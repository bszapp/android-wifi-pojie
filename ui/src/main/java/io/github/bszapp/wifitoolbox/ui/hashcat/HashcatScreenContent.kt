package io.github.bszapp.wifitoolbox.ui.hashcat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelState
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatKernelStep
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatNetwork
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskSnapshot
import io.github.bszapp.wifitoolbox.contract.hashcat.HashcatTaskState
import io.github.bszapp.wifitoolbox.ui.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.ui.component.WeakPasswordResult
import io.github.bszapp.wifitoolbox.ui.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.ui.util.BlurredBar
import io.github.bszapp.wifitoolbox.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HashcatSheetPage { PREPARATION, INPUT, DICTIONARIES, DETAIL }
data class HashcatSheetState(
    val show: Boolean = false,
    val page: HashcatSheetPage = HashcatSheetPage.INPUT,
    val input: String = "",
    val selectedDictionaryIds: Set<String> = emptySet(),
    val taskId: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Hashcat 页面的动作集；ViewModel 负责，QA 用空实现渲染。 */
interface HashcatScreenActions {
    fun open()
    fun dismiss()
    fun input(value: String)
    fun next()
    fun previous()
    fun select(id: String)
    fun run()
    fun compileKernels()
    fun inspect(id: String)
    fun reselectDictionaries(id: String)
    fun deleteTask(id: String)
    fun toggleTask(id: String)
    fun pickFile()
    /** 创建服务端合成的速度测试任务并直接运行。 */
    fun speedTest()
}

@Composable
fun HashcatScreenContent(
    tasks: List<HashcatTaskSnapshot>,
    connected: Boolean,
    sheet: HashcatSheetState,
    dictionaries: List<DictionaryResource>,
    kernels: HashcatKernelSnapshot?,
    snackbarHostState: SnackbarHostState,
    actions: HashcatScreenActions,
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
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
                if (!connected) item { Text("服务未连接，仅可查看任务记录", style = MiuixTheme.textStyles.body2) }
                if (tasks.isEmpty()) {
                    item { HashcatEmptyState(onOpen = { actions.open() }) }
                }
                val activeTasks = tasks.filter { it.active || it.state == HashcatTaskState.PAUSED }
                val settledTasks = tasks.filter { it.evaluationFinished() || it.state == HashcatTaskState.FAILED }
                if (activeTasks.isNotEmpty()) {
                    item { SmallTitle(text = "进行中") }
                    items(activeTasks, key = { it.id }) { task ->
                        HashcatTaskCard(
                            task = task,
                            connected = connected,
                            busy = sheet.busy,
                            onInspect = { actions.inspect(task.id) },
                            onToggle = { actions.toggleTask(task.id) },
                        )
                    }
                }
                if (settledTasks.isNotEmpty()) {
                    item { SmallTitle(text = "已完成") }
                    items(settledTasks, key = { it.id }) { task ->
                        HashcatTaskCard(
                            task = task,
                            connected = connected,
                            busy = sheet.busy,
                            onInspect = { actions.inspect(task.id) },
                            onToggle = { actions.toggleTask(task.id) },
                            onReselect = { actions.reselectDictionaries(task.id) },
                        )
                    }
                }
            }
            FloatingActionButton(
                onClick = { actions.open() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = safeBottom + 16.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "运行", tint = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
    SingleOverlayBottomSheet(
        show = sheet.show,
        title = when {
            sheet.page == HashcatSheetPage.DETAIL -> "WPA Hashcat" + "任务"
            sheet.page == HashcatSheetPage.DICTIONARIES && sheet.taskId != null -> "重新运行"
            else -> "运行WPA Hashcat"
        },
        onDismissRequest = { actions.dismiss() },
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
                contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (page) {
                    HashcatSheetPage.PREPARATION -> itemsPreparation(kernels)
                    HashcatSheetPage.INPUT -> itemsInput(sheet, actions)
                    HashcatSheetPage.DICTIONARIES -> itemsDictionaries(sheet, dictionaries, actions)
                    HashcatSheetPage.DETAIL -> itemsDetail(sheet, tasks, connected, actions)
                }
                sheet.message?.let { message ->
                    item { SheetMessage(message) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (page == HashcatSheetPage.DICTIONARIES) { if (sheet.taskId == null) "上一步" else "返回任务" } else "关闭",
                    onClick = { if (page == HashcatSheetPage.DICTIONARIES) actions.previous() else actions.dismiss() },
                    modifier = Modifier.weight(1f), enabled = !sheet.busy)
                when (page) {
                    HashcatSheetPage.PREPARATION -> if (kernels?.state != HashcatKernelState.COMPILING) TextButton(
                        if (kernels?.state == HashcatKernelState.FAILED) "重试编译" else "编译内核",
                        onClick = actions::compileKernels, enabled = connected && !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.INPUT -> TextButton("下一步", onClick = actions::next, enabled = !sheet.busy,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.DICTIONARIES -> TextButton(if (sheet.busy) "合并中" else if (sheet.taskId == null) "运行" else "重新运行", onClick = actions::run,
                        enabled = connected && !sheet.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    HashcatSheetPage.DETAIL -> tasks.firstOrNull { it.id == sheet.taskId }?.let { task ->
                        if (task.active || task.state == HashcatTaskState.PAUSED) TextButton(
                            text = pauseResumeLabel(task.state),
                            onClick = { actions.toggleTask(task.id) }, enabled = connected && !sheet.busy && task.state != HashcatTaskState.PAUSING,
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
            Spacer(Modifier.height(safeBottom))
        }
        }
    }
}

// ── 列表页 ────────────────────────────────────────────────────────────────────

@Composable
private fun HashcatEmptyState(onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.Memory,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(52.dp),
        )
        Text("暂无评估任务", style = MiuixTheme.textStyles.title4)
        Text(
            "捕获握手包后，用显卡级引擎校验字典候选",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onOpen,
            modifier = Modifier.padding(top = 6.dp),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text("开始评估")
        }
    }
}

/** 列表任务卡：网络名为主标题，状态徽标与时间各占一行；组内结构等高。 */
@Composable
private fun HashcatTaskCard(
    task: HashcatTaskSnapshot,
    connected: Boolean,
    busy: Boolean,
    onInspect: () -> Unit,
    onToggle: () -> Unit,
    onReselect: (() -> Unit)? = null,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onInspect),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    primaryNetworkName(task),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MiuixTheme.textStyles.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HashcatStateChip(task.state)
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatTaskTime(task.createdAt),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            CardMetadataRow(task)
            when {
                task.evaluationFinished() -> CardSummaryFinished(task)
                task.state == HashcatTaskState.FAILED -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "运行失败：${task.error ?: task.step}",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onReselect != null) {
                        TextButton(
                            "重选字典重新运行",
                            onClick = onReselect,
                            enabled = connected,
                        )
                    }
                }
                else -> CardSummaryRunning(
                    task = task,
                    onToggle = onToggle,
                    toggleEnabled = connected && !busy && task.state != HashcatTaskState.PAUSING,
                )
            }
        }
    }
}

