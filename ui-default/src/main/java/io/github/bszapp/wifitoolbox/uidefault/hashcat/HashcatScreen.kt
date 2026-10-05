package io.github.bszapp.wifitoolbox.uidefault.hashcat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatMemorySnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HASHCAT_MIB
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelState
import kotlinx.coroutines.delay
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import top.yukonga.miuix.kmp.basic.*
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
    val memory by viewModel.memory.collectAsStateWithLifecycle()
    val kernels by viewModel.kernels.collectAsStateWithLifecycle()
    LaunchedEffect(initialHandshake) { initialHandshake?.let(viewModel::openIncoming) }
    val navigator = LocalNavigator.current
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::readFile)
    }
    Scaffold(
        topBar = {
            TopAppBar(title = "WPA Hashcat", navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            })
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = safeBottom + 92.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!connected) item { Text("服务未连接，历史记录仍可查看", style = MiuixTheme.textStyles.body2) }
                if (tasks.any { it.active }) item { MemoryPanel(memory) }
                if (tasks.isEmpty()) item { Text("暂无 WPA 字典安全评估任务") }
                items(tasks, key = { it.id }) { task ->
                    Card(Modifier.fillMaxWidth().clickable { viewModel.inspect(task.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("WPA Hashcat · ${task.state.label()}", style = MiuixTheme.textStyles.subtitle)
                            Text(formatDate(task.createdAt), style = MiuixTheme.textStyles.body2)
                            Text(task.dictionaryNames.joinToString("、"), style = MiuixTheme.textStyles.body2)
                            if (task.evaluationFinished()) Assessment(task) else {
                                task.memoryLimitMiB?.let { Text("计算内存预算：$it MiB", style = MiuixTheme.textStyles.body2) }
                                Progress(task)
                            }
                            if (task.state == HashcatTaskState.SUCCEEDED) Text("已成功发现密码，点击查看详情", color = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }
            FloatingActionButton(onClick = { viewModel.open() }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = safeBottom + 16.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "运行", tint = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
    SingleOverlayBottomSheet(
        show = sheet.show,
        title = if (sheet.page == HashcatSheetPage.DETAIL) "WPA Hashcat 任务" else "运行 WPA Hashcat",
        onDismissRequest = viewModel::dismiss,
    ) {
        AnimatedContent(
            targetState = sheet.page,
            modifier = Modifier.fillMaxWidth().heightIn(max = sheetHeight),
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                val movingForward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(260)) { width -> if (movingForward) width else -width } togetherWith
                    slideOutHorizontally(tween(260)) { width -> if (movingForward) -width else width })
                    .using(SizeTransform(clip = true) { _, _ -> tween(260) })
            },
            label = "HashcatSheetPage",
        ) { page ->
        Column(Modifier.fillMaxWidth().heightIn(max = sheetHeight), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    HashcatSheetPage.PREPARATION -> {
                        if (kernels?.state == HashcatKernelState.COMPILING) item { KernelProgress(kernels!!) }
                        else {
                            item { Text(if (sheet.busy) "加载中" else "内核暂未编译", style = MiuixTheme.textStyles.subtitle) }
                            item { Text("此功能仅供恢复密码以及检验授权网络的安全性使用。破解未经授权网络密码属于违法行为。",
                                style = MiuixTheme.textStyles.body2) }
                            item {
                                Row(Modifier.fillMaxWidth().clickable { viewModel.agree(!sheet.agreed) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(state = if (sheet.agreed) ToggleableState.On else ToggleableState.Off,
                                        onClick = { viewModel.agree(!sheet.agreed) })
                                    Spacer(Modifier.width(12.dp))
                                    Text("已阅读并同意", style = MiuixTheme.textStyles.body2)
                                }
                            }
                            kernels?.error?.let { error -> item { Text(error, style = MiuixTheme.textStyles.body2) } }
                        }
                    }
                    HashcatSheetPage.INPUT -> {
                        item { Text("选择 hc22000 文件，或直接粘贴握手包的 hc22000 文本。", style = MiuixTheme.textStyles.body2) }
                        item { TextButton("选择 hc22000 文件", onClick = { pickFile.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) }
                        item { TextField(value = sheet.input, onValueChange = viewModel::input, label = "hc22000 文本", modifier = Modifier.fillMaxWidth(), maxLines = 8) }
                    }
                    HashcatSheetPage.DICTIONARIES -> {
                        item { MemoryPanel(memory, selectedMiB = sheet.memoryLimitMiB, onSelect = viewModel::allocateMemory, enabled = connected && !sheet.busy) }
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
                            item { if (task.evaluationFinished()) Assessment(task) else Progress(task) }
                            if (!task.evaluationFinished()) item {
                                val editable = task.state == HashcatTaskState.RUNNING || task.state == HashcatTaskState.PAUSED
                                MemoryPanel(memory, taskId = task.id, selectedMiB = sheet.memoryLimitMiB ?: task.memoryLimitMiB,
                                    onSelect = if (editable) viewModel::allocateMemory else null, enabled = connected && !sheet.busy)
                                if (editable && sheet.memoryLimitMiB != null) TextButton("应用内存预算",
                                    onClick = { viewModel.applyMemory(task.id) }, enabled = connected && !sheet.busy,
                                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
                            }
                            if (task.devices.isNotBlank()) item { Text("计算设备：${task.devices}", style = MiuixTheme.textStyles.body2) }
                            if (task.candidates.isNotBlank()) item { Text("当前候选批次：${task.candidates}", style = MiuixTheme.textStyles.body2) }
                            items(task.findings) { finding ->
                                var visible by rememberSaveable(task.id, finding.dictionaryPosition, finding.password) { mutableStateOf(false) }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("已在给定字典的第${finding.dictionaryPosition}位发现密码，此密码为弱密码，建议立即更换。")
                                        Text(if (visible) finding.password else "••••••••", style = MiuixTheme.textStyles.subtitle)
                                        TextButton(if (visible) "隐藏密码" else "显示密码", onClick = { visible = !visible })
                                    }
                                }
                            }
                            task.error?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
                        }
                    }
                }
                sheet.message?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (page == HashcatSheetPage.DICTIONARIES) "上一步" else "关闭",
                    onClick = { if (page == HashcatSheetPage.DICTIONARIES) viewModel.previous() else viewModel.dismiss() },
                    modifier = Modifier.weight(1f), enabled = !sheet.busy)
                when (page) {
                    HashcatSheetPage.PREPARATION -> if (kernels?.state != HashcatKernelState.COMPILING) TextButton(
                        "继续", onClick = viewModel::compileKernels, enabled = connected && sheet.agreed && !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.INPUT -> TextButton("下一步", onClick = viewModel::next, enabled = !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.DICTIONARIES -> TextButton(if (sheet.busy) "准备中" else "运行", onClick = viewModel::run,
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

/** 下方宽条显示实测 RAM；上方虚线显示预算预览，滑动不会改变实际占用数字。 */
@Composable
private fun MemoryPanel(
    memory: HashcatMemorySnapshot?, taskId: String? = null, selectedMiB: Int? = null,
    onSelect: ((Int) -> Unit)? = null, enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("实时系统内存", style = MiuixTheme.textStyles.subtitle)
            if (memory == null) {
                Text("加载中", style = MiuixTheme.textStyles.body2)
                return@Column
            }
            if (memory.error != null || memory.totalBytes <= 0) {
                Text(memory.error ?: "无法读取内存", style = MiuixTheme.textStyles.body2)
                return@Column
            }
            Text("总内存 ${memorySize(memory.totalBytes)} · 可用 ${memorySize(memory.availableBytes)}",
                style = MiuixTheme.textStyles.body2)
            Text("采样时间 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(memory.capturedAt))} · 每秒更新",
                style = MiuixTheme.textStyles.footnote1)
            val unreadable = memory.tasks.filter { it.pssBytes == null }
            val current = memory.tasks.firstOrNull { it.taskId == taskId }?.pssBytes ?: 0
            val others = memory.tasks.filter { it.taskId != taskId }.sumOf { it.pssBytes ?: 0 }
            val used = memory.totalBytes - memory.availableBytes
            val remainingUsed = (used - current - others).coerceAtLeast(0)
            val maximum = (memory.assignableBytes(taskId) / HASHCAT_MIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val budget = selectedMiB ?: 0
            val otherColor = colors.onSurfaceVariantSummary
            val taskColor = colors.secondary
            val currentColor = colors.primary
            val freeColor = colors.surfaceVariant
            if (unreadable.isEmpty() && current + others <= used) {
                Canvas(Modifier.fillMaxWidth().height(38.dp)) {
                    fun position(bytes: Long) = (bytes.toDouble() / memory.totalBytes * size.width).toFloat().coerceIn(0f, size.width)
                    val top = 14.dp.toPx()
                    var left = 0f
                    listOf(remainingUsed to otherColor, others to taskColor, current to currentColor, memory.availableBytes to freeColor).forEach { (bytes, color) ->
                        val right = (left + position(bytes)).coerceAtMost(size.width)
                        drawRect(color, Offset(left, top), Size((right - left).coerceAtLeast(0f), size.height - top))
                        left = right
                    }
                    if (onSelect != null && maximum > 0 && budget > 0) {
                        val start = position(remainingUsed + others)
                        val width = position(budget.toLong() * HASHCAT_MIB).coerceAtMost(size.width - start)
                        drawRect(currentColor, Offset(start, 2.dp.toPx()), Size(width, 8.dp.toPx()),
                            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
                    }
                }
                MemoryLegend("系统及其他应用", remainingUsed, otherColor)
                MemoryLegend("其他运行任务（PSS）", others, taskColor)
                if (taskId != null) MemoryLegend("本任务实际（PSS）", current, currentColor)
                MemoryLegend("可用（含可回收内存）", memory.availableBytes, freeColor)
            } else {
                Text(if (unreadable.isNotEmpty()) "任务内存读取失败，暂无法绘制完整分类：${unreadable.joinToString { it.error.orEmpty() }}"
                    else "本次 PSS 与可用内存统计存在重叠，等待下一次采样。", style = MiuixTheme.textStyles.body2)
            }
            val reserved = memory.tasks.filter { it.taskId != taskId }.sumOf { (it.budgetMiB?.toLong() ?: 0) * HASHCAT_MIB }
            Text("其他运行任务预算 ${memorySize(reserved)} · 当前可分配 ${memorySize(memory.assignableBytes(taskId))}", style = MiuixTheme.textStyles.body2)
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = onSelect != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (budget > 0) "本任务计算内存预算：$budget MiB（虚线为预览）" else "请使用滑块选择本任务的内存预算", style = MiuixTheme.textStyles.body2)
                    if (maximum > 1) Slider(value = budget.coerceIn(1, maximum).toFloat(),
                        onValueChange = { onSelect?.invoke(it.toInt().coerceIn(1, maximum)) },
                        valueRange = 1f..maximum.toFloat(), enabled = enabled)
                    else if (maximum == 1) TextButton("分配 1 MiB", onClick = { onSelect?.invoke(1) }, enabled = enabled)
                    if (budget > maximum) Text("所选预算超过当前可分配额度，请调整后再运行。", style = MiuixTheme.textStyles.body2)
                    Text("预算限制计算缓冲区；PSS 不包含驱动未映射的独立 GPU 内存。预算过小可能无法运行，运行中修改需保存恢复点后继续。",
                        style = MiuixTheme.textStyles.footnote1)
                }
            }
            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = onSelect == null && selectedMiB != null) {
                if (selectedMiB != null) Text("本任务计算内存预算：$selectedMiB MiB", style = MiuixTheme.textStyles.body2)
            }
            Text("GPU 驱动未映射到进程的内存计入系统部分，任务部分采用实测 PSS。", style = MiuixTheme.textStyles.footnote1)
        }
    }
}

@Composable
private fun MemoryLegend(label: String, bytes: Long, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text("$label：${memorySize(bytes)}", style = MiuixTheme.textStyles.footnote1)
    }
}
private fun memorySize(bytes: Long): String {
    val gib = 1024.0 * 1024 * 1024
    return if (bytes >= gib) String.format(Locale.ROOT, "%.2f GiB", bytes / gib)
    else String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / HASHCAT_MIB)
}

@Composable
private fun Progress(task: HashcatTaskSnapshot) {
    Text(task.step, style = MiuixTheme.textStyles.body2)
    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
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
    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
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
    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
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
