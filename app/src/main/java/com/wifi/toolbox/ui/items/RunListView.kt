package com.wifi.toolbox.ui.items

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.utils.ApiUtil
import com.wifi.toolbox.utils.PojieWifiController

data class StartScanResult(
    val code: Int = CODE_UNKNOWN,
    val errorMessage: String? = null
) {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_SCAN_FAIL = -1
        const val CODE_WIFI_NOT_ENABLED = -2
        const val CODE_LOCATION_NOT_ENABLED = -3
        const val CODE_LOCATION_NOT_ALLOWED = -4
        const val CODE_SEND_FAIL = -5
        const val CODE_UNKNOWN = -6
    }
}

data class ScanResult(
    val code: Int = CODE_UNKNOWN,
    val errorMessage: String? = null,
    var wifiList: List<WifiInfo>? = null
) {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_UNKNOWN = -1
    }
}

sealed class ScreenState {
    object Idle : ScreenState()
    data class Success(val sendSucceed: Boolean) : ScreenState()
    data class Error(val message: String, val type: Int) : ScreenState()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunListView(
    controller: PojieWifiController,
    onStartClick: (String) -> Unit = {},
    onStopClick: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val runningTasks = controller.runningTasks

    LaunchedEffect(Unit) {
        if (controller.uiState is ScreenState.Idle) controller.reload()
    }

    LaunchedEffect(controller.isScanning) {
        if (!controller.isScanning) listState.animateScrollToItem(0)
    }

    LaunchedEffect(runningTasks.size) {
        if (runningTasks.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "wifi列表",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )

        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = { controller.reload() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Rounded.Autorenew,
                        contentDescription = null,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("刷新")
                }
            },
            trailingButton = {
                SplitButtonDefaults.TrailingButton(
                    checked = expanded,
                    onCheckedChange = { expanded = it },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                ) {
                    val rotation by animateFloatAsState(if (expanded) 180f else 0f)
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .graphicsLayer { rotationZ = rotation }
                    )
                }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("前面的区域，以后再来探索吧") },
                        onClick = { expanded = false })
                }
            }
        )
    }

    AnimatedContent(controller.uiState, label = "ListContent") { s ->
        when (s) {
            is ScreenState.Success -> {
                val res = remember(controller.trigger) { controller.fetchResults() }
                val scannedList = res.wifiList ?: emptyList()

                val fullList = remember(scannedList, runningTasks.toList(), controller.trigger) {
                    val runningSsids = runningTasks.map { it.ssid }.toSet()

                    val partRunning = runningTasks.map { task ->
                        val realTimeInfo = scannedList.find { it.ssid == task.ssid }
                        WifiInfo(
                            ssid = task.ssid,
                            level = realTimeInfo?.level ?: 0,
                            capabilities = realTimeInfo?.capabilities ?: ""
                        )
                    }

                    val partScanned = scannedList
                        .filter { it.ssid !in runningSsids }
                        .sortedByDescending { it.level }

                    partRunning + partScanned
                }

                val state = if (controller.isScanning && fullList.isEmpty()) 0
                else if (!controller.isScanning && fullList.isEmpty()) 1
                else 2

                AnimatedContent(state, label = "ListContent") { targetState ->
                    when (targetState) {
                        0 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ContainedLoadingIndicator(Modifier.size(60.dp))
                        }

                        1 -> Column(Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Rounded.Inbox,
                                        null,
                                        Modifier.size(96.dp),
                                        tint = MaterialTheme.colorScheme.outlineVariant
                                    )
                                    Text("空列表", style = MaterialTheme.typography.bodyLarge)
                                }
                            }

                            BannerTip(
                                title = "没有找到想要的wifi？",
                                text = "点击手动输入名称",
                                trailingIcon = true,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                        }

                        2 -> Column(Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = controller.isScanning,
                                enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                                exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                item {
                                    if (ApiUtil.isWifiConnected(LocalContext.current)) {
                                        BannerTip(
                                            title = "当前已连接wifi",
                                            text = "可能对扫描及运行造成干扰，点击断开",
                                            trailingIcon = true,
                                            modifier = Modifier.padding(4.dp)
                                        )
                                    }
                                }
                                item {
                                    val state = controller.uiState
                                    if (state is ScreenState.Success && !state.sendSucceed)
                                        BannerTip(
                                            text = "扫描请求发送失败，当前查看的是旧数据",
                                            modifier = Modifier.padding(4.dp)
                                        )
                                }
                                items(fullList, key = { it.ssid }) { wifi ->
                                    WifiPojieItem(
                                        modifier = Modifier.animateItem(),
                                        wifi = wifi,
                                        runningInfo = runningTasks.find { it.ssid == wifi.ssid },
                                        onStartClick = onStartClick,
                                        onStopClick = onStopClick
                                    )
                                }
                                item {
                                    if (!controller.isScanning || !res.wifiList.isNullOrEmpty()) BannerTip(
                                        title = "没有找到想要的wifi？",
                                        text = "点击手动输入名称",
                                        trailingIcon = true,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is ScreenState.Error -> {
                val icon = when (s.type) {
                    StartScanResult.CODE_WIFI_NOT_ENABLED -> Icons.Rounded.WifiOff
                    StartScanResult.CODE_SCAN_FAIL -> Icons.Rounded.ErrorOutline
                    StartScanResult.CODE_LOCATION_NOT_ENABLED -> Icons.Rounded.LocationOff
                    StartScanResult.CODE_LOCATION_NOT_ALLOWED -> Icons.Rounded.WrongLocation

                    else -> Icons.Rounded.BugReport
                }
                ErrorTip(icon, s.message) {
                    when (s.type) {
                        StartScanResult.CODE_WIFI_NOT_ENABLED -> {
                            Button(onClick = {
                                controller.toggleWifiOn()
                            }) {
                                Text("开启wifi")
                            }
                        }

                        StartScanResult.CODE_LOCATION_NOT_ENABLED -> {
                            Button(onClick = {
                                controller.enableLocation()
                            }) {
                                Text("开启定位")
                            }
                        }

                        StartScanResult.CODE_LOCATION_NOT_ALLOWED -> {
                            Button(onClick = {
                                controller.applyLocation()
                            }) {
                                Text("申请权限")
                            }
                        }

                        else -> {
                            Button(onClick = {
                                controller.reload()
                            }) {
                                Text("重试")
                            }
                        }
                    }

                }
            }

            else -> {}
        }
    }
}

@Composable
fun ErrorTip(icon: ImageVector, message: String, button: @Composable (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            message, style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (button != null) {
            Spacer(Modifier.height(8.dp))
            button()
        }
    }
}