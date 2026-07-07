package io.github.bszapp.wifitoolbox.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.AttributionSource
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import io.github.bszapp.wifitoolbox.contract.startup.StartupInfo
import java.lang.reflect.InvocationTargetException

/**
 * 统一处理 App 与 service 之间的通信：调用方校验、Provider 投递 Binder、callback 预留通道。
 */
@SuppressLint("PrivateApi")
class ServiceCommunication(
    private val startupInfoProvider: () -> StartupInfo,
    private val serviceBinderProvider: () -> IBinder,
) {
    private val callbacks = RemoteCallbackList<IMainServiceCallback>()
    private val publisherLock = Any()
    private val sdk = Build.VERSION.SDK_INT

    @Volatile
    private var binderPublisherRunning = false

    fun enforceStartupInitializer(info: StartupInfo) {
        info.requireLaunchInfo()
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (!isLocalCall(callingUid, callingPid) && callingUid != info.trustedUid) {
            throw SecurityException(
                "初始化调用方 uid=$callingUid pid=$callingPid 与启动信息 trustedUid=${info.trustedUid} 不一致"
            )
        }
    }

    fun <T> callFromApp(block: () -> T): T {
        enforceCallerIsApp()
        return block()
    }

    fun connect(): Boolean = callFromApp {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        Log.d(TAG, "connect() uid=$callingUid pid=$callingPid → 允许")
        true
    }

    fun isAlive(): Boolean = callFromApp { true }

    fun registerCallback(callback: IMainServiceCallback) = callFromApp {
        callbacks.register(callback)
    }

    fun unregisterCallback(callback: IMainServiceCallback) = callFromApp {
        callbacks.unregister(callback)
    }

    fun startBinderPublisher(reason: String) {
        synchronized(publisherLock) {
            if (binderPublisherRunning) return
            binderPublisherRunning = true
        }

        Thread({
            Log.d(TAG, "Binder 投递器启动：$reason")
            try {
                var deliveredInCurrentAppRun = false
                while (true) {
                    if (isTrustedAppProcessRunning()) {
                        if (!deliveredInCurrentAppRun && tryPushBinder()) {
                            Log.d(TAG, "Binder 已投递到应用进程")
                            deliveredInCurrentAppRun = true
                        }
                    } else {
                        deliveredInCurrentAppRun = false
                    }
                    Thread.sleep(1000L)
                }
            } catch (_: InterruptedException) {
            } catch (e: Throwable) {
                Log.e(TAG, "Binder 投递器异常，停止投递", e)
            } finally {
                synchronized(publisherLock) {
                    binderPublisherRunning = false
                }
            }
        }, "toolbox-binder-publisher").apply {
            isDaemon = true
            start()
        }
    }

    private fun enforceCallerIsApp() {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        if (isLocalCall(callingUid, callingPid)) return

        val expectedUid = startupInfoProvider().trustedUid
        if (expectedUid <= 0) {
            throw SecurityException("服务启动信息未初始化，拒绝 uid=$callingUid pid=$callingPid")
        }
        if (callingUid != expectedUid) {
            Log.w(TAG, "uid 不匹配：caller=$callingUid expected=$expectedUid")
            throw SecurityException("调用方 uid=$callingUid pid=$callingPid 未授权（期望 uid=$expectedUid）")
        }
    }

    private fun isLocalCall(callingUid: Int, callingPid: Int): Boolean =
        callingUid == Process.myUid() && callingPid == Process.myPid()

    private fun isTrustedAppProcessRunning(): Boolean {
        val trustedUid = startupInfoProvider().trustedUid
        val am = activityManager()
        val list = systemApi("getRunningAppProcesses") {
            am::class.java.getMethod("getRunningAppProcesses").invoke(am)
        } as? List<*> ?: throw IllegalStateException("getRunningAppProcesses 返回类型不是 List")

        return list.any { item ->
            val info = item as? ActivityManager.RunningAppProcessInfo
                ?: throw IllegalStateException("getRunningAppProcesses 返回了非 RunningAppProcessInfo 项：$item")
            info.uid == trustedUid
        }
    }

    @SuppressLint("NewApi")
    private fun tryPushBinder(): Boolean {
        val authority = PROVIDER_AUTHORITY
        var holder: Any? = null
        var failure: Throwable? = null

        try {
            val am = activityManager()
            val amClass = am::class.java

            holder = systemApi("getContentProviderExternal") {
                if (sdk >= 29) {
                    amClass.getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.java,
                        IBinder::class.java,
                        String::class.java
                    ).invoke(am, authority, 0, null, authority)
                } else {
                    amClass.getMethod(
                        "getContentProviderExternal",
                        String::class.java,
                        Int::class.java,
                        IBinder::class.java
                    ).invoke(am, authority, 0, null)
                }
            } ?: return false

            enforceProviderBelongsToTrustedApp(holder)

            val provider = holder::class.java
                .getField("provider")
                .get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider 为 null")

            val providerBinder = provider::class.java
                .getMethod("asBinder")
                .invoke(provider) as? IBinder
                ?: throw IllegalStateException("provider.asBinder 返回 null")

            if (!providerBinder.pingBinder()) return false

            val extras = Bundle().apply {
                putBinder(PROVIDER_BINDER_KEY, serviceBinderProvider())
            }
            val providerClass = provider::class.java
            val callerPackage = providerCallerPackage()

            val result = systemApi("ContentProvider.call") {
                when {
                    sdk >= 31 -> {
                        val attrSource = AttributionSource.Builder(Process.myUid())
                            .setPackageName(callerPackage)
                            .build()
                        providerClass.getMethod(
                            "call",
                            AttributionSource::class.java,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        ).invoke(provider, attrSource, authority, PROVIDER_METHOD, null, extras)
                    }
                    sdk >= 30 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, null, authority, PROVIDER_METHOD, null, extras)

                    sdk >= 29 -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, authority, PROVIDER_METHOD, null, extras)

                    else -> providerClass.getMethod(
                        "call",
                        String::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    ).invoke(provider, callerPackage, PROVIDER_METHOD, null, extras)
                }
            } as? Bundle ?: return false

            return result.getBoolean(PROVIDER_RESULT_OK)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            if (holder != null) {
                try {
                    releaseContentProviderExternal(authority)
                } catch (releaseError: Throwable) {
                    if (failure != null) failure.addSuppressed(releaseError) else throw releaseError
                }
            }
        }
    }

    private fun enforceProviderBelongsToTrustedApp(holder: Any) {
        val info = holder::class.java
            .getField("info")
            .get(holder) as? ProviderInfo
            ?: throw IllegalStateException("ContentProviderHolder.info 不是 ProviderInfo")

        val authorities = info.authority?.split(';')?.map { it.trim() }.orEmpty()
        if (PROVIDER_AUTHORITY !in authorities) {
            throw SecurityException("Provider authority 不匹配：${info.authority}")
        }

        val appInfo = info.applicationInfo
            ?: throw IllegalStateException("ProviderInfo.applicationInfo 为 null")

        if (appInfo.packageName != APP_PACKAGE) {
            throw SecurityException("Provider package 不匹配：${appInfo.packageName}")
        }

        val trustedUid = startupInfoProvider().trustedUid
        if (appInfo.uid != trustedUid) {
            throw SecurityException("Provider uid=${appInfo.uid} 与可信 uid=$trustedUid 不一致")
        }
    }

    private fun releaseContentProviderExternal(authority: String) {
        val am = activityManager()
        systemApi("removeContentProviderExternal") {
            am::class.java.getMethod(
                "removeContentProviderExternal",
                String::class.java,
                IBinder::class.java
            ).invoke(am, authority, null)
        }
    }

    private fun activityManager(): Any {
        val amBinder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity") as? IBinder
            ?: throw IllegalStateException("ActivityManager service 不存在")

        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, amBinder)
            ?: throw IllegalStateException("IActivityManager.asInterface 返回 null")
    }

    private inline fun <T> systemApi(apiName: String, block: () -> T): T {
        return try {
            block()
        } catch (e: InvocationTargetException) {
            val target = e.targetException ?: e
            throw when (target) {
                is RuntimeException -> target
                is Error -> target
                else -> IllegalStateException("$apiName 调用失败", target)
            }
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("$apiName 反射调用失败", e)
        }
    }

    private fun providerCallerPackage(): String = when (Process.myUid()) {
        0, 1000 -> "android"
        else -> "com.android.shell"
    }

    companion object {
        private const val TAG = "ServiceCommunication"
        private const val APP_PACKAGE = "io.github.bszapp.wifitoolbox"
        private const val PROVIDER_AUTHORITY = "io.github.bszapp.wifitoolbox.provider"
        private const val PROVIDER_METHOD = "sendBinder"
        private const val PROVIDER_BINDER_KEY = "binder"
        private const val PROVIDER_RESULT_OK = "ok"
    }
}
