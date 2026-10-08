package io.github.bszapp.wifitoolbox.uidefault.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.wifilist.*
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.component.BlockingLoadingDialog
import io.github.bszapp.wifitoolbox.uidefault.model.CaptureViewModel
import io.github.bszapp.wifitoolbox.uidefault.model.CaptureDetailViewModel
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatMonitorByteCount
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatHandshakeStartTime
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorCaptureClearSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.HandshakeDetailsCard
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorDeviceDetailSheet
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.MonitorHandshakeAction
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.handshakeStatusText
import io.github.bszapp.wifitoolbox.uidefault.widget.wifilist.formatHandshakeDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun CaptureScreen(initialBssid: String = "", initialDeviceMac: String = "", model: CaptureViewModel = viewModel()) {
    CaptureListContent(initialBssid, initialDeviceMac, model)
}

@Composable
private fun CaptureListContent(initialBssid: String, initialDeviceMac: String, model: CaptureViewModel) {
    val navigator = LocalNavigator.current
    val display by model.state.collectAsStateWithLifecycle()
    val filters by model.filters.collectAsStateWithLifecycle()
    val mode by model.modeState.collectAsStateWithLifecycle()
    val opening by model.opening.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTop by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0 } }
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val colors = MiuixTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var keyword by rememberSaveable { mutableStateOf(filters.keyword) }
    var search by rememberSaveable { mutableStateOf(filters.keyword.isNotEmpty()) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var showFilter by rememberSaveable { mutableStateOf(false) }
    var selector by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(model, initialBssid, initialDeviceMac) { model.open(initialBssid, initialDeviceMac) }
    LaunchedEffect(opening?.snapshotId) {
        model.takePreparedDetail()?.let { navigator.push(Route.CaptureDetail(it)) }
    }
    LaunchedEffect(keyword) { delay(300); model.updateFilters(model.filters.value.copy(keyword = keyword)) }
    LaunchedEffect(list, model) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.filter { it.contentType == "record" }
                .let { (it.firstOrNull()?.index ?: 0) to (it.lastOrNull()?.index ?: 32) }
        }.distinctUntilChanged().collect { (first, last) -> model.visibleWindow(first, last) }
    }
    Scaffold(topBar = {
        BlurredBar(backdrop) {
            Column(Modifier.background(if (backdrop == null) colors.surface else Color.Transparent)) {
            SmallTopAppBar(title = "抓包解析", subtitle = display.count.toString() + " 项",
                color = if (backdrop != null) Color.Transparent else colors.surface,
                navigationIcon = { CaptureBackButton { navigator.pop() } }, scrollBehavior = scrollBehavior,
                actions = {
                    CaptureAction(Icons.Rounded.Search, "搜索") {
                        search = !search
                        searchExpanded = search
                        if (!search) { keyword = ""; focusManager.clearFocus() }
                    }
                    CaptureAction(Icons.Rounded.FilterList, "筛选") { selector = ""; showFilter = true }
                    CaptureAction(Icons.Rounded.DeleteSweep, "仅清空非握手数据（保留 DHCP 设备名协商）") { model.clearNonHandshakeData() }
                })
            if (search) SearchBar(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                inputField = {
                    InputField(query = keyword, onQueryChange = { keyword = it },
                        onSearch = {
                            model.updateFilters(model.filters.value.copy(keyword = it))
                            focusManager.clearFocus()
                        },
                        expanded = searchExpanded, onExpandedChange = { searchExpanded = it }, label = "搜索域名、URL、MAC、名称及捕获内容")
                }, expanded = searchExpanded, onExpandedChange = { searchExpanded = it },
            ) {}
            val active = listOfNotNull(filters.accessPoint.takeIf(String::isNotBlank), filters.device.takeIf(String::isNotBlank)) +
                filters.protocols + filters.contentKinds + filters.statusGroups.map { it.toString() + "xx" }
            if (active.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(active.joinToString(" · ") { captureProtocolLabel(it) }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2, color = colors.primary)
                CaptureAction(Icons.Rounded.FilterAltOff, "清除筛选") { model.updateFilters(CaptureViewModel.Filters(keyword = keyword)) }
            }
            }
        }
    }, contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)) { padding ->
        Box((if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).fillMaxSize()) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection), contentPadding = padding, overscrollEffect = null) {
                items(display.count, key = { display.rows[it]?.let { record -> "record:" + record.id } ?: "pending:$it" }, contentType = { "record" }) { index ->
                    val record = display.rows[index]
                    if (record == null) Text("加载中", modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp).padding(16.dp))
                    else CaptureRecordRow(record, record.id in display.viewed) { model.prepareDetail(record) }
                }
                item(key = "bottom-insets") { CaptureBottomInset() }
            }
            display.error?.let { Text(it, color = colors.error, modifier = Modifier.align(Alignment.TopCenter)
                .padding(top = padding.calculateTopPadding()).padding(16.dp), style = MiuixTheme.textStyles.body2) }
            if (display.count == 0 && display.error == null) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ManageSearch, null, Modifier.size(64.dp), tint = colors.onSurfaceVariantSummary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (search || filters != CaptureViewModel.Filters()) "无结果" else "暂未捕获到通信或可校验的握手数据", color = colors.onSurfaceVariantSummary)
                }
            }
            AnimatedVisibility(visible = showTop,
                enter = slideInVertically { it * 2 } + fadeIn(), exit = slideOutVertically { it * 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp,
                    bottom = 16.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding())) {
                FloatingActionButton(onClick = { scope.launch { list.animateScrollToItem(0) } }, containerColor = colors.surfaceContainer) {
                    Icon(Icons.Rounded.VerticalAlignTop, "回到顶部", tint = colors.onSurface)
                }
            }
        }
    }
    CaptureFilterSheet(showFilter, selector, filters, mode?.monitorStatistics?.accessPoints.orEmpty(), display.available,
        onSelector = { selector = it }, onChange = model::updateFilters, onDismiss = { showFilter = false })
    BlockingLoadingDialog(visible = opening != null && opening?.snapshotId == null,
        operationId = opening?.operationId ?: 0L, text = "正在获取完整详情",
        interruptionWarning = "取消本次详情读取，录制继续运行。", onInterrupt = { model.cancelOpening() }) {
        val value = opening
        if (value != null && value.total > 0) {
            Text(formatMonitorByteCount(value.bytes) + " / " + formatMonitorByteCount(value.total), style = MiuixTheme.textStyles.footnote1)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = (value.bytes.toDouble() / value.total).toFloat())
        }
    }
    MonitorCaptureClearSheet(mode?.captureClearProgress, model::interruptClear)
}

