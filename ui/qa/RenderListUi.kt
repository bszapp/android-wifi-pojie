// UI QA 无头渲染器（qbsg 方式）。
// 用法（经 :ui:uiQaRender 任务）:
//   gradle :ui:uiQaRender -PqaArgs="<场景> [宽] [高] [light|dark|monet-light|monet-dark]"
//   gradle :ui:uiQaRender -PqaArgs="matrix [场景1,场景2,…]"   // 全矩阵：场景 × 8 变体
// 矩阵变体：每场景 4 主题 @412x915 + light @360x740 + light @834x1112 + 方形手表 light @200x200。
package qa

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import android.content.res.Configuration
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiModeState
import io.github.bszapp.wifitoolbox.contract.wifilist.WifiState
import io.github.bszapp.wifitoolbox.ui.component.LocalQaThemeController
import io.github.bszapp.wifitoolbox.ui.component.LocalSheetPresentationManager
import io.github.bszapp.wifitoolbox.ui.component.PreviewSheetHost
import io.github.bszapp.wifitoolbox.ui.component.SheetPresentationManager
import io.github.bszapp.wifitoolbox.ui.model.NormalWifiSubmode
import io.github.bszapp.wifitoolbox.ui.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.ui.navigation.Route
import io.github.bszapp.wifitoolbox.ui.navigation.rememberNavigator
import io.github.bszapp.wifitoolbox.ui.theme.ColorMode
import io.github.bszapp.wifitoolbox.ui.theme.LocalColorMode
import io.github.bszapp.wifitoolbox.ui.screen.ListPageContent
import io.github.bszapp.wifitoolbox.ui.screen.UsbMonitorTaskContentPreview
import io.github.bszapp.wifitoolbox.ui.screen.UsbMonitorTaskContentWaitingPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.ConnectWifiTaskSheetConfigPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.ConnectWifiTaskSheetTaskPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorCaptureClearSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorCaptureSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorDeviceDetailSheetHandshakeTestPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorDeviceDetailSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.MonitorModeSheetPreview
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.PskSecurityAuditTaskSheet
import io.github.bszapp.wifitoolbox.ui.psksecurityaudit.WpsSecurityAuditTaskSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiDetailSheet
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiListContent
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WifiModeSwitchSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPbcTaskSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPinInputSheetPreview
import io.github.bszapp.wifitoolbox.ui.widget.wifilist.WpsPinTaskSheetPreview
import java.io.File
import io.github.bszapp.wifitoolbox.contract.task.TaskExecutionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

private val qaScenes = listOf(
    // 整页：列表页（WifiListContent 内容侧走假数据插槽）
    "list-scan",
    "list-connected",
    "list-empty",
    "list-disabled",
    "list-underlying",
    "list-monitor-idle",
    "list-monitor-capturing",
    // 整页：弱密码检测子模式（任务 FAB 与行内进度形态）
    "list-audit-dict-running",
    "list-audit-wps-running",
    "list-audit-nomode",
    "list-audit-task-finished",
    // 弹层：复用现有 @Preview 真身
    "sheet-mode-switch",
    "sheet-capture-clear",
    "sheet-monitor-mode",
    "sheet-capture",
    "sheet-connect-config",
    "sheet-connect-task",
    "sheet-wps-pbc",
    "sheet-wps-pin-input",
    "sheet-wps-pin-task",
    "sheet-usb",
    "sheet-usb-waiting",
    "sheet-device-detail",
    "sheet-device-handshake-test",
    "sheet-wifi-detail",
    "sheet-audit-task",
    "sheet-wps-audit-task",
)

private data class QaVariant(val mode: String, val width: Int, val height: Int, val suffix: String? = null)

private val qaVariants = listOf(
    QaVariant("light", 412, 915),
    QaVariant("dark", 412, 915),
    QaVariant("monet-light", 412, 915),
    QaVariant("monet-dark", 412, 915),
    QaVariant("light", 360, 740, suffix = "360x740"),
    QaVariant("light", 834, 1112, suffix = "834x1112"),
    QaVariant("light", 200, 200, suffix = "watch"),
)

fun qaThemeController(mode: String): ThemeController {
    val scheme = when (mode) {
        "dark" -> ColorSchemeMode.Dark
        "monet-light" -> ColorSchemeMode.MonetLight
        "monet-dark" -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.Light
    }
    // "正常配色"：keyColor 走 miuix 默认（与应用默认色一致），palette/colorSpec 与生产默认相同。
    return ThemeController(
        colorSchemeMode = scheme,
        keyColor = null,
        paletteStyle = ThemePaletteStyle.TonalSpot,
        colorSpec = ThemeColorSpec.Spec2025,
    )
}

