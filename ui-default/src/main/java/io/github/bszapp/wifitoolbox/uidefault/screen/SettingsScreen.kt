package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.screen.settings.ServiceStatusDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: DefaultViewModel = viewModel()) {
    val uid by viewModel.startup.uid.collectAsStateWithLifecycle()
    val pid by viewModel.startup.pid.collectAsStateWithLifecycle()
    val mode by viewModel.startup.mode.collectAsStateWithLifecycle()
    val versionName by viewModel.startup.serviceVersionName.collectAsStateWithLifecycle()
    val versionCode by viewModel.startup.serviceVersionCode.collectAsStateWithLifecycle()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    var showDialog by rememberSaveable { mutableStateOf(false) }
    val uidStr by viewModel.startup.uidStr.collectAsStateWithLifecycle()

    val isActive = uid != null
    val containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onErrorContainer
    val icon = if (isActive) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline
    val title = if (isActive) "服务运行中" else "未激活"
    val modeText = mode.displayName()
    val versionText = formatVersion(versionName, versionCode)
    val subtitle = if (isActive) "$modeText  $versionText  UID:$uid  PID:$pid" else "点击选择工作模式"

    if (showDialog) {
        ServiceStatusDialog(
            uidStr = uidStr ?: "获取失败",
            versionText = versionText,
            onDismiss = { showDialog = false },
            onExit = {
                showDialog = false
                viewModel.startup.stop(true)
            },
            onReselect = {
                showDialog = false
                viewModel.startup.stop(false)
            }
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = "设置",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(containerColor)
                    .clickable {
                        if (isActive) showDialog = true
                        else viewModel.startup.stop(false)
                    }
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = contentColor,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}


private fun StartupMode?.displayName(): String = when (this) {
    StartupMode.SHIZUKU -> "Shizuku"
    StartupMode.SHIZUKU_TERMINAL -> "Terminal"
    StartupMode.ROOT -> "Root"
    null -> "未知"
}

private fun formatVersion(name: String?, code: Long?): String {
    val versionName = name?.takeIf { it.isNotBlank() } ?: "unknown"
    val versionCode = code?.takeIf { it >= 0 }?.toString() ?: "-1"
    return "$versionName($versionCode)"
}
