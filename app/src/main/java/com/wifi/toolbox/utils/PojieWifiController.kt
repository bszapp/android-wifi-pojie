package com.wifi.toolbox.utils

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.ui.items.*
import kotlinx.coroutines.*

interface PojieWifiController {
    val uiState: ScreenState
    val isScanning: Boolean
    val trigger: Int
    val runningTasks: List<PojieRunInfo>
    val finishedInfo: SnapshotStateMap<String, String>
    fun reload()
    fun fetchResults(): ScanResult
    fun toggleWifiOn()
    fun applyLocation()
    fun enableLocation()
    fun disconnectWifi()
}

@Composable
fun rememberPojieWifiController(
    context: Context,
    app: MyApplication,
    settings: PojieSettings
): PojieWifiController {
    val scope = rememberCoroutineScope()
    var uiState by rememberSaveable { mutableStateOf<ScreenState>(ScreenState.Idle) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var trigger by rememberSaveable { mutableIntStateOf(0) }
    var showScanResult by rememberSaveable { mutableStateOf(true) }

    val currentRunningTasks = app.runningPojieTasks
    val currentFinishedTasks = app.finishedPojieTasksTip


    return remember(
        settings,
        uiState,
        refreshJob,
        trigger,
        showScanResult,
        currentRunningTasks.size
    ) {
        object : PojieWifiController {
            override val uiState = uiState
            override val isScanning = refreshJob?.isActive == true
            override val trigger = trigger
            override val runningTasks = currentRunningTasks
            override val finishedInfo = currentFinishedTasks

            val MIN_SCAN_TIME = 500
            val MAX_SCAN_TIME = 3000
            val SCAN_INTERVAL = 100


            override fun reload() {
                refreshJob?.cancel()
                refreshJob = scope.launch {
                    val start = scanInternal()
                    when (start.code) {
                        StartScanResult.CODE_SUCCESS, StartScanResult.CODE_SEND_FAIL -> {
                            val sendSucceed = start.code == StartScanResult.CODE_SUCCESS
                            uiState = ScreenState.Success(sendSucceed)
                            if (sendSucceed) {
                                showScanResult = false
                                repeat(MIN_SCAN_TIME / SCAN_INTERVAL) { //心理作用？
                                    trigger++
                                    delay(SCAN_INTERVAL.toLong())
                                }
                                showScanResult = true
                                repeat((MAX_SCAN_TIME - MIN_SCAN_TIME) / SCAN_INTERVAL) {
                                    trigger++
                                    delay(SCAN_INTERVAL.toLong())
                                }
                            } else {
                                showScanResult = false
                                trigger++
                                delay(MIN_SCAN_TIME.toLong()) //心理作用？
                                showScanResult = true
                                trigger++
                            }
                            refreshJob = null
                        }

                        StartScanResult.CODE_SCAN_FAIL -> uiState = ScreenState.Error(
                            start.errorMessage ?: "扫描失败",
                            StartScanResult.CODE_SCAN_FAIL
                        )

                        StartScanResult.CODE_WIFI_NOT_ENABLED -> uiState = ScreenState.Error(
                            "wifi未开启",
                            StartScanResult.CODE_WIFI_NOT_ENABLED
                        )

                        StartScanResult.CODE_LOCATION_NOT_ENABLED -> uiState = ScreenState.Error(
                            "定位服务未开启",
                            StartScanResult.CODE_LOCATION_NOT_ENABLED,
                        )

                        StartScanResult.CODE_LOCATION_NOT_ALLOWED -> uiState = ScreenState.Error(
                            "未获取定位权限",
                            StartScanResult.CODE_LOCATION_NOT_ALLOWED,
                        )


                        else -> uiState = ScreenState.Error(
                            "未知错误(${start.errorMessage})",
                            StartScanResult.CODE_UNKNOWN,
                        )
                    }
                }
            }

            private fun scanInternal(): StartScanResult {
                if (settings.scanMode == 0) return StartScanResult(
                    code = StartScanResult.CODE_SCAN_FAIL,
                    errorMessage = "扫描实现为空，请先在设置中选择"
                )
                if (!ApiUtil.isWifiEnabled(context)) return StartScanResult(
                    code = StartScanResult.CODE_WIFI_NOT_ENABLED
                )
                return try {
                    when (settings.scanMode) {
                        1 -> {
                            //系统隐藏API (Shizuku)
                            if (checkShizukuUI(app)) {
                                if (ShizukuUtil.startWifiScan(settings.allowScanUseCommand))
                                    StartScanResult(
                                        code = StartScanResult.CODE_SUCCESS
                                    )
                                else StartScanResult(
                                    code = StartScanResult.CODE_SEND_FAIL
                                )
                            } else StartScanResult(
                                code = StartScanResult.CODE_SCAN_FAIL,
                                errorMessage = "未获取到Shizuku权限"
                            )
                        }

                        2 -> {
                            if (ApiUtil.hasLocationPermission(context)) {
                                if (ApiUtil.isLocationEnabled(context)) {
                                    if (ApiUtil.startScan(context))
                                        StartScanResult(
                                            code = StartScanResult.CODE_SUCCESS
                                        )
                                    else StartScanResult(
                                        code = StartScanResult.CODE_SEND_FAIL
                                    )
                                } else {
                                    StartScanResult(
                                        code = StartScanResult.CODE_LOCATION_NOT_ENABLED
                                    )
                                }
                            } else {
                                StartScanResult(
                                    code = StartScanResult.CODE_LOCATION_NOT_ALLOWED
                                )
                            }
                        }

                        else -> {
                            StartScanResult(
                                code = StartScanResult.CODE_UNKNOWN,
                                errorMessage = "前面的区域，以后再来探索吧\n(scanMode=${settings.scanMode})"
                            )
                        }
                    }

                } catch (e: Exception) {
                    StartScanResult(StartScanResult.CODE_UNKNOWN, e.message)
                }
            }

            override fun fetchResults(): ScanResult {
                if (!showScanResult) return ScanResult()
                return try {
                    when (settings.scanMode) {
                        0 -> ScanResult(
                            code = StartScanResult.CODE_SCAN_FAIL,
                            errorMessage = "扫描实现为空，请先在设置中选择"
                        )

                        1 ->
                            ScanResult(
                                ScanResult.CODE_SUCCESS,
                                null,
                                ShizukuUtil.getWifiScanResults()
                                    .filter { it.ssid.isNotEmpty() }
                                    .distinctBy { it.ssid }
                            )

                        2 -> ScanResult(
                            ScanResult.CODE_SUCCESS,
                            null,
                            ApiUtil.getScanResults(context)
                                .filter { it.ssid.isNotEmpty() }
                                .distinctBy { it.ssid }
                        )

                        else ->
                            ScanResult(
                                code = StartScanResult.CODE_UNKNOWN,
                                errorMessage = "前面的区域，以后再来探索吧\n(scanMode=${settings.scanMode})"
                            )

                    }

                } catch (e: Exception) {
                    ScanResult(errorMessage = e.message)
                }
            }

            override fun toggleWifiOn() {
                when (settings.enableMode) {
                    0 -> app.alert("缺失参数", "开关wifi实现为空")
                    1 -> checkShizukuUI(app) {
                        ShizukuUtil.setWifiEnabled(true)
                        reload()
                    }

                    2 -> {
                        ApiUtil.setWifiEnabled(context, true)
                        reload()
                    }

                    else -> app.alert(
                        "缺失参数",
                        "前面的区域，以后再来探索吧(enableMode=${settings.enableMode})"
                    )
                }
            }

            override fun applyLocation() {
                if (ApiUtil.requestLocationPermission(context as Activity)) reload()
            }

            override fun enableLocation() {
                if (ApiUtil.enableLocation(context)) reload()
            }

            override fun disconnectWifi() {
                when (settings.enableMode) {
                    0 -> app.alert("缺失参数", "管理已保存网络实现为空")
                    1 -> {
                        ShizukuUtil.disconnectWifi()
                        trigger++
                    }

                    else -> app.alert(
                        "缺失参数",
                        "前面的区域，以后再来探索吧(enableMode=${settings.enableMode})"
                    )
                }
            }
        }
    }
}