@Composable
private fun QaListPage(
    wifiState: WifiState,
    modeState: WifiModeState?,
    submode: NormalWifiSubmode = NormalWifiSubmode.SCAN,
    auditTasks: List<io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState> = emptyList(),
    auditBatch: io.github.bszapp.wifitoolbox.contract.task.TrackedTaskState? = null,
    currentTask: io.github.bszapp.wifitoolbox.contract.task.TaskSnapshot? = null,
) {
    ListPageContent(
        state = qaListPageState(wifiState, modeState, submode, auditTasks)
            .copy(currentTask = currentTask),
        actions = QaListPageActions(),
        wifiListContent = { modifier, listState, contentPadding, _, _ ->
            WifiListContent(
                state = qaWifiListPageState(
                    wifiState, modeState, submode,
                    auditTasks = auditTasks,
                    auditBatch = auditBatch,
                    currentTask = currentTask,
                ),
                actions = QaWifiListPageActions(),
                modifier = modifier,
                listState = listState,
                contentPadding = contentPadding,
            )
        },
    )
}

@Composable
private fun qaSceneContent(name: String) {
    when (name) {
        "skeleton" -> Box(modifier = Modifier.fillMaxSize()) {
            Text(text = "渲染环境就绪 · $name")
            TextButton(text = "UI 校验", onClick = {})
        }
        "list-scan" -> QaListPage(qaWifiStateScan(), qaNormalModeState())
        "list-connected" -> QaListPage(qaWifiStateConnected(), qaNormalModeState())
        "list-empty" -> QaListPage(qaWifiStateEmpty(), qaNormalModeState())
        "list-disabled" -> QaListPage(qaWifiStateDisabled(), qaNormalModeState())
        "list-underlying" -> QaListPage(qaWifiStateUnderlying(), qaNormalModeState())
        "list-monitor-idle" -> QaListPage(
            qaWifiStateMonitor(),
            qaMonitorModeState(capturing = false),
        )
        "list-monitor-capturing" -> QaListPage(
            qaWifiStateMonitor(capturing = true),
            qaMonitorModeState(capturing = true),
        )
        "list-audit-dict-running" -> QaListPage(
            qaWifiStateScan(),
            qaNormalModeState(),
            submode = NormalWifiSubmode.PSK_SECURITY_AUDIT,
            auditTasks = listOf(qaPskSecurityAuditTracked()),
        )
        "list-audit-wps-running" -> QaListPage(
            qaWifiStateScan(),
            qaNormalModeState(),
            submode = NormalWifiSubmode.WPS_SECURITY_AUDIT,
            auditBatch = qaWpsAuditTracked(),
            currentTask = qaWpsAuditTracked().snapshot,
        )
        // 生产边界态：服务未就绪（modeState=null）时打开弱密码检测子模式，
        // 与屏幕层判定一致，卡片应显示运行/停止而不是连接。
        "list-audit-nomode" -> QaListPage(
            qaWifiStateScan(),
            null,
            submode = NormalWifiSubmode.PSK_SECURITY_AUDIT,
        )
        // 任务 FAB 空闲态：无任务时也可点击，打开空日志详情；这里展示已结束任务仍可打开。
        "list-audit-task-finished" -> QaListPage(
            qaWifiStateScan(),
            qaNormalModeState(),
            submode = NormalWifiSubmode.PSK_SECURITY_AUDIT,
            auditTasks = listOf(
                qaPskSecurityAuditTracked().let { task ->
                    task.copy(snapshot = task.snapshot.copy(state = TaskExecutionState.FINISHED))
                }
            ),
        )
        "sheet-mode-switch" -> WifiModeSwitchSheetPreview()
        "sheet-capture-clear" -> MonitorCaptureClearSheetPreview()
        "sheet-monitor-mode" -> MonitorModeSheetPreview()
        "sheet-capture" -> MonitorCaptureSheetPreview()
        "sheet-connect-config" -> ConnectWifiTaskSheetConfigPreview()
        "sheet-connect-task" -> ConnectWifiTaskSheetTaskPreview()
        "sheet-wps-pbc" -> WpsPbcTaskSheetPreview()
        "sheet-wps-pin-input" -> WpsPinInputSheetPreview()
        "sheet-wps-pin-task" -> WpsPinTaskSheetPreview()
        "sheet-usb" -> UsbMonitorTaskContentPreview()
        "sheet-usb-waiting" -> UsbMonitorTaskContentWaitingPreview()
        "sheet-device-detail" -> MonitorDeviceDetailSheetPreview()
        "sheet-device-handshake-test" -> MonitorDeviceDetailSheetHandshakeTestPreview()
        "sheet-wifi-detail" -> PreviewSheetHost {
            WifiDetailSheet(
                show = true,
                group = qaConnectedGroup(),
                onDismiss = {},
                onDismissFinished = {},
            )
        }
        "sheet-audit-task" -> PreviewSheetHost {
            PskSecurityAuditTaskSheet(
                task = qaPskSecurityAuditTracked(),
                onStop = {},
                onDismiss = {},
            )
        }
        "sheet-wps-audit-task" -> PreviewSheetHost {
            WpsSecurityAuditTaskSheet(
                task = qaWpsAuditTracked(),
                onStop = {},
                onDismiss = {},
            )
        }
        else -> Box(modifier = Modifier.fillMaxSize()) {
            Text(text = "场景 $name 尚未接线")
        }
    }
}

