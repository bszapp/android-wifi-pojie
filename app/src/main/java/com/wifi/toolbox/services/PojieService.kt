package com.wifi.toolbox.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.*
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.*
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.ui.MainActivity
import com.wifi.toolbox.utils.ActivityStack
import com.wifi.toolbox.utils.ApiUtil
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.pow

class PojieService : Service() {

    val NOTIFICATION_CHANNEL_ID = "PojieServiceChannel"

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var readLogMode = 0
    private var logcatService: WifiLogcatService? = null
    private var broadcastService: WifiBroadcastService? = null

    private var connectWifiApi29Callback: ConnectivityManager.NetworkCallback? = null
    private var lastConnectNetId: Int? = null

    fun log(log: String) {
        (applicationContext as MyApplication).logState.addLog(log)
    }

    private lateinit var sharedPreferences: SharedPreferences

    private var _pojieSettings: PojieSettings? = null

    private val pojieSettings: PojieSettings
        get() = _pojieSettings ?: PojieSettings.from(sharedPreferences).also { _pojieSettings = it }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        _pojieSettings = PojieSettings.from(prefs)
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings_pojie", MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun stopAllTasks() {
        val app = applicationContext as MyApplication
        app.runningPojieTasks.clear()
        currentWorkerJob?.cancel()
    }

    private fun calculatePriority(task: PojieRunInfo): Long {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - task.lastTryTime

        val priority = -timeDiff
        return priority
    }

    private suspend fun performTaskLogic(app: MyApplication, task: SinglePojieTask): Int {
        val startTime = System.currentTimeMillis()
        val connectMode = pojieSettings.connectMode
        when (connectMode) {
            0 -> throw Exception("连接wifi实现为空，请先去设置中选择")
            1 -> lastConnectNetId = ShizukuUtil.connectToWifi(task.ssid, task.password)
            2 -> {
                lastConnectNetId = ApiUtil.connectToWifiApi28(this, task.ssid, task.password)
                if (lastConnectNetId == -1) throw Exception("请求发送失败，请先手动忘记此网络")
            }

            3 -> {}
            else -> throw Exception("前面的区域，以后再来探索吧(connectMode=${pojieSettings.connectMode})")
        }

        return withTimeoutOrNull(timeMillis = app.pojieConfig.maxTryTime.toLong()) {
            suspendCancellableCoroutine<Int> { continuation ->

                val collectJob = launch {

                    if (connectMode == 3) {
                        connectWifiApi29Callback =
                            connectToWifiApi29(task.ssid, task.password) { success ->
                                if (continuation.isActive) {
                                    if (success) {
                                        continuation.resume(SinglePojieTask.RESULT_SUCCESS)
                                        cancel()
                                    } else {
                                        continuation.resume(SinglePojieTask.RESULT_FAILED)
                                        cancel()
                                    }
                                }
                            }
                    }

                    when (readLogMode) {
                        1 -> {
                            logcatService?.logFlow?.collect { data ->
                                if (continuation.isActive) {
                                    if (connectMode == 3 ||
                                        (data.ssid == task.ssid && data.eventStartTime >= startTime)
                                    ) {
                                        when (data.event) {
                                            WifiLogData.EVENT_WIFI_CONNECTED -> {
                                                if (connectMode != 3) {
                                                    continuation.resume(SinglePojieTask.RESULT_SUCCESS)
                                                    cancel()
                                                }
                                            }

                                            WifiLogData.EVENT_CONNECT_FAILED -> {
                                                if (System.currentTimeMillis() - data.eventStartTime > 2000) {
                                                    continuation.resume(SinglePojieTask.RESULT_FAILED)
                                                    cancel()
                                                }
                                            }

                                            WifiLogData.EVENT_HANDSHAKE -> {
                                                when (app.pojieConfig.failureFlag) {
                                                    1 -> if (data.handshakeUseTime > app.pojieConfig.timeout) {
                                                        continuation.resume(SinglePojieTask.RESULT_FAILED)
                                                        cancel()
                                                    }

                                                    2 -> {
                                                        if (data.handshakeCount > app.pojieConfig.maxHandshakeCount) {
                                                            continuation.resume(SinglePojieTask.RESULT_FAILED)
                                                            cancel()
                                                        }
                                                    }
                                                }
                                            }

                                            WifiLogData.EVENT_CONNECT_ERROR -> {
                                                continuation.resume(SinglePojieTask.RESULT_ERROR_TRANSIENT)
                                                cancel()
                                            }
                                        }
                                    } else {
                                        log("W: 信息不匹配，实际${data.ssid}，应为${task.ssid}，请不要同时连接其他网络")
                                    }
                                }
                            }
                        }

                        2 -> {
                            if (app.pojieConfig.failureFlag == 2) {
                                throw Exception("广播监听模式不支持按握手次数判定，请在改为“握手超时”或切换为Logcat模式")
                            }

                            broadcastService?.setTargetSsid(task.ssid)
                            broadcastService?.logFlow?.collect { data ->
                                if (continuation.isActive && data.ssid == task.ssid) {
                                    when (data.event) {
                                        WifiLogData.EVENT_WIFI_CONNECTED -> {
                                            if (connectMode != 3) {
                                                continuation.resume(SinglePojieTask.RESULT_SUCCESS)
                                                cancel()
                                            }
                                        }

                                        WifiLogData.EVENT_CONNECT_FAILED -> {
                                            continuation.resume(SinglePojieTask.RESULT_FAILED)
                                            cancel()
                                        }

                                        WifiLogData.EVENT_HANDSHAKE -> {
                                            if (data.handshakeUseTime > app.pojieConfig.timeout) {
                                                continuation.resume(SinglePojieTask.RESULT_FAILED)
                                                cancel()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    collectJob.cancel()
                }
            }
        } ?: SinglePojieTask.RESULT_TIMEOUT
    }

    private var currentWorkerJob: Job? = null

    @Volatile
    private var currentWorkingSsid: String? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun getLogTime(): String {
        val df = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return "[${df.format(java.util.Date())}]"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as MyApplication
        try {
            createNotificationChannel()
            val notificationIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("target", "Pojie")
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("密码字典破解服务").setContentText("正在运行")
                .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(pendingIntent)
                .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()

            startForeground(1, notification)

            if (currentWorkerJob == null) {
                app.logState.clear()
                log(
                    log = """      _      __                 _        
     | |___ / _|_ __ ___  _   _| |_ __ _ 
  _  | / __| |_| '_ ` _ \| | | | __/ _` |
 | |_| \__ \  _| | | | | | |_| | || (_| |
  \___/|___/_| |_| |_| |_|\__, |\__\__, |
                          |___/    |___/ 
==========================================
wifi密码暴力破解工具 v3 for Android
"""
                )
                readLogMode = pojieSettings.readLogMode
                when (readLogMode) {
                    0 -> throw Exception("读取网络日志实现为空，请先去设置中选择")
                    1 -> {
                        logcatService = WifiLogcatService(pojieSettings)
                        log("Logcat服务已启动")
                    }

                    2 -> {
                        broadcastService = WifiBroadcastService(this)
                        log("广播监听服务已启动")
                    }
                }
                startServiceLogic()
            }
            return START_STICKY
        } catch (e: Exception) {
            stopAllTasks()
            log("E: 服务启动失败")
            log(e.toString())
            stop()
            return START_NOT_STICKY
        }
    }

    private fun stop() {
        log("[运行结束]")
        logcatService?.close()
        broadcastService?.close()
        stopSelf()
    }

    private fun startServiceLogic() {
        val app = applicationContext as MyApplication

        serviceScope.launch {
            while (isActive) {
                //注：专门针对某些设备密码错误就不管什么页面都弹窗的反人类设计
                //但是运行期间打不开设置应用，所以TODO加个开关
                if (pojieSettings.connectMode != 3)//好像忘了点什么事情，咋不会弹窗确认连接呢
                    ShizukuUtil.executeCommandSync("am force-stop com.android.settings")
                delay(100)
            }
        }

        serviceScope.launch {
            launch {
                snapshotFlow { app.runningPojieTasks.toList() }.collect { currentList ->
                    val targetSsid = currentWorkingSsid
                    if (currentList.isEmpty() || (targetSsid != null && currentList.none { it.ssid == targetSsid })) {
                        currentWorkerJob?.cancel()
                    }
                }
            }

            launch {
                snapshotFlow { app.runningPojieTasks.size }.collect {
                    if (currentWorkingSsid == null) {
                        currentWorkerJob?.cancel()
                    }
                }
            }

            while (isActive) {
                val now = System.currentTimeMillis()
                val tasks = app.runningPojieTasks
                val config = app.pojieConfig

                if (tasks.isEmpty()) {
                    stop()
                    break
                }

                val availableTasks = tasks.filter {
                    val waitTime = if (it.retryCount > 0) {
                        (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * config.doublingBase
                    } else 0L

                    val isReady = now - it.lastTryTime >= waitTime
                    isReady
                }

                val task = availableTasks.minByOrNull { calculatePriority(it) }

                if (task == null) {
                    val nextAvailableTime = tasks.minOf {
                        val waitTime = if (it.retryCount > 0) {
                            (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * config.doublingBase
                        } else 0L
                        it.lastTryTime + waitTime
                    }
                    val waitMs = maxOf(0L, nextAvailableTime - now)
                    if (waitMs > 0) {
                        currentWorkerJob = launch {
                            log("冷却中，等待${waitMs}ms")
                            delay(waitMs)
                        }
                        try {
                            currentWorkerJob?.join()
                        } catch (_: CancellationException) {
                            return@launch
                        }
                        currentWorkerJob = null
                        if (!isActive) return@launch
                    }
                    continue
                }

                currentWorkingSsid = task.ssid
                val currentPass = task.tryList.getOrNull(task.tryIndex) ?: "未知"

                app.updateTaskState(task.ssid) {
                    it.copy(
                        textTip = "正在尝试：$currentPass", lastTryTime = System.currentTimeMillis()
                    )
                }

                var taskResult = -1

                currentWorkerJob = launch {
                    try {
                        taskResult = performTaskLogic(
                            app, SinglePojieTask(
                                ssid = task.ssid, password = currentPass
                            )
                        )
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            log("E: 任务执行出错：${e.toString()}")
                        }
                        taskResult = SinglePojieTask.RESULT_ERROR
                    }
                }

                var timeTag = getLogTime()
                log("$timeTag 尝试: (${task.ssid}, $currentPass) ...")

                currentWorkerJob?.join()
                currentWorkingSsid = null

                timeTag = getLogTime()
                if (currentWorkerJob?.isCancelled == true) {
                    app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: 任务中断")
                    app.finishedPojieTasksTip[task.ssid]="任务中断(index=${task.tryIndex})"
                    forgetLastNetwork()
                } else {
                    if (taskResult != SinglePojieTask.RESULT_ERROR) {
                        app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: $taskResult")
                    }else{
                        app.finishedPojieTasksTip[task.ssid]="执行出错，请查看输出"
                    }
                }

                if (connectWifiApi29Callback != null) {
                    connectWifiApi29Callback?.let {
                        ApiUtil.cancelWifiRequest(
                            this@PojieService, it
                        )
                    }
                    connectWifiApi29Callback = null
                } else when (pojieSettings.enableMode) {
                    1 -> ShizukuUtil.disconnectWifi()
                    2 -> ApiUtil.disconnectWifi(this@PojieService)
                }

                when (taskResult) {
                    SinglePojieTask.RESULT_SUCCESS -> {
                        log("连接成功: (${task.ssid}, $currentPass)")
                        app.finishedPojieTasksTip[task.ssid] = "连接成功：$currentPass"
                        app.stopTaskByName(task.ssid)
                    }

                    SinglePojieTask.RESULT_FAILED -> {
                        processTaskCompletion(app, task.ssid)
                        app.updateTaskState(task.ssid) { it.copy(retryCount = 0) }
                    }

                    SinglePojieTask.RESULT_ERROR -> {
                        app.stopTaskByName(task.ssid)
                    }

                    SinglePojieTask.RESULT_TIMEOUT, SinglePojieTask.RESULT_ERROR_TRANSIENT -> {
                        app.updateTaskState(task.ssid) { it.copy(retryCount = it.retryCount + 1) }
                        val updatedTask = getTask(app, task.ssid)
                        if (updatedTask != null && app.pojieConfig.retryCountType <= 5 && updatedTask.retryCount > app.pojieConfig.retryCountType) {
                            app.updateTaskState(task.ssid) { it.copy(retryCount = 0) }
                            processTaskCompletion(app, task.ssid)
                        }
                    }
                }

                currentWorkingSsid = null
                currentWorkerJob = null
            }
        }
    }

    private fun forgetLastNetwork() {
        lastConnectNetId?.let {
            when (pojieSettings.connectMode) {
                1 -> ShizukuUtil.forgetNetwork(it)
                2 -> ApiUtil.forgetNetwork(this@PojieService, it)
            }
        }
    }

    private fun getTask(app: MyApplication, ssid: String): PojieRunInfo? {
        return app.runningPojieTasks.find { it.ssid == ssid }
    }

    private fun processTaskCompletion(app: MyApplication, ssid: String) {
        val task = getTask(app, ssid) ?: return
        val nextIndex = task.tryIndex + 1

        if (nextIndex >= task.tryList.size) {
            forgetLastNetwork()
            app.finishedPojieTasksTip[ssid] = "全部尝试失败(size=${task.tryList.size})"
            app.stopTaskByName(ssid)
        } else {
            app.updateTaskState(ssid) {
                it.copy(
                    tryIndex = nextIndex, textTip = "排队中"
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "密码字典破解服务", NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private suspend fun connectToWifiApi29(
        ssid: String, pass: String, callback: (Boolean) -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val foregroundActivity = ActivityStack.get()

        return if (foregroundActivity != null) {
            withContext(Dispatchers.Main) {
                ApiUtil.connectToWifiApi29(foregroundActivity, ssid, pass, callback)
            }
        } else {
            ApiUtil.connectToWifiApi29(this, ssid, pass, callback)
        }
    }
}