@Composable
private fun CaptureRecordRow(record: MonitorCommunicationRecord, viewed: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val time = remember(record.timestampUnixMillis) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(record.timestampUnixMillis)) }
    Column(Modifier.fillMaxWidth().alpha(if (viewed) .5f else 1f).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (record.handshake != null) Icon(Icons.Rounded.WifiLock, null, Modifier.size(36.dp), tint = colors.primary)
            else CaptureFavicon(record.domain)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.deviceName.ifBlank { record.deviceMac }, modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(time, style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
                Text("#" + record.id + " · " + record.domain.ifBlank { record.destination }, style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(record.handshake?.let { "WPA/WPA2握手 · ${record.ssid} · ${handshakeStatusText(it)}" }
                    ?: listOf(record.method.ifBlank { record.type.name }, record.url.ifBlank { record.summary }).joinToString(" "),
                    style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val status = when {
                        record.handshake != null -> "WPA/WPA2握手"
                        record.type == MonitorCommunicationType.HTTP && record.statusCode > 0 -> record.statusCode.toString()
                        record.type == MonitorCommunicationType.HTTP -> "未捕获响应"
                        record.type == MonitorCommunicationType.HTTPS -> record.protocol.ifBlank { "TLS" }
                        else -> record.type.name
                    }
                    Text(status, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2,
                        color = if (record.statusCode >= 400) colors.error else colors.primary)
                    Text(record.handshake?.let { "${it.exportPacketCount} 包" + when {
                        it.status == MonitorHandshakeStatus.IN_PROGRESS -> " · 结束后计算用时"
                        it.durationMillis != null -> " · ${formatHandshakeDuration(it.durationMillis)}"
                        else -> " · 未知状态"
                    } }
                        ?: ("↑ " + formatMonitorByteCount(record.uploadBytes) + "  ↓ " + formatMonitorByteCount(record.downloadBytes)),
                        style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }
        }
    }
    CaptureDivider()
}

@Composable
private fun CaptureFilterSheet(show: Boolean, selector: String, filter: CaptureViewModel.Filters, points: List<MonitorAccessPoint>,
    available: MonitorCommunicationAvailability,
    onSelector: (String) -> Unit, onChange: (CaptureViewModel.Filters) -> Unit, onDismiss: () -> Unit) {
    val selectableDevices = points.filter { filter.accessPoint.isBlank() || it.bssid.contains(filter.accessPoint, true) || it.ssid?.contains(filter.accessPoint, true) == true }
        .flatMap { point -> point.devices.filter { "${point.bssid}/${it.mac}" in available.devicePairs }.map { it.mac } }.toSet()
    SingleOverlayBottomSheet(show = show, title = when (selector) { "ap" -> "接入点"; "device" -> "设备"; else -> "筛选" },
        onDismissRequest = onDismiss, startAction = if (selector.isEmpty()) null else ({ CaptureBackButton { onSelector("") } })) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp),
            contentPadding = PaddingValues(bottom = 12.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding())) {
            if (selector.isEmpty()) {
                item {
                    CaptureFilterItem("接入点", filter.accessPoint.ifBlank { "全部接入点" }, available.accessPoints.isNotEmpty()) { onSelector("ap") }
                    CaptureFilterItem("设备", filter.device.ifBlank { "全部设备" }, selectableDevices.isNotEmpty()) { onSelector("device") }
                    CaptureOptionGroup("协议", MonitorCommunicationType.entries.map { it.name }, filter.protocols, available.protocols,
                        label = ::captureProtocolLabel) {
                        onChange(filter.copy(protocols = it))
                    }
                    CaptureOptionGroup("内容类型", listOf("JSON", "XML", "HTML", "JS", "文本", "图片", "媒体", "二进制"), filter.contentKinds, available.contentKinds) {
                        onChange(filter.copy(contentKinds = it))
                    }
                    CaptureOptionGroup("状态码", listOf("1xx", "2xx", "3xx", "4xx", "5xx"), filter.statusGroups.map { it.toString() + "xx" }.toSet(),
                        available.statusGroups.map { it.toString() + "xx" }.toSet()) {
                        onChange(filter.copy(statusGroups = it.map { code -> code.first().digitToInt() }.toSet()))
                    }
                    TextButton("重置筛选", onClick = { onChange(CaptureViewModel.Filters(keyword = filter.keyword)) }, modifier = Modifier.fillMaxWidth())
                    TextButton("完成", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
            } else if (selector == "ap") {
                item {
                    TextField(value = filter.accessPoint, onValueChange = { onChange(filter.copy(accessPoint = it)) }, singleLine = true,
                        label = "接入点 MAC 或名称", modifier = Modifier.fillMaxWidth())
                    CaptureFilterItem("全部接入点", null, available.accessPoints.isNotEmpty()) { onChange(filter.copy(accessPoint = "")); onSelector("") }
                }
                items(points.sortedByDescending { it.bssid in available.accessPoints }, key = { it.bssid }) { point ->
                    CaptureFilterItem(point.ssid ?: "<未知网络>", point.bssid, point.bssid in available.accessPoints) {
                        onChange(filter.copy(accessPoint = point.bssid)); onSelector("")
                    }
                }
            } else {
                item {
                    TextField(value = filter.device, onValueChange = { onChange(filter.copy(device = it)) }, singleLine = true,
                        label = "设备 MAC 或名称", modifier = Modifier.fillMaxWidth())
                    CaptureFilterItem("全部设备", null, selectableDevices.isNotEmpty()) { onChange(filter.copy(device = "")); onSelector("") }
                }
                val devices = points.filter { filter.accessPoint.isBlank() || it.bssid.contains(filter.accessPoint, true) || it.ssid?.contains(filter.accessPoint, true) == true }
                    .flatMap { it.devices }.distinctBy { it.mac }.sortedByDescending { it.mac in selectableDevices }
                items(devices, key = { it.mac }) { device ->
                    CaptureFilterItem(device.name ?: device.mac, device.mac.takeIf { device.name != null }, device.mac in selectableDevices) {
                        onChange(filter.copy(device = device.mac)); onSelector("")
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureOptionGroup(title: String, values: List<String>, selected: Set<String>, available: Set<String>,
    label: (String) -> String = { it }, onChange: (Set<String>) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                val checked = value in selected
                val enabled = value in available
                Box(Modifier.alpha(if (enabled) 1f else .38f).border(1.dp, if (checked) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = .4f), RoundedCornerShape(7.dp))
                    .background(if (checked) colors.primary.copy(alpha = .12f) else Color.Transparent, RoundedCornerShape(7.dp))
                    .clickable(enabled = enabled) { onChange(if (checked) selected - value else selected + value) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(label(value), color = if (checked) colors.primary else colors.onSurface, style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}

private fun captureProtocolLabel(value: String) = if (value == MonitorCommunicationType.WPA_HANDSHAKE.name) "WPA/WPA2握手" else value
@Composable
private fun CaptureFilterItem(title: String, summary: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .38f).clickable(enabled = enabled, onClick = onClick).padding(16.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1)
        if (summary != null) Text(summary, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}


@Composable
fun CaptureDetailScreen(route: Route.CaptureDetail, model: CaptureDetailViewModel = viewModel()) {
    CaptureDetailContent(route, model)
}

@Composable
private fun CaptureDetailContent(route: Route.CaptureDetail, model: CaptureDetailViewModel) {
    val navigator = LocalNavigator.current
    val display by model.state.collectAsStateWithLifecycle()
    val channel by model.channel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val list = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val colors = MiuixTheme.colorScheme
    val record = display.record
    var response by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var hex by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var showExports by rememberSaveable { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { model.export(it) }
    fun export(value: MonitorCommunicationChannel) {
        model.prepareExport(value)
        saver.launch((record?.type?.name?.lowercase() ?: "capture") + "_" + (record?.timestampUnixMillis ?: 0L) +
            "_" + (record?.id ?: route.snapshotId) + "_" + value.name.lowercase() + ".bin")
    }
    LaunchedEffect(model, route) { model.open(route.snapshotId) }
    val http = record?.type == MonitorCommunicationType.HTTP
    val handshake = record?.handshake
    val tcp = record?.transport == MonitorCommunicationTransport.TCP
    val hexAllowed = http || record?.type in listOf(MonitorCommunicationType.DNS, MonitorCommunicationType.TCP)
    val tabs = if (handshake != null) listOf("总览") else if (http) listOf("总览", "原始",
        (if (response) "响应头(" + record?.responseHeaderCount else "请求头(" + record?.requestHeaderCount) + ")",
        if (response) "响应体" else "请求体") else listOf("总览", "原始")
    val selected = tab.coerceIn(0, tabs.lastIndex)
    val wanted = when {
        http && selected == 2 -> if (response) MonitorCommunicationChannel.RESPONSE_HEADERS else MonitorCommunicationChannel.REQUEST_HEADERS
        http && selected == 3 && hex -> if (response) MonitorCommunicationChannel.RAW_RESPONSE_BODY else MonitorCommunicationChannel.RAW_REQUEST_BODY
        http && selected == 3 -> if (response) MonitorCommunicationChannel.RESPONSE_BODY else MonitorCommunicationChannel.REQUEST_BODY
        http && hex -> if (response) MonitorCommunicationChannel.RAW_RESPONSE else MonitorCommunicationChannel.RAW_REQUEST
        http -> if (response) MonitorCommunicationChannel.RESPONSE else MonitorCommunicationChannel.REQUEST
        record?.type == MonitorCommunicationType.DNS && hex -> MonitorCommunicationChannel.RAW_DNS
        record?.type == MonitorCommunicationType.TCP -> if (response) MonitorCommunicationChannel.RAW_DOWNLOAD else MonitorCommunicationChannel.RAW_UPLOAD
        else -> MonitorCommunicationChannel.DETAIL
    }
    LaunchedEffect(wanted) { model.selectChannel(wanted) }
    LaunchedEffect(channel, selected, hex) { list.scrollToItem(0) }
    LaunchedEffect(list, model) {
        snapshotFlow {
            list.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.removePrefix("body:")?.toIntOrNull() }
                .let { (it.firstOrNull() ?: 0) to (it.lastOrNull() ?: 0) }
        }.distinctUntilChanged().collect { (first, last) -> model.visibleWindow(first, last) }
    }
    Scaffold(topBar = {
        BlurredBar(backdrop) {
            SmallTopAppBar(title = if (handshake != null) "WPA/WPA2握手详情" else if (http) "请求 & 响应" else (record?.type?.name ?: "通信") + "详情", color = if (backdrop != null) Color.Transparent else colors.surface,
                navigationIcon = { CaptureBackButton { navigator.pop() } }, scrollBehavior = scrollBehavior,
                actions = {
                    CaptureAction(Icons.Rounded.Share, "分享记录信息") {
                        record?.let { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT,
                                it.summary + "\n" + it.url + "\n" + it.source + " → " + it.destination + "\n" + it.bssid + " / " + it.deviceMac)
                        }, "分享记录信息")) }
                    }
                    if (handshake == null) CaptureAction(Icons.Rounded.FileDownload, "导出原始数据") { showExports = true }
                })
        }
    }, bottomBar = {
        Column(Modifier.background(colors.surface)) {
            CaptureDivider()
            if (http || record?.type == MonitorCommunicationType.TCP) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                CaptureDirection(if (http) "↑ 请求" else "↑ 上行", !response, Modifier.weight(1f)) { response = false }
                CaptureDirection(if (http) "↓ 响应" else "↓ 下行", response, Modifier.weight(1f)) { response = true }
            }
            Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }, contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)) { padding ->
        Column((if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier).fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CaptureTabs(tabs, selected, { tab = it }, Modifier.weight(1f))
                if (record != null) CaptureBadge(if (response && record.statusCode > 0) record.statusCode.toString()
                    else record.method.ifBlank { record.protocol.ifBlank { record.type.name } })
            }
            if (selected == 0) {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().scrollEndHaptic().overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection), overscrollEffect = null) {
                    item {
                        if (record != null && handshake != null) CaptureHandshakeContent(record, model)
                        else CaptureOverview(record, response)
                    }
                    item { CaptureBottomInset() }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CaptureTextMode("Text", !hex || !hexAllowed || selected == 2) { hex = false }
                    if (hexAllowed && selected != 2) CaptureTextMode("Hex", hex) { hex = true }
                    Spacer(Modifier.weight(1f))
                    CaptureAction(Icons.Rounded.Search, "搜索当前已加载内容") { search = !search }
                    CaptureAction(Icons.Rounded.WrapText, if (wrap) "关闭换行" else "启用换行", selected = wrap) { wrap = !wrap }
                    CaptureAction(Icons.Rounded.ContentCopy, "复制当前已加载内容") {
                        val text = display.chunks.entries.sortedBy { it.key }.joinToString("\n") { (page, bytes) ->
                            if (hex && hexAllowed && selected != 2) captureHex(bytes, page * 65536L) else bytes.toString(Charsets.UTF_8)
                        }
                        captureCopy(context, text, "已复制当前加载内容")
                    }
                    CaptureAction(Icons.Rounded.FileDownload, "导出此部分") { export(channel) }
                }
                if (search) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = keyword, onValueChange = { keyword = it }, singleLine = true, label = "在当前加载区域查找", modifier = Modifier.weight(1f))
                    CaptureAction(Icons.Rounded.Close, "关闭查找") { search = false; keyword = "" }
                }
                display.error?.let { Text(it, color = colors.error, modifier = Modifier.padding(12.dp), style = MiuixTheme.textStyles.body2) }
                if (display.exporting) Text("正在导出", modifier = Modifier.padding(horizontal = 16.dp), style = MiuixTheme.textStyles.footnote2)
                val horizontal = rememberScrollState()
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .border(1.dp, colors.onSurfaceVariantSummary.copy(alpha = .25f))) {
                    val viewportWidth = maxWidth
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection), overscrollEffect = null,
                        contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (display.totalBytes == 0L) item {
                            Text(if (response && http && record?.statusCode == 0) "未捕获响应" else "暂未捕获到此部分内容",
                                color = colors.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                        }
                        items(((display.totalBytes + 4095) / 4096).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), key = { "body:$it" }) { index ->
                            val isHex = hex && hexAllowed && selected != 2
                            val text = remember(display.chunks, index, display.totalBytes, isHex) {
                                if (isHex) captureHex(captureBytes(display.chunks, index * 4096L, minOf(4096L, display.totalBytes - index * 4096L).toInt()), index * 4096L)
                                else captureTextBlock(display.chunks, index, display.totalBytes)
                            }
                            val startLine = display.lines[index / 16]?.plus(
                                display.chunks[index / 16]?.take((index % 16) * 4096)?.count { it == 10.toByte() } ?: 0) ?: 1L
                            CaptureCodeBlock(text, startLine, isHex, wrap, if (search) keyword else "", horizontal, viewportWidth)
                        }
                    }
                }
            }
        }
    }
    SingleOverlayBottomSheet(show = showExports, title = "导出原始数据", onDismissRequest = { showExports = false }) {
        Column(Modifier.fillMaxWidth().padding(bottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding())) {
            if (record?.type == MonitorCommunicationType.DNS) BasicComponent(title = "DNS 报文（二进制）", onClick = {
                showExports = false; export(MonitorCommunicationChannel.RAW_DNS)
            })
            if (tcp && (record?.type != MonitorCommunicationType.HTTP || record.tcpStreamId.isNotEmpty())) {
                BasicComponent(title = "上行 TCP 流（二进制）", onClick = { showExports = false; export(MonitorCommunicationChannel.RAW_UPLOAD) })
                BasicComponent(title = "下行 TCP 流（二进制）", onClick = { showExports = false; export(MonitorCommunicationChannel.RAW_DOWNLOAD) })
            }
            if (http) {
                BasicComponent(title = "HTTP 请求（原始字节）", onClick = { showExports = false; export(MonitorCommunicationChannel.RAW_REQUEST) })
                BasicComponent(title = "HTTP 响应（原始字节）", onClick = { showExports = false; export(MonitorCommunicationChannel.RAW_RESPONSE) })
            }
            BasicComponent(title = "当前通道（完整内容）", onClick = { showExports = false; export(channel) })
        }
    }
}

