package io.github.bszapp.wifitoolbox.ui.hashcat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelState
import kotlinx.coroutines.delay
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.util.BlurredBar
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HashcatScreen(snackbarHostState: SnackbarHostState, viewModel: HashcatViewModel = viewModel(), initialHandshake: String? = null) {
    val tasks by viewModel.history.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val sheet by viewModel.sheet.collectAsStateWithLifecycle()
    val dictionaries by viewModel.dictionaries.collectAsStateWithLifecycle()
    val kernels by viewModel.kernels.collectAsStateWithLifecycle()
    LaunchedEffect(initialHandshake) { initialHandshake?.let(viewModel::openIncoming) }
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::readFile)
    }
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                Column {
                    TopAppBar(
                        color = barColor,
                        title = "WPA Hashcat",
                        scrollBehavior = scrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                val layoutDirection = LocalLayoutDirection.current
                                Icon(
                                    modifier = Modifier.graphicsLayer {
                                        if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                    },
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "返回",
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                        },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
        ) { padding ->
        Box(
            (if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .fillMaxSize()
        ) {
            val layoutDirection = LocalLayoutDirection.current
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + safeBottom + 92.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!connected) item { Text("服务未连接，历史记录仍可查看", style = MiuixTheme.textStyles.body2) }
                if (tasks.isEmpty()) item { Text("暂无 WPA 字典安全评估任务") }
                items(tasks, key = { it.id }) { task ->
                    Card(Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.inspect(task.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("WPA Hashcat · ${task.state.label()}", style = MiuixTheme.textStyles.subtitle)
                            Text(formatDate(task.createdAt), style = MiuixTheme.textStyles.body2)
                            TaskNetworks(task)
                            Text(task.dictionaryNames.joinToString("、"), style = MiuixTheme.textStyles.body2)
                            if (task.evaluationFinished()) Assessment(task) else Progress(task)
                            if (task.state == HashcatTaskState.SUCCEEDED) {
                                Text(
                                    "已成功发现密码，点击查看详情",
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
            FloatingActionButton(onClick = { viewModel.open() }, modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = safeBottom + 16.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "运行", tint = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
    SingleOverlayBottomSheet(
        show = sheet.show,
        title = when {
            sheet.page == HashcatSheetPage.DETAIL -> "WPA Hashcat任务"
            sheet.page == HashcatSheetPage.DICTIONARIES && sheet.taskId != null -> "重新运行WPA Hashcat"
            else -> "运行WPA Hashcat"
        },
        onDismissRequest = viewModel::dismiss,
    ) {
        AnimatedContent(
            targetState = sheet.page,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = sheetHeight),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                val movingForward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(260)) { width -> if (movingForward) width else -width } togetherWith
                    slideOutHorizontally(tween(260)) { width -> if (movingForward) -width else width })
                    .using(SizeTransform(clip = true) { _, _ -> tween(260) })
            },
            label = "HashcatSheetPage",
        ) { page ->
        Column(Modifier
            .fillMaxWidth()
            .heightIn(max = sheetHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    HashcatSheetPage.PREPARATION -> {
                        if (kernels?.state == HashcatKernelState.COMPILING) item { KernelProgress(kernels!!) }
                        else {
                            item { Text(if (sheet.busy) "加载中" else "内核暂未编译", style = MiuixTheme.textStyles.subtitle) }
                            kernels?.error?.let { error -> item { Text(error, style = MiuixTheme.textStyles.body2) } }
                        }
                    }
                    HashcatSheetPage.INPUT -> {
                        item { Text("选择 hc22000 文件，或直接粘贴握手包的 hc22000 文本。", style = MiuixTheme.textStyles.body2) }
                        item { TextButton("选择 hc22000 文件", onClick = { pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) }
                        item { TextField(value = sheet.input, onValueChange = viewModel::input, label = "hc22000 文本", modifier = Modifier.fillMaxWidth(), maxLines = 8) }
                    }
                    HashcatSheetPage.DICTIONARIES -> {
                        item { Text("选择字典（可多选，自动合并并去重，保留首次出现的顺序）", style = MiuixTheme.textStyles.body2) }
                        if (dictionaries.isEmpty()) item { Text("资源管理中暂无普通字典；脚本字典不可选。") }
                        items(dictionaries, key = { it.id }) { dictionary ->
                            val checked = dictionary.id in sheet.selectedDictionaryIds
                            BasicComponent(title = dictionary.name ?: dictionary.id, summary = dictionary.description,
                                onClick = { viewModel.select(dictionary.id) }, endActions = {
                                    Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off,
                                        onClick = { viewModel.select(dictionary.id) })
                                })
                        }
                    }
                    HashcatSheetPage.DETAIL -> {
                        val task = tasks.firstOrNull { it.id == sheet.taskId }
                        if (task == null) item { Text("加载中") }
                        else {
                            item { Text(task.state.label(), style = MiuixTheme.textStyles.subtitle) }
                            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { TaskNetworks(task) } }
                            item { if (task.evaluationFinished()) Assessment(task) else Progress(task) }
                            if (task.devices.isNotBlank()) item { Text("计算设备：${task.devices}", style = MiuixTheme.textStyles.body2) }
                            if (task.candidates.isNotBlank()) item { Text("当前候选批次：${task.candidates}", style = MiuixTheme.textStyles.body2) }
                            items(task.findings) { finding ->
                                io.github.bszapp.wifitoolbox.ui.component.WeakPasswordResult(
                                    "${task.id}:${finding.dictionaryPosition}", finding.dictionaryPosition, finding.password)
                            }
                            task.error?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
                            item {
                                TextButton("重选字典重新运行", onClick = { viewModel.reselectDictionaries(task.id) },
                                    enabled = connected && !sheet.busy, modifier = Modifier.fillMaxWidth())
                            }
                            item {
                                TextButton("删除任务", onClick = { viewModel.deleteTask(task.id) },
                                    enabled = !sheet.busy && (connected || !task.active), modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.textButtonColors(color = MiuixTheme.colorScheme.errorContainer,
                                        textColor = MiuixTheme.colorScheme.error))
                            }
                        }
                    }
                }
                sheet.message?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (page == HashcatSheetPage.DICTIONARIES) { if (sheet.taskId == null) "上一步" else "返回任务" } else "关闭",
                    onClick = { if (page == HashcatSheetPage.DICTIONARIES) viewModel.previous() else viewModel.dismiss() },
                    modifier = Modifier.weight(1f), enabled = !sheet.busy)
                when (page) {
                    HashcatSheetPage.PREPARATION -> if (kernels?.state != HashcatKernelState.COMPILING) TextButton(
                        "继续", onClick = viewModel::compileKernels, enabled = connected && !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.INPUT -> TextButton("下一步", onClick = viewModel::next, enabled = !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.DICTIONARIES -> TextButton(if (sheet.busy) "准备中" else if (sheet.taskId == null) "运行" else "重新运行", onClick = viewModel::run,
                        enabled = connected && !sheet.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.DETAIL -> tasks.firstOrNull { it.id == sheet.taskId }?.let { task ->
                        if (task.active || task.state == HashcatTaskState.PAUSED) TextButton(
                            text = if (task.state == HashcatTaskState.PAUSED) "继续" else if (task.state == HashcatTaskState.PAUSING) "正在暂停" else "暂停",
                            onClick = { viewModel.toggleTask(task.id) }, enabled = connected && !sheet.busy && task.state != HashcatTaskState.PAUSING,
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
        }
    }
}

@Composable
private fun TaskNetworks(task: HashcatTaskSnapshot) {
    if (task.networks.isEmpty()) Text("网络信息不可用", style = MiuixTheme.textStyles.body2)
    task.networks.forEach { network ->
        Text("网络：${network.ssid.ifEmpty { "隐藏网络" }}", style = MiuixTheme.textStyles.body2)
        Text("MAC：${network.mac}", style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun KernelProgress(snapshot: HashcatKernelSnapshot) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.state) {
        while (snapshot.state == HashcatKernelState.COMPILING) { now = System.currentTimeMillis(); delay(200) }
    }
    Text("正在编译内核", style = MiuixTheme.textStyles.subtitle)
    Text("正在为当前设备编译Hashcat内核，可能需要数十秒", style = MiuixTheme.textStyles.body2)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(snapshot.stage, style = MiuixTheme.textStyles.body2)
    Text("已完成 ${snapshot.steps.count { it.finishedAt != null }} 个步骤", style = MiuixTheme.textStyles.body2)
    snapshot.steps.forEach { step ->
        val elapsed = ((step.finishedAt ?: now) - step.startedAt).coerceAtLeast(0) / 1000.0
        Text("${if (step.finishedAt == null) "进行中" else "已完成"} · ${step.label} · ${String.format(Locale.ROOT, "%.1f", elapsed)}秒",
            style = MiuixTheme.textStyles.footnote1)
    }
}

private fun HashcatTaskSnapshot.evaluationFinished() = state == HashcatTaskState.SUCCEEDED || state == HashcatTaskState.EXHAUSTED

@Composable
private fun Assessment(task: HashcatTaskSnapshot) {
    Text(if (task.state == HashcatTaskState.SUCCEEDED) "已成功发现密码" else "给定字典未发现密码", style = MiuixTheme.textStyles.body2)
    Text("计算耗时：${if (task.computeDurationMillis > 0) String.format(Locale.ROOT, "%.2f秒", task.computeDurationMillis / 1000.0) else "未记录"}", style = MiuixTheme.textStyles.body2)
    Text("平均校验速度：${if (task.computeDurationMillis > 0) "${task.averageSpeed} H/s" else "未记录"}", style = MiuixTheme.textStyles.body2)
    Text("已评估：${task.completed}/${task.total}", style = MiuixTheme.textStyles.body2)
}

@Composable
private fun Progress(task: HashcatTaskSnapshot) {
    Text(task.step, style = MiuixTheme.textStyles.body2)
    io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
        targetState = task.takeIf { it.stepTotal > 0 || (it.active && it.total == 0L) },
        contentKey = { it?.let { state -> state.stepTotal > 0 } },
        label = "hashcat-primary-progress",
    ) { progressTask ->
        if (progressTask != null) {
            if (progressTask.stepTotal > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val label = if (progressTask.stepUnit == "PBKDF2") "当前批次 PBKDF2 计算进度" else "步骤进度"
                    Text("$label：${progressTask.stepCompleted}/${progressTask.stepTotal}${if (progressTask.stepUnit == "BYTES") " B" else ""}", style = MiuixTheme.textStyles.body2)
                    LinearProgressIndicator(progress = (progressTask.stepCompleted.toFloat() / progressTask.stepTotal).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                }
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
    io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
        targetState = task.takeIf { it.total > 0 },
        contentKey = { it == null },
        label = "hashcat-file-progress",
    ) { fileTask ->
        if (fileTask != null) {
            val percent = fileTask.completed.toDouble() * 100 / fileTask.total
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${fileTask.completed}/${fileTask.total}${if (fileTask.progressUnit == "BYTES") " B" else ""} · ${String.format(Locale.ROOT, "%.2f", percent)}%", style = MiuixTheme.textStyles.body2)
                LinearProgressIndicator(progress = (fileTask.completed.toFloat() / fileTask.total).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
            }
        }
    }
    io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
        targetState = task.takeIf { it.speed > 0 },
        contentKey = { it == null },
        label = "hashcat-speed",
    ) { speedTask ->
        if (speedTask != null) {
            Text("${speedTask.speed} H/s · 剩余 ${speedTask.remainingSeconds?.let { "${it}秒" } ?: "计算中"}", style = MiuixTheme.textStyles.body2)
        }
    }
}
private fun HashcatTaskState.label() = when (this) {
    HashcatTaskState.RUNNING -> "运行中"
    HashcatTaskState.PAUSING -> "正在暂停"
    HashcatTaskState.PAUSED -> "已暂停"
    HashcatTaskState.SUCCEEDED -> "已成功"
    HashcatTaskState.EXHAUSTED -> "字典已用尽"
    HashcatTaskState.FAILED -> "运行失败"
}
private fun formatDate(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
