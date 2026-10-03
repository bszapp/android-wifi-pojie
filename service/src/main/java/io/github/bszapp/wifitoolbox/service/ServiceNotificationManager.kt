package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTarget
import io.github.bszapp.wifitoolbox.contract.task.ConnectWifiTaskType
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import io.github.bszapp.wifitoolbox.contract.task.TaskRequestPayload
import io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiMode
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 服务状态的通知投影；网卡和任务状态始终从各自的管理器读取。 */
@SuppressLint("PrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi")
internal class ServiceNotificationManager(
    private val modeProvider: () -> WifiMode,
    private val taskProvider: () -> TaskSnapshot?,
    private val hashcatTaskCountProvider: () -> Int = { 0 },
    private val appUserIdProvider: () -> Int,
    private val onError: (String, Throwable) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "service-notification").apply { isDaemon = true }
    }
    private var started = false
    private var closed = false
    private var refreshPending = false
    private var backend: NotificationBackend? = null
    private val published = mutableMapOf<Int, String?>()
    private var lastError: String? = null

    fun start(): Unit = synchronized(lock) {
        if (closed || started) return
        started = true
        refresh()
    }

    /** 合并高频模式/任务进度回调，在独立线程调用 Binder，保持服务自身调用身份。 */
    fun refresh(): Unit = synchronized(lock) {
        if (!started || closed || refreshPending) return
        refreshPending = true
        executor.execute {
            synchronized(lock) {
                refreshPending = false
                if (closed) return@execute
            }
            runCatching {
                val modeText = if (modeProvider() == WifiMode.MONITOR) {
                    "当前网卡处于monitor模式"
                } else null
                val regularTaskText = taskProvider()
                    ?.takeIf { it.state == TaskExecutionState.RUNNING }
                    ?.let { "正在运行${taskName(it)}任务" }
                val hashcatCount = hashcatTaskCountProvider()
                val taskText = listOfNotNull(regularTaskText,
                    if (hashcatCount > 0) "正在运行${hashcatCount}个 WPA Hashcat 任务" else null,
                ).takeIf { it.isNotEmpty() }?.joinToString("；")
                val api = backend ?: NotificationBackend(appUserIdProvider()).also { backend = it }
                update(api, MODE_NOTIFICATION_ID, modeText)
                update(api, TASK_NOTIFICATION_ID, taskText)
                lastError = null
            }.onFailure { reportError("更新服务常驻通知", it) }
        }
    }

    private fun update(api: NotificationBackend, id: Int, text: String?) {
        if (published.containsKey(id) && published[id] == text) return
        if (text == null) api.cancel(id) else api.post(id, text)
        published[id] = text
    }

    override fun close() {
        val cleanup = synchronized(lock) {
            if (closed) return
            closed = true
            executor.submit {
                backend?.let { api ->
                    listOf(MODE_NOTIFICATION_ID, TASK_NOTIFICATION_ID).forEach { id ->
                        runCatching { api.cancel(id) }
                            .onFailure { reportError("撤下服务常驻通知", it) }
                    }
                    runCatching { api.restoreExemption() }
                        .onFailure { reportError("恢复通知 AppOps 豁免", it) }
                }
                published.clear()
            }
        }
        executor.shutdown()
        runCatching { cleanup.get(5, TimeUnit.SECONDS) }
            .onFailure { reportError("清理服务通知", it) }
    }

    private fun reportError(operation: String, error: Throwable) {
        val key = "$operation:${error.javaClass.name}:${error.message}"
        if (lastError == key) return
        lastError = key
        Log.e(TAG, operation, error)
        onError(operation, error)
    }

    private fun taskName(task: TaskSnapshot): String = when (val payload = task.request.payload) {
        is TaskRequestPayload.WpsPbc -> "WPS-PBC"
        is TaskRequestPayload.ConnectWifi -> {
            val input = payload.request.input
            when {
                input.target is ConnectWifiTarget.NetworkCard -> "测试连通性（网卡）"
                input.type == ConnectWifiTaskType.USE_SAVED_NETWORK -> "使用已保存的网络连接"
                else -> "连接到网络"
            }
        }
    }

    private class NotificationBackend(private val appUserId: Int) {
        private val uid = Process.myUid()
        private val userId = uid / PER_USER_RANGE
        private val shellUid = userId * PER_USER_RANGE + 2000
        private val opPackage = if (uid % PER_USER_RANGE == 2000) SHELL_PACKAGE else "android"
        private val notifications = SystemApi("notification", "android.app.INotificationManager")
        private var appOps: SystemApi? = null
        private var exemptionOp: Int? = null
        private var originalExemptionMode: Int? = null
        private var ready = false
        private val contentIntent: PendingIntent by lazy { createOpenAppIntent() }

        fun post(id: Int, text: String) {
            prepare()
            val notification = createNotification(text)
            if (notifications.hasMethod("enqueueNotificationWithTag", 6)) {
                notifications.call("enqueueNotificationWithTag", SHELL_PACKAGE, opPackage,
                    NOTIFICATION_TAG, id, notification, userId)
            } else {
                // Android 7 的接口还包含返回通知 ID 的 int[]。
                notifications.call("enqueueNotificationWithTag", SHELL_PACKAGE, opPackage,
                    NOTIFICATION_TAG, id, notification, intArrayOf(id), userId)
            }
            Log.i(TAG, "通知已提交：id=$id text=$text callerUid=$uid ownerUid=$shellUid")
        }

        fun cancel(id: Int) {
            if (notifications.hasMethod("cancelNotificationWithTag", 5)) {
                notifications.call("cancelNotificationWithTag", SHELL_PACKAGE, opPackage,
                    NOTIFICATION_TAG, id, userId)
            } else {
                notifications.call("cancelNotificationWithTag", SHELL_PACKAGE,
                    NOTIFICATION_TAG, id, userId)
            }
        }

        private fun prepare() {
            if (ready) return
            check(uid % PER_USER_RANGE in listOf(0, 1000, 2000)) { "服务通知需要 Root、system 或 Shell 权限" }
            if (Build.VERSION.SDK_INT >= 34) enableExemption()
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(CHANNEL_ID, TITLE, NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                val slice = Class.forName("android.content.pm.ParceledListSlice")
                    .getConstructor(List::class.java).newInstance(listOf(channel))
                if (uid % PER_USER_RANGE == 2000) {
                    notifications.call("createNotificationChannels", SHELL_PACKAGE, slice)
                } else {
                    // root/system 代 Shell 创建频道，频道的 UID 必须与通知归属 UID 相同。
                    notifications.call("createNotificationChannelsForPackage", SHELL_PACKAGE, shellUid, slice)
                }
            }
            ready = true
        }

        private fun enableExemption() {
            val api = appOps ?: SystemApi("appops", "com.android.internal.app.IAppOpsService")
                .also { appOps = it }
            val op = AppOpsManager::class.java
                .getDeclaredField("OP_SYSTEM_EXEMPT_FROM_DISMISSIBLE_NOTIFICATIONS")
                .apply { isAccessible = true }.getInt(null)
            exemptionOp = op
            if (originalExemptionMode == null) {
                originalExemptionMode = api.call("checkOperationRaw", op, shellUid, SHELL_PACKAGE, null) as Int
            }
            api.call("setMode", op, shellUid, SHELL_PACKAGE, AppOpsManager.MODE_ALLOWED)
            check(api.call("checkOperation", op, shellUid, SHELL_PACKAGE) == AppOpsManager.MODE_ALLOWED) {
                "Shell 的通知不可关闭豁免未生效"
            }
            Log.i(TAG, "通知不可关闭豁免已启用：owner=$SHELL_PACKAGE uid=$shellUid callerUid=$uid")
        }

        fun restoreExemption() {
            val op = exemptionOp ?: return
            val original = originalExemptionMode ?: return
            appOps?.call("setMode", op, shellUid, SHELL_PACKAGE, original)
            Log.i(TAG, "通知 AppOps 已恢复：uid=$shellUid mode=$original")
        }

        private fun createOpenAppIntent(): PendingIntent {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(APP_PACKAGE, "$APP_PACKAGE.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val api = if (Build.VERSION.SDK_INT >= 26) {
                SystemApi("activity", "android.app.IActivityManager")
            } else {
                SystemApi("activity", "android.app.IActivityManager", "android.app.ActivityManagerNative")
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val sender = if (api.hasMethod("getIntentSenderWithFeature", 11)) {
                api.call("getIntentSenderWithFeature", 2, opPackage, null, null, null,
                    MODE_NOTIFICATION_ID, arrayOf(intent), arrayOfNulls<String>(1), flags, null, appUserId)
            } else {
                api.call("getIntentSender", 2, opPackage, null, null,
                    MODE_NOTIFICATION_ID, arrayOf(intent), arrayOfNulls<String>(1), flags, null, appUserId)
            } ?: error("系统未创建打开应用的 PendingIntent")
            return PendingIntent::class.java
                .getDeclaredConstructor(Class.forName("android.content.IIntentSender"))
                .apply { isAccessible = true }.newInstance(sender)
        }

        private fun createNotification(text: String): Notification {
            // 独立 app_process 没有 AMS 注册的 ApplicationThread。Android 15 的 Notification
            // 构造器读取 DeviceConfig 会访问 Settings Provider 并报错，使用已验证的 Parcel 字段构建。
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe")
                .apply { isAccessible = true }.get(null)
            val notification = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, Notification::class.java) as Notification
            // MIUI 的 Notification.writeToParcel 还要求其扩展对象非空。
            Notification::class.java.declaredFields
                .filter { it.type.name == "android.app.MiuiNotification" }
                .forEach { field ->
                    field.isAccessible = true
                    field.set(notification, field.type.getDeclaredConstructor()
                        .apply { isAccessible = true }.newInstance())
                }
            if (Build.VERSION.SDK_INT >= 26) {
                Notification::class.java.getDeclaredField("mChannelId")
                    .apply { isAccessible = true }.set(notification, CHANNEL_ID)
            }
            val iconId = android.R.drawable.stat_notify_more
            Notification::class.java.getDeclaredField("mSmallIcon")
                .apply { isAccessible = true }
                .set(notification, Icon.createWithResource("android", iconId))
            @Suppress("DEPRECATION")
            notification.icon = iconId
            notification.`when` = System.currentTimeMillis()
            notification.flags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR or
                Notification.FLAG_ONLY_ALERT_ONCE
            // FLAG_NO_DISMISS 由 NMS 根据 AppOps 豁免附加，不自行伪造该标记。
            notification.category = Notification.CATEGORY_SERVICE
            notification.contentIntent = contentIntent
            notification.extras = Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE, TITLE)
                putCharSequence(Notification.EXTRA_TEXT, text)
                putBoolean(Notification.EXTRA_SHOW_WHEN, false)
            }
            return notification
        }
    }

    private class SystemApi(
        serviceName: String,
        interfaceName: String,
        stubName: String = "$interfaceName\$Stub",
    ) {
        private val type = Class.forName(interfaceName)
        private val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, serviceName)
            .let { binder ->
                check(binder is IBinder) { "系统服务 $serviceName 不存在" }
                Class.forName(stubName).getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder) ?: error("$interfaceName.asInterface 返回 null")
            }

        fun hasMethod(name: String, count: Int): Boolean =
            type.methods.any { it.name == name && it.parameterTypes.size == count }

        fun call(name: String, vararg args: Any?): Any? {
            val method = type.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: throw NoSuchMethodException("${type.name}.$name(${args.size} 个参数)")
            return try {
                method.invoke(service, *args)
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
        }
    }

    companion object {
        private const val TAG = "ServiceNotification"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val PER_USER_RANGE = 100_000
        private const val TITLE = "WifiToolbox Service"
        private const val CHANNEL_ID = "wifitoolbox_service"
        private const val NOTIFICATION_TAG = "WifiToolbox.Service"
        private const val MODE_NOTIFICATION_ID = 0x57465401
        private const val TASK_NOTIFICATION_ID = 0x57465402
    }
}