@Composable
private fun CaptureHandshakeContent(record: MonitorCommunicationRecord, model: CaptureDetailViewModel) {
    val handshake = requireNotNull(record.handshake)
    val savedWifi by model.savedWifiList.collectAsStateWithLifecycle()
    val test by model.handshakeActions.state.collectAsStateWithLifecycle()
    val device = remember(record) { MonitorDevice(record.deviceMac, record.deviceName.takeIf(String::isNotBlank), handshakes = listOf(handshake)) }
    val point = remember(record, device) { MonitorAccessPoint(record.bssid, record.ssid.takeIf(String::isNotBlank), devices = listOf(device)) }
    var action by rememberSaveable { mutableStateOf<MonitorHandshakeAction?>(null) }
    var pcapPath by rememberSaveable { mutableStateOf<String?>(null) }
    var hc22000 by rememberSaveable { mutableStateOf<String?>(null) }
    val pcapSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap")) { uri ->
        pcapPath?.let { model.savePcap(it, uri) }; pcapPath = null
    }
    val textSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        hc22000?.let { model.saveHc22000(it, uri) }; hc22000 = null
    }
    LaunchedEffect(model) {
        model.pcapExports.collect { result ->
            if (model.ownsExport(result.requestId)) {
                pcapPath?.let { model.savePcap(it, null) }
                pcapPath = result.path
                pcapSaver.launch(result.fileName)
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("校验", modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { action = MonitorHandshakeAction.TEST })
            TextButton("导出 PCAP", modifier = Modifier.weight(1f), onClick = { action = MonitorHandshakeAction.EXPORT })
        }
        HandshakeDetailsCard(point, device, handshake)
    }
    MonitorDeviceDetailSheet(
        accessPoint = point, device = device, savedNetworks = savedWifi?.networks.orEmpty(), handshakeTest = test,
        onClearHandshakeTestResult = model.handshakeActions::clear, onDismiss = { action = null }, onExport = {},
        onTestHandshake = { id, password -> model.handshakeActions.test(record.bssid, record.deviceMac, id, password) },
        onExportHandshake = { model.exportHandshake(record) },
        onSaveHc22000 = { content, name -> hc22000 = content; textSaver.launch(name) },
        showDeviceDetails = false, renderSheet = false, initialHandshakeId = handshake.id,
        initialHandshakeAction = action, onInitialActionFinished = { action = null },
    )
}