private fun qaColorMode(mode: String): ColorMode = when (mode) {
    "dark" -> ColorMode.DARK
    "monet-light" -> ColorMode.MONET_LIGHT
    "monet-dark" -> ColorMode.MONET_DARK
    else -> ColorMode.LIGHT
}

private fun qaConfiguration(variant: QaVariant): Configuration = Configuration().apply {
    screenWidthDp = variant.width
    screenHeightDp = variant.height
    screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL or Configuration.SCREENLAYOUT_LONG_NO
    densityDpi = 420
    fontScale = 1f
}

private suspend fun renderOnce(name: String, variant: QaVariant, outputRoot: File) {
    val dirLabel = if (variant.suffix == null) variant.mode else "${variant.mode}-${variant.suffix}"
    val output = File(outputRoot, "$name-$dirLabel").apply { mkdirs() }

    val controller = qaThemeController(variant.mode)
    val owner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    }
    val scene = ImageComposeScene(
        width = variant.width,
        height = variant.height,
        density = Density(1f, 1f),
        coroutineContext = Dispatchers.Swing,
    ) {
        CompositionLocalProvider(
            LocalNavigationEventDispatcherOwner provides owner,
            LocalQaThemeController provides controller,
            LocalColorMode provides qaColorMode(variant.mode),
            LocalContext provides android.content.Context(),
            LocalConfiguration provides qaConfiguration(variant),
        ) {
            MiuixTheme(controller = controller) {
                if (name.startsWith("list-")) {
                    CompositionLocalProvider(
                        LocalSheetPresentationManager provides SheetPresentationManager(),
                        LocalNavigator provides rememberNavigator(Route.Main),
                    ) {
                        qaSceneContent(name)
                    }
                } else {
                    qaSceneContent(name)
                }
            }
        }
    }

    // 帧驱动：render(nanos) 推进内部帧时钟并出图；前 35 帧空转让动画/状态收敛，最后存盘。
    var nanos = 1L
    repeat(35) {
        scene.render(nanos)
        nanos += 16_666_667L
        delay(3)
    }
    val image = scene.render(nanos)
    image.use { snapshot ->
        snapshot.encodeToData()!!.use { data ->
            File(output, "frame-0000.png").writeBytes(data.bytes)
        }
    }
    scene.close()
    println("Rendered ${File(output, "frame-0000.png").absolutePath}")
}

fun main(args: Array<String>) = runBlocking(Dispatchers.Swing) {
    val name = args.getOrElse(0) { "matrix" }
    // 输出目录由 uiQaRender 任务注入（qa.outDir），脱离任务直接跑时默认 qa-out/。
    val outputRoot = System.getProperty("qa.outDir")?.let { File(it) } ?: File("qa-out")
    if (name == "matrix") {
        val scenes = if (args.size > 1) args[1].split(',') else qaScenes
        var ok = 0
        val failed = mutableListOf<String>()
        for (scene in scenes) {
            for (variant in qaVariants) {
                try {
                    renderOnce(scene, variant, outputRoot)
                    ok++
                } catch (t: Throwable) {
                    failed += "${scene}-${variant.mode}-${variant.width}x${variant.height}: ${t::class.simpleName}: ${t.message}"
                    t.printStackTrace()
                }
            }
        }
        println("Matrix done: ok=$ok failed=${failed.size}")
        failed.forEach { println("FAILED $it") }
    } else {
        val width = args.getOrElse(1) { "412" }.toInt()
        val height = args.getOrElse(2) { "915" }.toInt()
        val mode = args.getOrElse(3) { "light" }
        renderOnce(name, QaVariant(mode, width, height), outputRoot)
    }
}
