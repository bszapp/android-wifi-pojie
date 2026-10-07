package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDecryptionStatus
import io.github.bszapp.wifitoolbox.contract.wifilist.MonitorDeviceProtocol
import io.github.bszapp.wifitoolbox.uidefault.model.MonitorCommunicationUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun decryptionStatusText(value: MonitorDecryptionStatus): String = when (value) {
    MonitorDecryptionStatus.PROTOCOL_UNKNOWN -> "尚未确定设备实际使用的协议，不能据此判断通信安全"
    MonitorDecryptionStatus.NO_PASSWORD -> "无目标接入点密码"
    MonitorDecryptionStatus.PASSWORD_MISMATCH -> "本机存储的目标接入点密码有误"
    MonitorDecryptionStatus.INCOMPLETE_HANDSHAKE -> "未捕获到完整握手数据"
    MonitorDecryptionStatus.READY -> "已核验本机保存的密码，可解析捕获的通信数据"
    MonitorDecryptionStatus.SECURE -> "该网络传输的数据是安全的"
    MonitorDecryptionStatus.UNSUPPORTED -> "当前不支持解密设备实际使用的协议或密码套件"
    MonitorDecryptionStatus.LOADING -> "加载中"
}

internal fun deviceProtocolText(value: MonitorDeviceProtocol): String = when (value) {
    MonitorDeviceProtocol.UNKNOWN -> "设备实际协议尚未确定"
    MonitorDeviceProtocol.WPA_PSK -> "设备实际协议：WPA-PSK"
    MonitorDeviceProtocol.WPA2_PSK -> "设备实际协议：WPA2-PSK"
    MonitorDeviceProtocol.WPA2_PSK_SHA256 -> "设备实际协议：WPA2-PSK-SHA256"
    MonitorDeviceProtocol.SAE -> "设备实际协议：SAE（WPA3）"
    MonitorDeviceProtocol.OWE -> "设备实际协议：OWE"
    MonitorDeviceProtocol.OTHER -> "设备实际使用其他认证协议"
}

internal fun LazyListScope.monitorCommunicationItems(
    reader: MonitorCommunicationUiState,
    display: MonitorCommunicationUiState.Display,
) {
    item {
        Text("HTTPS 仅显示明确捕获的 TLS 域名，不解密正文。ECH、加密 DNS 或漏包可能使域名不可见。",
            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    item {
        val page = display.page
        SmallTitle(if (page == null) "通信记录" else "通信记录 · ${page.totalCount} 条")
        if (display.loading) Text("加载中", style = MiuixTheme.textStyles.body2)
        display.error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2) }
        if (page?.totalCount == 0L) {
            Card { BasicComponent(title = "暂未捕获到通信数据") }
        }
    }
    items(display.page?.records.orEmpty(), key = { "communication:${it.id}" }) { record ->
        Card {
            Column {
                BasicComponent(
                    title = "${if (record.type.name == "HTTPS") "HTTPS / TLS 域名" else record.type.name} · ${record.summary}",
                    summary = formatHandshakeStartTime(record.timestampUnixMillis) +
                        if (record.complete) "" else " · 请求数据尚未完整捕获",
                    onClick = { reader.toggleRecord(record.id) },
                )
                AnimatedVisibility(display.selectedRecordId == record.id) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${record.source} → ${record.destination}", style = MiuixTheme.textStyles.body2)
                        val detail = display.detail?.takeIf { it.recordId == record.id }
                        if (detail != null) {
                            SelectionContainer {
                                Text(detail.bytes.toString(Charsets.UTF_8), style = MiuixTheme.textStyles.body2)
                            }
                            Text("详情共 ${formatMonitorByteCount(detail.totalBytes)} · 分页读取",
                                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Row(Modifier.fillMaxWidth()) {
                                TextButton("上一段", enabled = detail.previousCursor >= 0,
                                    onClick = { reader.detailPage(detail.previousCursor) }, modifier = Modifier.weight(1f))
                                TextButton("下一段", enabled = detail.nextCursor >= 0,
                                    onClick = { reader.detailPage(detail.nextCursor) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
    item {
        val page = display.page
        Row(Modifier.fillMaxWidth()) {
            TextButton("上一页", enabled = page != null && page.fromIndex > 0,
                onClick = { reader.page((page!!.fromIndex - 32).coerceAtLeast(0)) }, modifier = Modifier.weight(1f))
            TextButton("下一页", enabled = page != null && page.nextIndex < page.totalCount,
                onClick = { reader.page(page!!.nextIndex) }, modifier = Modifier.weight(1f))
            TextButton("刷新", onClick = { reader.page(page?.fromIndex ?: 0) }, modifier = Modifier.weight(1f))
        }
    }
}
