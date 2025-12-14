package com.wifi.toolbox.ui.items

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.wifi.toolbox.structs.WifiInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import com.wifi.toolbox.structs.PojieRunInfo
import kotlinx.coroutines.Job


data class StartScanResult(
    val code: Int = CODE_UNKNOWN, val errorMessage: String? = null
) {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_SCAN_FAIL = -1
        const val CODE_WIFI_NOT_ENABLED = -2
        const val CODE_LOCATION_NOT_ENABLED = -3
        const val CODE_LOCATION_NOT_ALLOWED = -4
        const val CODE_UNKNOWN = -5
    }
}


data class ScanResult(
    val code: Int = CODE_UNKNOWN,
    val errorMessage: String? = null,
    var wifiList: List<WifiInfo>? = null
) {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_SCAN_FAIL = -1
        const val CODE_NOT_SCANNED = -2
        const val CODE_UNKNOWN = -2
    }
}

sealed class ScreenState {
    object Idle : ScreenState()
    object Success : ScreenState()
    data class Error(val message: String, val type: Int) : ScreenState()
    companion object {
        const val ERROR_WIFI_NOT_ENABLED = 1
        const val ERROR_SCAN_FAIL = 2
        const val ERROR_UNKNOWN = 3
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunListView(
    scanWifi: (() -> StartScanResult) = { StartScanResult() },
    getScanResult: (() -> ScanResult) = { ScanResult() },

    enableWifi: (() -> Unit) = {},
    applyLocation: (() -> Unit) = {},
    enableLocation: (() -> Unit) = {},

    runningTasks: List<PojieRunInfo> = emptyList(),
    onStartClick: (String) -> Unit = {},
    onStopClick: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var refreshJob by remember { mutableStateOf<Job?>(null) }

    var uiState by remember { mutableStateOf<ScreenState>(ScreenState.Idle) }
    var trigger by remember { mutableIntStateOf(0) }

    var showScanResult by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()


    fun reload() {
        coroutineScope.launch {

            refreshJob?.cancel()
            refreshJob = coroutineScope.launch {
                val start = scanWifi()
                when (start.code) {
                    StartScanResult.CODE_SUCCESS -> {
                        uiState = ScreenState.Success


                        showScanResult = false
                        repeat(500 / 500) {
                            trigger += 1
                            delay(500)
                        }
                        showScanResult = true
                        repeat((3000 - 500) / 500) {
                            trigger += 1
                            delay(500)
                        }
                        launch { listState.animateScrollToItem(0) }
                        refreshJob = null
                    }

                    StartScanResult.CODE_SCAN_FAIL -> {
                        uiState = ScreenState.Error(
                            message = start.errorMessage ?: "扫描失败",
                            type = ScreenState.ERROR_SCAN_FAIL
                        )
                    }

                    StartScanResult.CODE_WIFI_NOT_ENABLED -> {
                        uiState = ScreenState.Error(
                            message = "wifi未开启", type = ScreenState.ERROR_WIFI_NOT_ENABLED
                        )
                    }

                    else -> {
                        uiState = ScreenState.Error(
                            message = "未知错误(${start.errorMessage})",
                            type = ScreenState.ERROR_UNKNOWN
                        )
                    }
                }
            }
        }

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
                    onClick = {
                        reload()
                    }, colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Icon(
                        Icons.Rounded.Autorenew,
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("刷新")
                }
            },
            trailingButton = {
                val description = "更多操作"
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        TooltipAnchorPosition.Above
                    ),
                    tooltip = { PlainTooltip { Text(description) } },
                    state = rememberTooltipState(),
                ) {
                    SplitButtonDefaults.TrailingButton(
                        checked = expanded,
                        onCheckedChange = { expanded = it },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                    ) {
                        val rotation: Float by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            modifier = Modifier
                                .size(SplitButtonDefaults.TrailingIconSize)
                                .graphicsLayer {
                                    this.rotationZ = rotation
                                },
                            contentDescription = "更多操作",
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("前面的区域，以后再来探索吧") },
                        onClick = { expanded = false },
                        leadingIcon = {})
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        val res = getScanResult()
        when (res.code) {
            ScanResult.CODE_NOT_SCANNED -> reload()
            ScanResult.CODE_SUCCESS -> uiState = ScreenState.Success
            ScanResult.CODE_SCAN_FAIL -> uiState =
                ScreenState.Error(res.errorMessage ?: "扫描失败", ScreenState.ERROR_SCAN_FAIL)

            else -> uiState = ScreenState.Error("未知错误", ScreenState.ERROR_UNKNOWN)
        }
    }

