package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.ServiceStatusDialog
import io.github.bszapp.wifitoolbox.uidefault.theme.LocalEnableBlur
import io.github.bszapp.wifitoolbox.uidefault.theme.isInDarkTheme
import io.github.bszapp.wifitoolbox.uidefault.util.BlurredBar
import io.github.bszapp.wifitoolbox.uidefault.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    viewModel: DefaultViewModel = viewModel(),
    bottomInnerPadding: Dp = 0.dp,
) {
    val uid by viewModel.startup.uid.collectAsStateWithLifecycle()
    val pid by viewModel.startup.pid.collectAsStateWithLifecycle()
    val mode by viewModel.startup.mode.collectAsStateWithLifecycle()
    val versionName by viewModel.startup.serviceVersionName.collectAsStateWithLifecycle()
    val versionCode by viewModel.startup.serviceVersionCode.collectAsStateWithLifecycle()
    val uidStr by viewModel.startup.uidStr.collectAsStateWithLifecycle()
    var showSheet by rememberSaveable { mutableStateOf(false) }

    val active = uid != null
    val modeText = mode.displayName()
    val versionText = formatVersion(versionName, versionCode)
    val pidText = pid?.toString() ?: "未知"
    val shortVersion = versionName?.takeIf { it.isNotBlank() } ?: "未知"

    if (showSheet) {
        ServiceStatusDialog(
            show = showSheet,
            title = if (active) "服务运行中" else "服务未激活",
            modeText = modeText,
            pidText = pidText,
            uidStr = uidStr ?: "未知",
            versionText = versionText,
            onDismiss = { showSheet = false },
            onExit = {
                showSheet = false
                viewModel.startup.stop(true)
            },
            onReselect = {
                showSheet = false
                viewModel.startup.stop(false)
            },
        )
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(LocalEnableBlur.current)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "WifiToolbox",
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ServiceStatusCard(
                            active = active,
                            modeText = modeText,
                            pidText = pidText,
                            versionName = shortVersion,
                            onClick = { showSheet = true },
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(
    active: Boolean,
    modeText: String,
    pidText: String,
    versionName: String,
    onClick: () -> Unit,
) {
    val isDynamicColor = MiuixTheme.colorScheme.primary != Color.Unspecified
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.defaultColors(
                color = when {
                    active && isDynamicColor -> colorScheme.secondaryContainer
                    active && isInDarkTheme() -> Color(0xFF1A3825)
                    active -> Color(0xFFDFFAE4)
                    isInDarkTheme() -> Color(0xFF3B2424)
                    else -> Color(0xFFFFE1E1)
                },
            ),
            onClick = onClick,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(38.dp, 45.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = if (active) MiuixIcons.Ok else MiuixIcons.Close,
                        tint = if (active) {
                            if (isDynamicColor) colorScheme.primary.copy(alpha = 0.8f) else Color(0xFF36D167)
                        } else {
                            colorScheme.error.copy(alpha = 0.78f)
                        },
                        contentDescription = null,
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 16.dp),
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (active) "服务运行中 <$modeText>" else "服务未激活",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = if (active) "服务版本 $versionName" else "点击重新选择工作模式",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            InfoNumberCard(
                modifier = Modifier.weight(1f),
                title = "PID",
                value = pidText,
                onClick = onClick,
            )
            Spacer(Modifier.height(12.dp))
            InfoNumberCard(
                modifier = Modifier.weight(1f),
                title = "版本",
                value = versionName,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun InfoNumberCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = title,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun StartupMode?.displayName(): String = when (this) {
    StartupMode.SHIZUKU -> "Shizuku"
    StartupMode.SHIZUKU_TERMINAL -> "Terminal"
    StartupMode.ROOT -> "Root"
    null -> "未知"
}

internal fun formatVersion(name: String?, code: Long?): String {
    val versionName = name?.takeIf { it.isNotBlank() } ?: return "未知"
    val versionCode = code?.takeIf { it >= 0 } ?: return "未知"
    return "$versionName($versionCode)"
}