@Composable
private fun CaptureOverview(record: MonitorCommunicationRecord?, response: Boolean) {
    if (record == null) { Text("加载中", modifier = Modifier.padding(16.dp)); return }
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (record.url.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("URL", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                CaptureAction(Icons.Rounded.ContentCopy, "复制 URL") { captureCopy(context, record.url) }
            }
            SelectionContainer { Text(record.url, color = colors.primary, style = MiuixTheme.textStyles.body2) }
            Spacer(Modifier.height(18.dp))
        }
        CaptureKeyValue("协议", record.protocol.ifBlank { record.type.name })
        if (record.method.isNotBlank()) CaptureKeyValue("方法", record.method)
        if (record.type == MonitorCommunicationType.HTTP) CaptureKeyValue("状态码",
            if (record.statusCode == 0) "未捕获响应" else record.statusCode.toString())
        if (record.contentType.isNotBlank()) CaptureKeyValue("内容类型", record.contentType)
        if (record.domain.isNotBlank()) CaptureKeyValue("域名", record.domain)
        CaptureKeyValue("数据大小", formatMonitorByteCount(if (response) record.downloadBytes else record.uploadBytes))
        Spacer(Modifier.height(20.dp))
        Text("连接", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        CaptureKeyValue("时间", formatHandshakeStartTime(record.timestampUnixMillis))
        CaptureKeyValue("来源地址", if (response) record.destination else record.source)
        CaptureKeyValue("目标地址", if (response) record.source else record.destination)
        CaptureKeyValue("网络", record.ssid.ifBlank { "<未知网络>" })
        CaptureKeyValue("接入点", record.bssid)
        if (record.deviceName.isNotBlank()) CaptureKeyValue("设备名称", record.deviceName)
        CaptureKeyValue("设备 MAC", record.deviceMac)
        CaptureKeyValue("上传", formatMonitorByteCount(record.uploadBytes))
        CaptureKeyValue("下载", formatMonitorByteCount(record.downloadBytes))
        if (record.type == MonitorCommunicationType.HTTPS) {
            Spacer(Modifier.height(16.dp))
            Text("HTTPS 正文保持加密，仅展示捕获到的域名和双向数据量。", style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun CaptureKeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, modifier = Modifier.width(100.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SelectionContainer(Modifier.weight(1f)) { Text(value, style = MiuixTheme.textStyles.body2) }
    }
    CaptureDivider()
}

@Composable
private fun CaptureTabs(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        tabs.forEachIndexed { index, label ->
            Column(Modifier.clickable { onSelected(index) }.padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MiuixTheme.textStyles.body2, color = if (selected == index) colors.onSurface else colors.onSurfaceVariantSummary,
                    fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.height(3.dp).width(28.dp).background(if (selected == index) colors.primary else Color.Transparent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun CaptureDirection(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun CaptureBadge(text: String) {
    Text(text, modifier = Modifier.padding(start = 8.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = .15f), RoundedCornerShape(16.dp))
        .padding(horizontal = 8.dp, vertical = 3.dp), color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote2)
}

@Composable
private fun CaptureTextMode(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, modifier = Modifier.background(if (selected) MiuixTheme.colorScheme.onSurface.copy(alpha = .09f) else Color.Transparent, RoundedCornerShape(3.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), style = MiuixTheme.textStyles.body2,
        color = if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun CaptureCodeBlock(text: String, startLine: Long, hex: Boolean, wrap: Boolean, keyword: String,
    horizontal: ScrollState, viewportWidth: androidx.compose.ui.unit.Dp) {
    val colors = MiuixTheme.colorScheme
    val style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace, color = colors.onSurface)
    val annotated = remember(text, keyword, hex, colors.primary) {
        buildAnnotatedString {
            append(text)
            if (!hex) Regex("(?m)^(?:GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS|CONNECT|TRACE|HTTP/\\d(?:\\.\\d)?)[^\\r\\n]*|^[\\w-]+(?=:)").findAll(text).forEach {
                addStyle(SpanStyle(color = colors.primary), it.range.first, it.range.last + 1)
            }
            if (keyword.isNotEmpty()) {
                var offset = 0
                while (offset < text.length) {
                    val found = text.indexOf(keyword, offset, ignoreCase = true)
                    if (found < 0) break
                    addStyle(SpanStyle(background = colors.primary.copy(alpha = .25f)), found, found + keyword.length)
                    offset = found + keyword.length
                }
            }
        }
    }
    var lineNumbers by remember(text, startLine) { mutableStateOf(startLine.toString()) }
    Row(Modifier.fillMaxWidth()) {
        if (!hex) Text(lineNumbers, modifier = Modifier.widthIn(min = 48.dp).padding(horizontal = 8.dp), textAlign = TextAlign.End,
            style = style, color = colors.onSurfaceVariantSummary)
        SelectionContainer(Modifier.weight(1f)) {
            BasicText(annotated, modifier = if (wrap && !hex) Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                else Modifier.horizontalScroll(horizontal).widthIn(min = (viewportWidth - 64.dp).coerceAtLeast(0.dp)).padding(horizontal = 8.dp),
                style = style, softWrap = wrap && !hex, onTextLayout = { layout ->
                    var logicalLine = startLine
                    var previous = 0
                    val numbers = (0 until layout.lineCount).joinToString("\n") { line ->
                        val offset = layout.getLineStart(line)
                        val newlines = text.substring(previous, offset).count { it == '\n' }
                        previous = offset
                        logicalLine += newlines
                        if (line == 0 || newlines > 0) logicalLine.toString() else ""
                    }
                    if (numbers != lineNumbers) lineNumbers = numbers
                })
        }
    }
}

@Composable
private fun CaptureAction(icon: ImageVector, description: String, selected: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, description, modifier = Modifier.size(22.dp), tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CaptureDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .12f)))
}

@Composable
private fun CaptureBackButton(onClick: () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    IconButton(onClick = onClick) {
        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.graphicsLayer { if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f })
    }
}

@Composable
private fun CaptureBottomInset() {
    Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp))
}

private fun captureCopy(context: Context, text: String, message: String = "已复制到剪贴板") {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("抓包数据", text))
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun captureBytes(chunks: Map<Int, ByteArray>, offset: Long, count: Int): ByteArray {
    val output = ByteArray(count.coerceAtLeast(0))
    for (index in output.indices) {
        val position = offset + index
        output[index] = chunks[(position / 65536).toInt()]?.getOrNull((position % 65536).toInt()) ?: return byteArrayOf()
    }
    return output
}

private fun captureHex(bytes: ByteArray, offset: Long): String = buildString {
    bytes.asList().chunked(16).forEachIndexed { index, row ->
        if (index > 0) append('\n')
        append("%08x  ".format(offset + index * 16L))
        append(row.joinToString(" ") { "%02x".format(it.toInt() and 255) }.padEnd(47))
        append("  ")
        row.forEach { append(if (it.toInt() in 32..126) it.toInt().toChar() else '.') }
    }
}

/** UTF-8 字符跨传输页时由前一文本块完整显示。 */
private fun captureTextBlock(chunks: Map<Int, ByteArray>, index: Int, total: Long): String {
    fun byteAt(offset: Long): Byte? {
        if (offset >= total) return null
        return chunks[(offset / 65536).toInt()]?.getOrNull((offset % 65536).toInt())
    }
    fun continuation(offset: Long) = byteAt(offset)?.let { it.toInt() and 0xc0 == 0x80 } == true
    var start = index * 4096L
    if (byteAt(start) == null) return "加载中"
    var end = minOf(total, start + 4096)
    repeat(3) { if (start < end && continuation(start)) start++ }
    repeat(3) { if (end < total && continuation(end)) end++ }
    return captureBytes(chunks, start, (end - start).toInt()).toString(Charsets.UTF_8)
}