    LaunchedEffect(runningTasks.size) {
        listState.animateScrollToItem(0)
    }

    when (val s = uiState) {
        is ScreenState.Idle -> {}

        is ScreenState.Success -> {
            val res = remember(trigger) { if (showScanResult) getScanResult() else ScanResult() }
            val scannedList = res.wifiList ?: emptyList()

            val fullList = remember(scannedList, runningTasks.size) {
                val scannedSsids = scannedList.map { it.ssid }.toSet()
                val missingRunningItems = runningTasks.filter { it.ssid !in scannedSsids }
                    .map { WifiInfo(it.ssid, 0, "") }

                (scannedList + missingRunningItems).sortedBy { wifi ->
                    val index = runningTasks.indexOfFirst { it.ssid == wifi.ssid }
                    if (index != -1) index else Int.MAX_VALUE
                }
            }
            val isLoading = refreshJob?.isActive == true
            val isEmpty = fullList.isEmpty()
            val state = if (isLoading && isEmpty) 0
            else if (!isLoading && isEmpty) 1
            else 2
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(
                        animationSpec = tween(
                            300
                        )
                    )
                },
                label = ""
            ) { state ->
                when (state) {
                    0 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            ContainedLoadingIndicator(
                                modifier = Modifier.size(60.dp),
                                indicatorColor = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    1 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    0.dp,
                                    Alignment.CenterVertically
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Inbox,
                                    contentDescription = null,
                                    modifier = Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(
                                    text = "空列表",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        }
                    }

                    2 -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            AnimatedVisibility(
                                visible = isLoading,
                                enter = expandVertically(),
                                exit = shrinkVertically()
                            ) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            LazyColumn(
                                state = listState, modifier = Modifier.fillMaxSize()
                            ) {
                                items(fullList, key = { it.ssid }) { wifi ->
                                    val runningInfo = runningTasks.find { it.ssid == wifi.ssid }

                                    wifiPojieItem(
                                        modifier = Modifier.animateItem(),
                                        wifi = wifi,
                                        runningInfo = runningInfo,
                                        onStartClick = onStartClick,
                                        onStopClick = onStopClick
                                    )

                                }
                                item {
                                    BannerTip(
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
        }

        is ScreenState.Error -> {
            when (s.type) {
                ScreenState.ERROR_WIFI_NOT_ENABLED -> ErrorTip(
                    Icons.Rounded.WifiOff, message = s.message, button = {
                        Button(onClick = {
                            enableWifi()
                            reload()
                        }) {
                            Text(text = "开启wifi")
                        }
                    })

                ScreenState.ERROR_SCAN_FAIL -> ErrorTip(
                    Icons.Rounded.ErrorOutline, message = s.message, button = {
                        Button(onClick = {
                            reload()
                        }) {
                            Text(text = "重试")
                        }
                    }
                )

                ScreenState.ERROR_UNKNOWN -> ErrorTip(
                    Icons.Rounded.BugReport, message = s.message, button = {
                        Button(onClick = {
                            reload()
                        }) {
                            Text(text = "重试")
                        }
                    }
                )

            }
        }
    }
}

@Composable
fun ErrorTip(
    icon: ImageVector,
    message: String,
    button: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterVertically)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.outlineVariant
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (button != null) {
            Spacer(modifier = Modifier.height(4.dp))
            button.invoke()
        }
    }
}