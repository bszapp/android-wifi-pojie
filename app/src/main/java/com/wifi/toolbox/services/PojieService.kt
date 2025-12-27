package com.wifi.toolbox.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.SinglePojieTask
import com.wifi.toolbox.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PojieService : Service() {

    private val NOTIFICATION_CHANNEL_ID = "PojieServiceChannel"
    private val NOTIFICATION_ID = 1
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())


    private fun calculatePriority(task: PojieRunInfo): Long {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - task.lastTryTime

        val priority = -timeDiff
        return priority
    }

    private suspend fun performTaskLogic(app: MyApplication, task: SinglePojieTask): Int {
        delay(3000)
        return SinglePojieTask.RESULT_FAILED
    }

    private var currentWorkerJob: Job? = null

    @Volatile
    private var currentWorkingSsid: String? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("target", "Pojie")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("密码字典破解服务")
            .setContentText("运行中...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        if (currentWorkerJob == null && currentWorkingSsid == null) {
            val app = applicationContext as MyApplication
            app.logState.clear()
            app.logState.addLog(
                log = """      _      __                 _        
     | |___ / _|_ __ ___  _   _| |_ __ _ 
  _  | / __| |_| '_ ` _ \| | | | __/ _` |
 | |_| \__ \  _| | | | | | |_| | || (_| |
  \___/|___/_| |_| |_| |_|\__, |\__\__, |
                          |___/    |___/ 
==========================================
wifi密码暴力破解工具v2 for Android
"""
            )
            startServiceLogic()
        }

        return START_STICKY
    }

    private fun startServiceLogic() {
        serviceScope.launch {
            val app = applicationContext as MyApplication

            launch {
                snapshotFlow { app.runningPojieTasks.toList() }
                    .collect { currentList ->
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
                    app.logState.addLog("[运行结束]")
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
                    taskResult = performTaskLogic(
                        app, SinglePojieTask(
                            ssid = task.ssid,
                            password = currentPass
                        )
                    )
                }

                app.logState.addLog("尝试: (${task.ssid}, $currentPass) ...")

                currentWorkerJob?.join()

                if (currentWorkerJob?.isCancelled == true) {
                    app.logState.setLine("尝试: (${task.ssid}, $currentPass) 结果: 任务中断")
                } else {
                    app.logState.setLine("尝试: (${task.ssid}, $currentPass) 结果: $taskResult")
                    processTaskCompletion(app, task.ssid)
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
            app.stopTask(ssid)
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
        app: MyApplication,
        ssid: String,
        transform: (PojieRunInfo) -> PojieRunInfo
    ) {
        val index = app.runningPojieTasks.indexOfFirst { it.ssid == ssid }
        if (index != -1) {
            app.runningPojieTasks[index] = transform(app.runningPojieTasks[index])
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "密码字典破解服务",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
}