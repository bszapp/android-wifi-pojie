package com.wifi.toolbox.ui.screen.test

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.tools.ShizukuTool
import com.wifi.toolbox.ui.items.*
import rikka.shizuku.*

private const val REQUEST_PERMISSION_CODE = 1001

@Composable
fun ShizukuTest(logState: LogState, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val logView by rememberUpdatedState(logState)

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ShizukuProvider.enableMultiProcessSupport(true)
        }
    }

    val requestPermissionResultListener = remember(logView) {
        Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code == REQUEST_PERMISSION_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                logView.addLog("权限申请结果：${if (granted) "同意" else "拒绝"}")
            }
        }
    }

    DisposableEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        onDispose {
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        }
    }

    fun checkShizukuStatus() {
        logView.addLog("--- 检查Shizuku状态 ---")

        try {
            val shizukuServiceRunning = Shizuku.pingBinder()
            logView.addLog("服务已启动: $shizukuServiceRunning")
        } catch (e: IllegalStateException) {
            logView.addLog("E: 检查服务状态失败: ${e.message}")
            return
        }


        try {
            val permissionGranted =
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            logView.addLog("已授权: $permissionGranted")

            if (permissionGranted) {
                try {
                    val uid = Shizuku.getUid()
                    logView.addLog("UID: $uid")
                } catch (_: IllegalStateException) {
                    logView.addLog("E: 获取UID失败")
                }
            }
        } catch (e: IllegalStateException) {
            logView.addLog("E: 检查权限失败: ${e.message}")
            return
        }
    }

    fun requestShizukuPermission() {
        logView.addLog("--- 申请权限 ---")
        try {
            if (Shizuku.isPreV11()) {
                logView.addLog("E: 不支持Shizuku pre-v11")
                return
            }
        } catch (e: IllegalStateException) {
            logView.addLog("E: 检查Shizuku版本失败: ${e.message}")
            return
        }


        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                logView.addLog("权限已经授予，无需重复申请")
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                logView.addLog("当前已被始终拒绝")
            } else {
                Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                logView.addLog("申请已发送")
            }
        } catch (e: IllegalStateException) {
            logView.addLog("E: 申请权限失败: ${e.message}")
            return
        }
    }

    LazyColumn{
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(title = "权限", icon = Icons.Default.Key)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = "检查状态",
                        icon = Icons.Filled.Search,
                        onClick = { checkShizukuStatus() })
                    ActionChip(
                        text = "申请权限",
                        icon = Icons.Filled.Security,
                        onClick = { requestShizukuPermission() })
                }

                SectionDivider()

                SectionTitle(title = "设备控制", icon = Icons.Default.Devices)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = "打开wifi",
                        icon = Icons.Filled.Wifi,
                        onClick = {
                            try {
                                ShizukuTool.setWifiEnabled(true)
                                logView.addLog("请求已发送")
                            } catch (e: Exception) {
                                logView.addLog("E: 打开wifi失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        })
                    ActionChip(
                        text = "关闭wifi",
                        icon = Icons.Filled.WifiOff,
                        onClick = {
                            try {
                                ShizukuTool.setWifiEnabled(false)
                                logView.addLog("请求已发送")
                            } catch (e: Exception) {
                                logView.addLog("E: 关闭wifi失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        })
                    ActionChip(
                        text = "锁屏",
                        icon = Icons.Filled.Lock,
                        onClick = {
                            try {
                                ShizukuTool.lookScreen()
                                logView.addLog("请求已发送")
                            } catch (e: Exception) {
                                logView.addLog("E: 锁屏失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        })
                    ActionChip(
                        text = "调整音量最大",
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        onClick = {
                            try {
                                ShizukuTool.setMediaVolumeMax()
                                logView.addLog("请求已发送")
                            } catch (e: Exception) {
                                logView.addLog("E: 调整音量失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        })
                }

                SectionDivider()

                SectionTitle(title = "连接wifi", icon = Icons.Default.InsertLink)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                            try {
                                val command = "cmd wifi connect-network $name wpa2 $password"
                                logView.addLog("执行Shell命令：${command}")
                                val result = ShizukuTool.executeCommandSync(command)
                                logView.addLog("请求已发送（响应：$result）")
                            } catch (e: Exception) {
                                logView.addLog("E: 执行Shell命令失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    ) {
                        Text("命令行（cmd wifi connect-network）")
                    }
                    Button(
                        onClick = {
                            try {
                                ShizukuTool.connectToWifi(name, password)
                                logView.addLog("请求已发送")
                            } catch (e: Exception) {
                                logView.addLog("E: 连接wifi失败")
                                logView.addLog(e.stackTraceToString())
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    ) {
                        Text("系统隐藏API（IWifiManager）")
                    }
                }
            }

        }
    }
}