private fun primaryNetworkName(task: HashcatTaskSnapshot): String =
    task.networks.firstOrNull()?.ssid ?: "网络信息不可用"

/** 卡片小字信息：独立元素横向流式排列，无标点分隔。 */
@Composable
private fun CardMetadataRow(task: HashcatTaskSnapshot) {
    val dictionaries = task.dictionaryNames
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        task.networks.firstOrNull()?.let { network ->
            MetadataPiece(network.mac)
        }
        dictionaries.forEach { dictionary ->
            MetadataPiece(dictionary)
        }
        if (task.networks.size > 1) {
            MetadataPiece("共${task.networks.size}个网络")
        }
    }
}

@Composable
private fun MetadataPiece(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
        softWrap = false,
    )
}

/** 运行或暂停中的卡内进度区。 */
@Composable
private fun CardSummaryRunning(
    task: HashcatTaskSnapshot,
    onToggle: () -> Unit,
    toggleEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            task.step,
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LinearProgressIndicator(
            progress = task.taskFraction(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = taskPercent(task),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            if (task.speed > 0) {
                Text(
                    text = "速度${formatSpeed(task.speed)} H/s",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                )
            }
            Text(
                text = remainingLabel(task.remainingSeconds),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.End,
            )
        }
        if (task.state == HashcatTaskState.PAUSED) {
            Text(
                "已暂停并保存恢复文件，可随时继续",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (task.active || task.state == HashcatTaskState.PAUSED) {
            TextButton(
                text = pauseResumeLabel(task.state),
                onClick = onToggle,
                enabled = toggleEnabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** 已有结论的卡内统计区。 */
@Composable
private fun CardSummaryFinished(task: HashcatTaskSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (task.state == HashcatTaskState.SUCCEEDED) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "发现${task.findings.size}个弱密码",
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "点击查看密码",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        } else {
            Text(
                "给定字典未发现弱密码",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (task.computeDurationMillis > 0) {
                MetadataPiece("计算耗时${formatDuration(task.computeDurationMillis)}")
            }
            if (task.averageSpeed > 0) {
                MetadataPiece("平均速度${formatSpeed(task.averageSpeed)} H/s")
            }
            MetadataPiece(taskPercent(task))
        }
    }
}

// ── 弹层：内核准备页 ─────────────────────────────────────────────────────────

private fun LazyListScope.itemsPreparation(kernels: HashcatKernelSnapshot?) {
    when {
        kernels != null && kernels.state == HashcatKernelState.COMPILING -> {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在编译内核", style = MiuixTheme.textStyles.title4)
                        InfiniteProgressIndicator(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .size(20.dp),
                        )
                    }
                    Text(
                        "首次在你的设备上编译Hashcat内核，可能需要数十秒，之后直接复用缓存",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetadataPiece(kernelStage(kernels))
                        MetadataPiece(kernelProgress(kernels))
                    }
                }
            }
            item { KernelStepList(kernels.steps) }
        }
        kernels != null && kernels.state == HashcatKernelState.FAILED -> {
            item {
                KernelHero(
                    title = "内核编译失败",
                    description = kernels.error ?: "未知错误",
                )
            }
        }
        else -> {
            item {
                KernelHero(
                    title = if (kernels == null) "检查内核状态" else "内核尚未编译",
                    description = "内核就绪后才能校验候选密码，首次编译可能需要数十秒",
                )
            }
        }
    }
}

@Composable
private fun KernelStepList(steps: List<HashcatKernelStep>) {
    val runningIndex = steps.indexOfFirst { it.finishedAt == null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEachIndexed { index, step ->
            KernelStepRow(step, running = index == runningIndex)
        }
    }
}

/** 单个内核步骤行：完成勾、进行中旋转、未开始空心点。 */
@Composable
private fun KernelStepRow(step: HashcatKernelStep, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            step.finishedAt != null -> Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            running -> InfiniteProgressIndicator(modifier = Modifier.size(16.dp))
            else -> Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .border(2.dp, MiuixTheme.colorScheme.onSurfaceVariantSummary, CircleShape),
            )
        }
        Text(
            step.label,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
            style = MiuixTheme.textStyles.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                step.finishedAt != null -> formatStepElapsed(step)
                running -> "进行中"
                else -> "待进行"
            },
            modifier = Modifier.padding(start = 10.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun KernelHero(title: String, description: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Rounded.Memory,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(48.dp),
        )
        Text(title, style = MiuixTheme.textStyles.title4)
        Text(
            description,
            modifier = Modifier.fillMaxWidth(),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

// ── 弹层：输入页 ─────────────────────────────────────────────────────────────

private fun LazyListScope.itemsInput(sheet: HashcatSheetState, actions: HashcatScreenActions) {
    item {
        Text(
            "选择hc22000文件，或直接粘贴握手包文本；识别结果实时显示",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    item {
        Button(
            onClick = { actions.pickFile() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("选择hc22000文件")
        }
    }
    item {
        TextField(
            value = sheet.input,
            onValueChange = actions::input,
            label = "hc22000文本",
            modifier = Modifier.fillMaxWidth(),
            maxLines = 8,
        )
    }
    item { ParsedNetworksPreview(sheet.input) }
    item {
        SpeedTestCard(
            enabled = !sheet.busy,
            onRun = actions::speedTest,
        )
    }
}

/** 速度测试入口：服务端合成随机握手与内置字典，一键创建并运行。 */
@Composable
private fun SpeedTestCard(enabled: Boolean, onRun: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onRun)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "速度测试",
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "一键创建",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            "随机合成SpeedTest网络与内置字典，无需握手文件，立即运行并测出本机速度",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 输入页的握手包实时解析预览。 */
@Composable
private fun ParsedNetworksPreview(input: String) {
    val networks = remember(input) { HashcatNetwork.fromHandshake(input) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (networks.isEmpty()) {
            Text(
                "尚未识别到有效网络记录",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Text("识别到${networks.size}个网络", style = MiuixTheme.textStyles.subtitle)
            networks.take(3).forEach { network ->
                NetworkField(network)
            }
            if (networks.size > 3) {
                Text(
                    "以及另外${networks.size - 3}个网络",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

// ── 弹层：字典选择页 ─────────────────────────────────────────────────────────

private fun LazyListScope.itemsDictionaries(
    sheet: HashcatSheetState,
    dictionaries: List<DictionaryResource>,
    actions: HashcatScreenActions,
) {
    item {
        Text(
            "可多选，运行时自动合并去重并保留首次出现的顺序",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    if (dictionaries.isEmpty()) {
        item { Text("资源管理中暂无普通字典；脚本字典不可选", style = MiuixTheme.textStyles.body2) }
    }
    items(dictionaries, key = { it.id }) { dictionary ->
        val checked = dictionary.id in sheet.selectedDictionaryIds
        Card(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = dictionary.name ?: dictionary.id,
                summary = dictionary.description,
                onClick = { actions.select(dictionary.id) },
                endActions = {
                    Checkbox(
                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                        onClick = { actions.select(dictionary.id) },
                    )
                },
            )
        }
    }
    item {
        Text(
            if (sheet.selectedDictionaryIds.isEmpty()) "尚未选择字典" else "已选${sheet.selectedDictionaryIds.size}个字典",
            modifier = Modifier.padding(start = 4.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

// ── 弹层：任务详情页 ─────────────────────────────────────────────────────────

private fun LazyListScope.itemsDetail(
    sheet: HashcatSheetState,
    tasks: List<HashcatTaskSnapshot>,
    connected: Boolean,
    actions: HashcatScreenActions,
) {
    val task = tasks.firstOrNull { it.id == sheet.taskId }
    if (task == null) {
        item { Text("加载中", style = MiuixTheme.textStyles.body2) }
        return
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    primaryNetworkName(task),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HashcatStateChip(task.state)
            }
            if (task.networks.size > 1) {
                task.networks.drop(1).forEach { network -> NetworkField(network) }
            }
            CardMetadataRow(task)
        }
    }
    item { HashcatDetailProgress(task) }
    if (task.devices.isNotBlank()) {
        item { DetailFieldRow("计算设备", task.devices) }
    }
    if (task.candidates.isNotBlank()) {
        item { DetailFieldRow("当前候选批次", task.candidates) }
    }
    val errorText = task.error
    if (errorText != null) {
        item {
            Text(
                errorText,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.error,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (task.findings.isNotEmpty()) {
        item { SmallTitle(text = "发现的弱密码") }
        items(task.findings, key = { "${task.id}-${it.dictionaryPosition}" }) { finding ->
            WeakPasswordResult("${task.id}:${finding.dictionaryPosition}", finding.dictionaryPosition, finding.password)
        }
    }
    item {
        TextButton(
            "重选字典重新运行",
            onClick = { actions.reselectDictionaries(task.id) },
            enabled = connected && !sheet.busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        TextButton(
            "删除任务",
            onClick = { actions.deleteTask(task.id) },
            enabled = !sheet.busy && (connected || !task.active),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.errorContainer,
                textColor = MiuixTheme.colorScheme.error,
            ),
        )
    }
}

/** 详情页的进度或结论主区。 */
@Composable
private fun HashcatDetailProgress(task: HashcatTaskSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (task.evaluationFinished()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailStat("评估规模", "${task.completed}/${task.total}")
                DetailStat("计算耗时", formatDuration(task.computeDurationMillis))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailStat(
                    "平均速度",
                    task.averageSpeed.takeIf { it > 0 }?.let { "${formatSpeed(it)} H/s" } ?: "未记录",
                )
                DetailStat(
                    if (task.state == HashcatTaskState.SUCCEEDED) "弱密码" else "结论",
                    if (task.state == HashcatTaskState.SUCCEEDED) "${task.findings.size}个" else "未发现",
                )
            }
        } else {
            Text(
                task.step,
                style = MiuixTheme.textStyles.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    taskPercent(task),
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 1,
                    softWrap = false,
                )
                LinearProgressIndicator(
                    progress = task.taskFraction(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                Text(
                    taskProgressCounts(task),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    remainingLabel(task.remainingSeconds),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                )
            }
            if (task.speed > 0) {
                Text(
                    "速度${formatSpeed(task.speed)} H/s",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (task.stepTotal > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        MetadataPiece(
                            if (task.stepUnit == "PBKDF2") "本批次PBKDF2计算" else "步骤进度"
                        )
                        MetadataPiece(
                            "${task.stepCompleted}/${task.stepTotal}${if (task.stepUnit == "BYTES") "B" else ""}"
                        )
                    }
                    LinearProgressIndicator(
                        progress = (task.stepCompleted.toFloat() / task.stepTotal).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (task.state == HashcatTaskState.PAUSED) {
                Text(
                    "已暂停并保存恢复文件，可随时继续",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun RowScope.DetailStat(label: String, value: String) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Text(
            value,
            style = MiuixTheme.textStyles.subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailFieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            softWrap = false,
        )
        Text(
            value,
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NetworkField(network: HashcatNetwork) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            network.ssid,
            style = MiuixTheme.textStyles.body1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            network.mac,
            modifier = Modifier.padding(start = 8.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun HashcatStateChip(state: HashcatTaskState) {
    val colors = MiuixTheme.colorScheme
    val (label, tint, background) = when (state) {
        HashcatTaskState.RUNNING -> Triple("运行中", colors.primary, colors.primary.copy(alpha = 0.14f))
        HashcatTaskState.PAUSING -> Triple("正在暂停", colors.onSurfaceVariantSummary, colors.onSurfaceVariantSummary.copy(alpha = 0.12f))
        HashcatTaskState.PAUSED -> Triple("已暂停", colors.onSurfaceVariantSummary, colors.onSurfaceVariantSummary.copy(alpha = 0.12f))
        HashcatTaskState.SUCCEEDED -> Triple("已成功", colors.primary, colors.primary.copy(alpha = 0.14f))
        HashcatTaskState.EXHAUSTED -> Triple("字典已用尽", colors.onSurfaceVariantSummary, colors.onSurfaceVariantSummary.copy(alpha = 0.12f))
        HashcatTaskState.FAILED -> Triple("运行失败", colors.error, colors.error.copy(alpha = 0.12f))
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = tint, maxLines = 1)
    }
}

@Composable
private fun SheetMessage(message: String) {
    Text(
        message,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.error,
    )
}

// ── 格式化与工具 ─────────────────────────────────────────────────────────────

private fun HashcatTaskSnapshot.evaluationFinished() =
    state == HashcatTaskState.SUCCEEDED || state == HashcatTaskState.EXHAUSTED

private fun HashcatTaskSnapshot.taskFraction(): Float =
    if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f

private fun taskPercent(task: HashcatTaskSnapshot): String =
    if (task.total > 0) {
        String.format(Locale.ROOT, "%.1f", task.completed.toDouble() * 100 / task.total) + "%"
    } else {
        "等待候选"
    }

private fun taskProgressCounts(task: HashcatTaskSnapshot): String =
    if (task.total > 0) {
        "${task.completed}/${task.total}${if (task.progressUnit == "BYTES") "B" else ""}"
    } else {
        "等待候选"
    }

private fun remainingLabel(seconds: Long?): String =
    when (seconds) {
        null -> "预计剩余计算中"
        else -> "预计剩余${formatRemainingSeconds(seconds)}"
    }

private fun formatRemainingSeconds(seconds: Long): String {
    val remaining = seconds.coerceAtLeast(0)
    if (remaining < 60) return "${remaining}秒"
    val hours = remaining / 3600
    val minutes = (remaining % 3600) / 60
    val secs = remaining % 60
    return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分${secs}秒"
}

private fun formatDuration(millis: Long): String =
    if (millis > 0) {
        val seconds = millis / 1000.0
        if (seconds < 60) String.format(Locale.ROOT, "%.1f", seconds) + "秒"
        else formatRemainingSeconds(seconds.toLong())
    } else {
        "未记录"
    }

private fun formatSpeed(speed: Long): String =
    String.format(Locale.ROOT, "%,d", speed)

private fun formatStepElapsed(step: HashcatKernelStep): String {
    val end = step.finishedAt ?: return "进行中"
    val seconds = (end - step.startedAt).coerceAtLeast(0) / 1000.0
    return String.format(Locale.ROOT, "%.1f", seconds) + "秒"
}

private fun kernelStage(kernels: HashcatKernelSnapshot): String =
    if (kernels.stage.isBlank()) "正在编译" else kernels.stage

private fun kernelProgress(kernels: HashcatKernelSnapshot): String {
    val finishedCount = kernels.steps.count { it.finishedAt != null }
    val total = maxOf(kernels.steps.size, 1)
    return "已完成${finishedCount}/${total}个步骤"
}

private fun pauseResumeLabel(state: HashcatTaskState): String = when (state) {
    HashcatTaskState.PAUSED -> "继续"
    HashcatTaskState.PAUSING -> "正在暂停"
    else -> "暂停"
}

private fun formatTaskTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
