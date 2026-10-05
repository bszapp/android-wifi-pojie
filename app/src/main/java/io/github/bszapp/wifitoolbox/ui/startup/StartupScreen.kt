package io.github.bszapp.wifitoolbox.ui.startup

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.error.ServiceCrashException
import io.github.bszapp.wifitoolbox.error.ServiceCrashReport
import io.github.bszapp.wifitoolbox.contract.startup.RunningException
import io.github.bszapp.wifitoolbox.contract.startup.StartupMode
import io.github.bszapp.wifitoolbox.contract.startup.StartupState
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus.*
import io.github.bszapp.wifitoolbox.ui.component.TaggedLinkText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StartupScreen(viewModel: StartupViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serviceCrash = state.errorException as? ServiceCrashException
    val exportCrashArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val archive = serviceCrash?.report?.archiveFile
        if (uri != null && archive?.isFile == true) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法打开 ZIP 保存位置")
                    output.use { destination -> archive.inputStream().use { it.copyTo(destination) } }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (result.isSuccess) "崩溃日志已导出" else "导出崩溃日志失败：${result.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    StartupScreenContent(
        state = state,
        onLaunch = viewModel::launch,
        onCancel = viewModel::cancel,
        onExportCrashArchive = {
            exportCrashArchive.launch("service_crash_${System.currentTimeMillis()}.zip")
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartupScreenContent(
    state: StartupState,
    onLaunch: (StartupMode) -> Unit,
    onCancel: () -> Unit,
    onExportCrashArchive: () -> Unit,
) {
    val serviceCrash = state.errorException as? ServiceCrashException
    val allModes = listOf(StartupMode.SHIZUKU, StartupMode.SHIZUKU_TERMINAL, StartupMode.ROOT)

    val contentMaxWidth = 480.dp

    val displayList = when (state.status) {
        IDLE -> allModes
        LAUNCHING, ERROR -> allModes.filter { it == state.selectedMode }
        else -> emptyList()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            item {
                Spacer(Modifier.padding(vertical = 8.dp))
            }

            // 上方状态显示
            item {
                when (state.status) {
                    IDLE -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Construction,
                                contentDescription = "Done",
                                modifier = Modifier
                                    .size(120.dp)
                                    .padding(bottom = 16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "工作模式",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "本应用需要adb/root权限运行，请从下方选择一个授权模式",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    LAUNCHING -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LoadingIndicator(
                                modifier = Modifier
                                    .size(100.dp)
                                    .padding(bottom = 8.dp)
                            )
                            Text(
                                text = "载入中",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp),
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "正在启动服务，请等待数秒……",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(bottom = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    RUNNING -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.CheckCircle,
                                contentDescription = "Done",
                                modifier = Modifier
                                    .size(120.dp)
                                    .padding(bottom = 8.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    ERROR -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (serviceCrash != null) {
                                Icon(
                                    imageVector = Icons.TwoTone.BugReport,
                                    contentDescription = "Error",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .padding(bottom = 16.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                )
                                Text(
                                    text = "崩溃啦",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    text = serviceCrash.report.exceptionMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 4.dp),
                                    textAlign = TextAlign.Center,
                                )
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(
                                                SpanStyle(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    textDecoration = TextDecoration.Underline,
                                                ),
                                            ) {
                                                append("导出服务日志")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .clickable(onClick = onExportCrashArchive),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = if (state.errorException is RunningException) Icons.TwoTone.BugReport else Icons.TwoTone.ErrorOutline,
                                    contentDescription = "Error",
                                    modifier = Modifier
                                        .size(120.dp)
                                        .padding(bottom = 16.dp),
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = if (state.errorException is RunningException) "崩溃啦" else "启动失败",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                                TaggedLinkText(
                                    text = state.errorException?.message.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // 工作模式选项
            items(displayList, key = { it.name }) { mode ->
                val index = displayList.indexOf(mode)

                SplicedGroupItem(
                    title = when (mode) {
                        StartupMode.SHIZUKU -> "Shizuku"
                        StartupMode.SHIZUKU_TERMINAL -> "Shizuku Terminal"
                        StartupMode.ROOT -> "Root"
                    },
                    description = when (mode) {
                        StartupMode.SHIZUKU -> "绑定Shizuku用户服务，需要额外安装并启动Shizuku，有无root均支持"
                        StartupMode.SHIZUKU_TERMINAL -> "利用Shizuku完成服务启动，上一种方式无法启动可尝试此方法"
                        StartupMode.ROOT -> "适合已root的设备，不需要额外安装应用"
                    },
                    icon = when (mode) {
                        StartupMode.SHIZUKU -> Icons.TwoTone.Bolt
                        StartupMode.SHIZUKU_TERMINAL -> Icons.TwoTone.Terminal
                        StartupMode.ROOT -> Icons.TwoTone.Shield
                    },
                    showArrow = state.status == IDLE,
                    isFirst = index == 0,
                    isEnd = index == displayList.size - 1,
                    onClick = { if (state.status == IDLE) onLaunch(mode) },
                    modifier = Modifier.animateItem()
                )
            }

            // 操作按钮
            if (state.status == ERROR || state.status == LAUNCHING) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = contentMaxWidth)
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onCancel,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = if (state.status == ERROR) "上一步" else "取消",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (state.status == ERROR) {
                                Button(
                                    onClick = { state.selectedMode?.let(onLaunch) },
                                    shape = RoundedCornerShape(16.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        "重试",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.padding(vertical = 8.dp))
            }
        }
    }

}

@Composable
private fun StartupScreenPreviewContent(state: StartupState) {
    MaterialTheme {
        StartupScreenContent(
            state = state,
            onLaunch = {},
            onCancel = {},
            onExportCrashArchive = {},
        )
    }
}

@Preview(name = "启动页 · 空闲", showBackground = true)
@Composable
private fun StartupScreenIdlePreview() {
    StartupScreenPreviewContent(StartupState())
}

@Preview(name = "启动页 · 启动中", showBackground = true)
@Composable
private fun StartupScreenLaunchingPreview() {
    StartupScreenPreviewContent(
        StartupState(
            status = LAUNCHING,
            selectedMode = StartupMode.SHIZUKU,
        ),
    )
}

@Preview(name = "启动页 · 运行成功", showBackground = true)
@Composable
private fun StartupScreenRunningPreview() {
    StartupScreenPreviewContent(StartupState(status = RUNNING))
}

@Preview(name = "启动页 · 启动失败", showBackground = true)
@Composable
private fun StartupScreenErrorPreview() {
    StartupScreenPreviewContent(
        StartupState(
            status = ERROR,
            selectedMode = StartupMode.ROOT,
            errorException = Exception("Root 权限不可用，请检查授权状态。"),
        ),
    )
}

@Preview(name = "启动页 · 服务崩溃", showBackground = true)
@Composable
private fun StartupScreenServiceCrashPreview() {
    StartupScreenPreviewContent(
        StartupState(
            status = ERROR,
            selectedMode = StartupMode.ROOT,
            errorException = ServiceCrashException(
                ServiceCrashReport(
                    processId = "1234",
                    thread = "main",
                    exceptionType = "java.lang.IllegalStateException",
                    exceptionMessage = "示例服务异常消息",
                    stackTrace = "",
                    archiveFile = File("preview-service-crash.zip"),
                ),
            ),
        ),
    )
}
