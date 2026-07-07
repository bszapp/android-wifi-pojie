@file:Suppress("DEPRECATION")

package io.github.bszapp.wifitoolbox.uidefault.widget.wifilist

import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import io.github.bszapp.wifitoolbox.uidefault.model.MergedWifiGroup

// ── 详情底部弹窗 ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDetailSheet(
    group: MergedWifiGroup,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialogWindow?.isNavigationBarContrastEnforced = false
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {

            // ── 固定头部（不参与滚动）──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text(
                            text = group.displaySsid,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${group.networks.size} 个接入点" +
                                    if (group.savedWifiList.isNotEmpty()) " · ${group.savedWifiList.size} 条已保存配置" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp)
            ) {

                // ── 接入点区域 ──
                SectionHeader(
                    icon = {
                        Icon(
                            Icons.Rounded.Router,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    title = "接入点",
                    badge = group.networks.size.toString()
                )

                // 双列瀑布流卡片
                TwoColumnCardFlow(
                    items = group.networks,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { ap ->
                    ApCard(ap = ap)
                }

                // ── 已保存配置区域 ──
                if (group.savedWifiList.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))

                    SectionHeader(
                        icon = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        title = "已保存的配置",
                        badge = group.savedWifiList.size.toString()
                    )

                    Spacer(Modifier.height(8.dp))

                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        group.savedWifiList.forEach { config ->
                            SavedWifiConfigCard(config = config)
                        }
                    }
                }
            }
        }
    }
}

// ── Section 标题 ──────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    badge: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            icon()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ── 双列可变高卡片流 ──────────────────────────────────────────────────────────

@Composable
private fun <T> TwoColumnCardFlow(
    items: List<T>,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    // 使用 Row + 两列 Column 实现双列流式布局（奇偶分列）
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 左列：偶数索引
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.filterIndexed { idx, _ -> idx % 2 == 0 }.forEach { item ->
                itemContent(item)
            }
        }
        // 右列：奇数索引
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items.filterIndexed { idx, _ -> idx % 2 == 1 }.forEach { item ->
                itemContent(item)
            }
        }
    }
}

// ── 单个 AP 卡片 ──────────────────────────────────────────────────────────────

@Composable
private fun ApCard(ap: ScanResult) {
    val signalLevel = WifiManager.calculateSignalLevel(ap.level, 5)
    val (signalLabel, signalColor) = signalInfo(signalLevel)
    val isSecure = ap.capabilities.contains("WPA") || ap.capabilities.contains("WEP")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 顶部：信号强度 + 锁图标
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 信号条
                SignalBars(
                    level = signalLevel,
                    color = signalColor,
                    modifier = Modifier.size(width = 22.dp, height = 16.dp)
                )
                Icon(
                    imageVector = if (isSecure) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    contentDescription = null,
                    tint = if (isSecure)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
            }

            // dBm + 信号等级
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${ap.level}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = signalColor,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = signalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = signalColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )

            // BSSID
            Text(
                text = ap.BSSID!!,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 频率
            ApChip(text = "${ap.frequency} MHz")

            // 安全能力（简化显示）
            if (ap.capabilities.isNotEmpty()) {
                val capShort = ap.capabilities
                    .replace("[", "")
                    .replace("]", " ")
                    .trim()
                Text(
                    text = capShort,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ApChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

// ── 信号条图形 ────────────────────────────────────────────────────────────────

@Composable
private fun SignalBars(
    level: Int,        // 0-4
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val totalBars = 4
        for (i in 1..totalBars) {
            val fraction = i / totalBars.toFloat()
            val active = i <= level
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (active) color
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
            )
        }
    }
}

// ── 已保存配置卡片 ────────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
@Composable
private fun SavedWifiConfigCard(config: WifiConfiguration) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = config.SSID?.trim('"') ?: "<未知>",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "ID ${config.networkId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(6.dp))

            // 密码
            if (!config.preSharedKey.isNullOrEmpty()) {
                SavedInfoRow("密码", config.preSharedKey!!.trim('"'))
            }

            // WEP 密钥
            config.wepKeys?.forEachIndexed { i, key ->
                if (!key.isNullOrEmpty()) {
                    SavedInfoRow("WEP Key $i", key)
                }
            }

            // BSSID
            if (!config.BSSID.isNullOrEmpty()) {
                SavedInfoRow("BSSID", config.BSSID!!)
            }

            // 认证方式
            val keyMgmtBits = buildList {
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)) add("NONE")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) add("WPA_PSK")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) add("WPA_EAP")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) add("IEEE8021X")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)) add("WPA2_PSK")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SAE)) add("SAE(WPA3)")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.OWE)) add("OWE")
                if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.SUITE_B_192)) add("SUITE_B_192")
            }
            if (keyMgmtBits.isNotEmpty()) {
                SavedInfoRow("认证方式", keyMgmtBits.joinToString(" / "))
            }

            // 协议
            val protocols = buildList {
                if (config.allowedProtocols.get(WifiConfiguration.Protocol.WPA)) add("WPA")
                if (config.allowedProtocols.get(WifiConfiguration.Protocol.RSN)) add("RSN(WPA2/3)")
            }
            if (protocols.isNotEmpty()) {
                SavedInfoRow("协议", protocols.joinToString(" / "))
            }

            // 单播加密
            val pairwise = buildList {
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.TKIP)) add("TKIP")
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.CCMP)) add("CCMP(AES)")
                if (config.allowedPairwiseCiphers.get(WifiConfiguration.PairwiseCipher.GCMP_256)) add(
                    "GCMP-256"
                )
            }
            if (pairwise.isNotEmpty()) {
                SavedInfoRow("单播加密", pairwise.joinToString(" / "))
            }

            // 组播加密
            val groupCiphers = buildList {
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.TKIP)) add("TKIP")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.CCMP)) add("CCMP")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP40)) add("WEP40")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.WEP104)) add("WEP104")
                if (config.allowedGroupCiphers.get(WifiConfiguration.GroupCipher.GCMP_256)) add("GCMP-256")
            }
            if (groupCiphers.isNotEmpty()) {
                SavedInfoRow("组播加密", groupCiphers.joinToString(" / "))
            }

            // 隐藏网络
            SavedInfoRow("隐藏网络", if (config.hiddenSSID) "是" else "否")

            // MAC 随机化
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SavedInfoRow(
                    "MAC 随机化", when (config.macRandomizationSetting) {
                        WifiConfiguration.RANDOMIZATION_NONE -> "使用设备MAC"
                        WifiConfiguration.RANDOMIZATION_PERSISTENT -> "指定随机地址"
                        WifiConfiguration.RANDOMIZATION_NON_PERSISTENT -> "每次随机"
                        WifiConfiguration.RANDOMIZATION_AUTO -> "自动"
                        else -> "未知(${config.macRandomizationSetting})"
                    }
                )
                SavedInfoRow("随机 MAC", config.randomizedMacAddress.toString())
            }
        }
    }
}

// ── SavedInfoRow ──────────────────────────────────────────────────────────────

@Composable
private fun SavedInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.62f),
            textAlign = TextAlign.End
        )
    }
}

// ── 工具函数 ──────────────────────────────────────────────────────────────────

@Composable
private fun signalInfo(level: Int): Pair<String, Color> = when (level) {
    4 -> "优秀" to MaterialTheme.colorScheme.primary
    3 -> "良好" to MaterialTheme.colorScheme.tertiary
    2 -> "一般" to MaterialTheme.colorScheme.secondary
    1 -> "较弱" to MaterialTheme.colorScheme.onSurfaceVariant
    else -> "很弱" to MaterialTheme.colorScheme.error
}