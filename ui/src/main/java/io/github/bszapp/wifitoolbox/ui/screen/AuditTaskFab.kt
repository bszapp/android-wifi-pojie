package io.github.bszapp.wifitoolbox.ui.screen

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 弱密码检测/PIN漏洞检测子模式的任务详情 FAB：
 * 运行中为脉冲动画图标；空闲为静态日志图标。点击打开当前类型最新任务的详情，
 * 没有任何任务时打开空日志详情。
 */
@Composable
internal fun AuditTaskFab(
    running: Boolean,
    onClick: () -> Unit,
) {
    FloatingActionButton(onClick = onClick) {
        if (running) {
            PulsePlayIcon()
        } else {
            Icon(
                Icons.Rounded.Terminal,
                contentDescription = "任务详情",
                tint = MiuixTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun PulsePlayIcon() {
    val transition = rememberInfiniteTransition(label = "audit-fab-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(820), RepeatMode.Reverse),
        label = "audit-fab-alpha",
    )
    Icon(
        Icons.Rounded.PlayArrow,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onPrimary.copy(alpha = alpha),
        modifier = Modifier
            .size(28.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}
