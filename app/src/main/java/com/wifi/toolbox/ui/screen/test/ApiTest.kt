package com.wifi.toolbox.ui.screen.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.*
import kotlinx.coroutines.*


@Composable
fun ApiTest(logState: LogState, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val wifiScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            scope.launch {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    performWifiScan(context, logState)
                } else {
                    logState.addLog("E: 缺少位置权限，无法扫描Wi-Fi")
                }
            }
        } else {
            logState.addLog("E: 缺少位置权限，无法扫描Wi-Fi")
        }
    }

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(title = "设备控制", icon = Icons.Default.Devices)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = "打开wifi",
                        icon = Icons.Filled.Wifi,
                        onClick = {
                            val success = ApiUtil.setWifiEnabled(context, true)
                            if (success) {
                                logState.addLog("Wi-Fi 打开成功")
                            } else {
                                logState.addLog("请求已发送")
                            }
                        })
                    ActionChip(
                        text = "关闭wifi",
                        icon = Icons.Filled.WifiOff,
                        onClick = {
                            val success = ApiUtil.setWifiEnabled(context, false)
                            if (success) {
                                logState.addLog("Wi-Fi 关闭成功")
                            } else {
                                logState.addLog("请求已发送")
                            }
                        })
                    ActionChip(
                        text = "扫描wifi",
                        icon = Icons.Filled.Radar,
                        onClick = {
                            checkAndPerformWifiScan(context, logState, wifiScanLauncher, scope)
                        })
                }

                SectionDivider()

                SectionTitle(title = "连接wifi", icon = Icons.Default.InsertLink)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val id = ApiUtil.connectToWifiApi28(context, name, password)
                        if (id != -1) logState.addLog("请求已发送") else logState.addLog("失败(请忘记网络)")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("WifiManager (API 28)")
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ApiUtil.connectToWifiApi29(context, name, password) { success ->
                                logState.addLog(if (success) "连接成功" else "连接失败")
                            }
                        } else {
                            logState.addLog("设备版本过低")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text("WifiNetworkSpecifier (API 29)")
                }
            }
        }
    }
}

private suspend fun performWifiScan(context: Context, logState: LogState) {
    try {
        val success = ApiUtil.startScan(context)
        if (!success) {
            logState.addLog("W: 扫描频率过快，请求被系统拒绝")
        } else {
            logState.addLog("请求已发送，3秒后获取结果")
        }

        delay(3000)

        if (!ApiUtil.hasLocationPermission(context)) {
            throw RuntimeException("缺少位置权限")
        }

        val scanResults = ApiUtil.getScanResults(context)

        logState.addLog("=== 扫描结果 ===")
        scanResults.forEach {
            logState.addLog(
                String.format(
                    "名称: %-16s 信号强度: %-8s 支持的协议: %s",
                    it.ssid,
                    it.level,
                    it.capabilities
                )
            )
        }
        logState.addLog("===============")

    } catch (e: Exception) {
        logState.addLog("E: 扫描wifi失败")
        logState.addLog(e.stackTraceToString())
    }
}

private fun checkAndPerformWifiScan(
    context: Context,
    logState: LogState,
    wifiScanLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    scope: CoroutineScope
) {
    if (ApiUtil.hasLocationPermission(context)) {
        if (!ApiUtil.isLocationEnabled(context)) {
            logState.addLog("系统定位服务未开启，请在设置中打开")
            ApiUtil.enableLocation(context)
            return
        }

        if (!ApiUtil.isWifiEnabled(context)) {
            logState.addLog("Wi-Fi未开启，请打开Wi-Fi")
            return
        }

        scope.launch {
            performWifiScan(context, logState)
        }
    } else {
        logState.addLog("请先允许定位权限")
        wifiScanLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}