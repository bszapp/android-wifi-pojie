package com.wifi.toolbox

import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wifi.toolbox.structs.PojieConfig
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.utils.LogState
import com.wifi.toolbox.utils.ShizukuUtil.REQUEST_PERMISSION_CODE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import android.content.Intent
import androidx.compose.runtime.mutableStateMapOf
import com.wifi.toolbox.services.PojieService
import com.wifi.toolbox.utils.ActivityStack


class MyApplication : Application() {

    data class AlertDialogData(
        val title: String,
        val text: String
    )

    var pojieConfig by mutableStateOf(PojieConfig())
        private set

    var logState by mutableStateOf(LogState())
        private set

    private val _alertDialogState = MutableSharedFlow<AlertDialogData?>(replay = 1)
    val alertDialogState = _alertDialogState.asSharedFlow()

    private val _shizukuPermissionEvents = MutableSharedFlow<Boolean>()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQUEST_PERMISSION_CODE) {
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            appScope.launch {
                _shizukuPermissionEvents.emit(granted)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            ShizukuProvider.enableMultiProcessSupport(true)
        } catch (_: Throwable) {
        }

        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                ActivityStack.register(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                ActivityStack.unregister()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    override fun onTerminate() {
        super.onTerminate()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        appScope.cancel()
    }

    fun updatePojieConfig(newConfig: PojieConfig) {
        pojieConfig = newConfig
    }

    fun alert(title: String, text: String) {
        appScope.launch {
            _alertDialogState.emit(AlertDialogData(title, text))
        }
    }

    fun dismissAlert() {
        appScope.launch {
            _alertDialogState.emit(null)
        }
    }

    data class SnackbarData(
        val message: String,
        val actionLabel: String? = null,
        val onActionClick: (() -> Unit)? = null
    )

    private val _snackbarState = MutableSharedFlow<SnackbarData>()
    val snackbarState = _snackbarState.asSharedFlow()

    fun snackbar(
        message: String,
        actionLabel: String? = "知道了",
        onActionClick: (() -> Unit)? = null
    ) {
        appScope.launch {
            _snackbarState.emit(SnackbarData(message, actionLabel, onActionClick))
        }
    }

    fun requestShizukuPermission(onSuccess: () -> Unit) {
        Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        appScope.launch {
            try {
                val granted = _shizukuPermissionEvents.first()
                if (granted) {
                    onSuccess()
                }
            } catch (_: Throwable) {
            }
        }
    }

    val runningPojieTasks = mutableStateListOf<PojieRunInfo>()
    val finishedPojieTasksTip = mutableStateMapOf<String, String>()

    fun startTask(data: PojieRunInfo) {
        if (runningPojieTasks.none { it.ssid == data.ssid }) {
            if (runningPojieTasks.isEmpty()) {
                val serviceIntent = Intent(this, PojieService::class.java)
                startService(serviceIntent)
            }
            runningPojieTasks.add(data)
            finishedPojieTasksTip.remove(data.ssid)
        }
    }

    fun stopTaskByName(ssid: String) {
        runningPojieTasks.removeIf { it.ssid == ssid }
    }

    fun updateTaskState(ssid: String, transform: (PojieRunInfo) -> PojieRunInfo) {
        val index = runningPojieTasks.indexOfFirst { it.ssid == ssid }
        if (index != -1) {
            runningPojieTasks[index] = transform(runningPojieTasks[index])
        }
    }
}