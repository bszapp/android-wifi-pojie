package com.wifi.toolbox.utils

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.ScanResult
import com.wifi.toolbox.ui.items.ScreenState
import com.wifi.toolbox.ui.items.StartScanResult
import com.wifi.toolbox.ui.items.checkShizukuUI
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface PojieWifiController {
    val uiState: ScreenState
    val isScanning: Boolean
    val trigger: Int
    val runningTasks: List<PojieRunInfo>
    fun reload()
    fun fetchResults(): ScanResult
    fun toggleWifiOn()
    fun applyLocation()
    fun enableLocation()
}

@Composable
fun rememberPojieWifiController(
    context: Context,
    app: MyApplication,
    settings: PojieSettings
): PojieWifiController {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<ScreenState>(ScreenState.Idle) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var trigger by remember { mutableIntStateOf(0) }
    var showScanResult by remember { mutableStateOf(true) }

    val currentRunningTasks = app.runningPojieTasks

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

            val MIN_SCAN_TIME = 500
            val MAX_SCAN_TIME = 3000
            val SCAN_INTERVAL = 100


            override fun reload() {
                refreshJob?.cancel()
                refreshJob = scope.launch {
                    val start = scanInternal()
                    when (start.code) {
                        StartScanResult.CODE_SUCCESS -> {
                            uiState = ScreenState.Success
                            showScanResult = false
                            repeat(MIN_SCAN_TIME / SCAN_INTERVAL) {
                                trigger += 1
                                delay(SCAN_INTERVAL.toLong())
                            }
                            showScanResult = true
                            repeat((MAX_SCAN_TIME - MIN_SCAN_TIME) / SCAN_INTERVAL) {
                                trigger += 1
                                delay(SCAN_INTERVAL.toLong())
                            }
                            refreshJob = null
                        }

                        StartScanResult.CODE_SCAN_FAIL -> uiState = ScreenState.Error(
                            start.errorMessage ?: "扫描失败",
                            ScreenState.ERROR_SCAN_FAIL
                        )

                        StartScanResult.CODE_WIFI_NOT_ENABLED -> uiState = ScreenState.Error(
                            "wifi未开启",
                            ScreenState.ERROR_WIFI_NOT_ENABLED
                        )

                        else -> uiState = ScreenState.Error(
                            "未知错误(${start.errorMessage})",
                            ScreenState.ERROR_UNKNOWN
                        )
                    }
                }
            }

            private fun scanInternal(): StartScanResult {
                if (settings.scanMode == 0) return StartScanResult(
                    code = StartScanResult.CODE_SCAN_FAIL,
                    errorMessage = "扫描实现为空"
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
                                    code = StartScanResult.CODE_SCAN_FAIL,
                                    errorMessage = "扫描频率过快，被系统拒绝"
                                )
                            } else StartScanResult(
                                code = StartScanResult.CODE_SCAN_FAIL,
                                errorMessage = "未授权"
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
                                        code = StartScanResult.CODE_SCAN_FAIL,
                                        errorMessage = "扫描频率过快，被系统拒绝"
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
                    val result = ShizukuUtil.getWifiScanResults()
                    ScanResult(
                        ScanResult.CODE_SUCCESS,
                        null,
                        result.filter { it.ssid.isNotEmpty() }.distinctBy { it.ssid }
                    )
                } catch (e: Exception) {
                    ScanResult(errorMessage = e.message)
                }
            }

            override fun toggleWifiOn() {
                if (settings.enableMode == 1) checkShizukuUI(app) {
                    ShizukuUtil.setWifiEnabled(
                        true
                    )
                }
                else app.alert("缺失参数", "实现为空")
                reload()
            }

            override fun applyLocation() {
                if (ApiUtil.requestLocationPermission(context as Activity)) reload()
            }

            override fun enableLocation() {
                if (ApiUtil.enableLocation(context)) reload()
            }
        }
    }
}