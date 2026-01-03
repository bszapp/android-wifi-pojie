package com.wifi.toolbox.ui.items

import android.net.wifi.WifiManager
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.structs.*

@Composable
fun WifiPojieItem(
    modifier: Modifier,
    wifi: WifiInfo,
    runningInfo: PojieRunInfo?,
    finishedInfo: String?,
    onStartClick: ((String) -> Unit) = {},
    onStopClick: ((String) -> Unit) = {},
) {
    var stableInfo by remember { mutableStateOf<PojieRunInfo?>(null) }
    var stableFinishedInfo by remember { mutableStateOf<String?>(null) }
    if (runningInfo != null) {
        stableInfo = runningInfo
    }
    if (finishedInfo != null) {
        stableFinishedInfo = finishedInfo
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable {}) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            @Suppress("DEPRECATION")
            val levelIndex = if (wifi.level == 0) 0
            else WifiManager.calculateSignalLevel(wifi.level, 5)
            WifiIcon(
                modifier = Modifier.size(28.dp),
                level = levelIndex
            )

            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = wifi.ssid,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1
                        )
                        Text(
                            text = if (wifi.level == 0) "未知" else "${wifi.level} dBm",
                            style = MaterialTheme.typography.bodySmall
                        )
                        AnimatedVisibility(
                            visible = finishedInfo != null,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Text(
                                text = stableFinishedInfo ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Button(
                        onClick = {
                            if (runningInfo != null) onStopClick(
                                wifi.ssid
                            )
                            else onStartClick(wifi.ssid)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (runningInfo != null) Color(0xFFFF4444)
                            else MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(
                            horizontal = 18.dp, vertical = 6.dp
                        ),
                    ) {
                        Icon(
                            imageVector = if (runningInfo != null) Icons.Default.Stop else Icons.Default.PlayArrow,
                            tint = if (runningInfo != null) Color.White else MaterialTheme.colorScheme.onPrimary,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = if (runningInfo != null) "停止" else "开始",
                            color = if (runningInfo != null) Color.White else MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = runningInfo != null,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    stableInfo?.let { info ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(
                                        alpha = 0.7f
                                    )
                                )
                                .padding(10.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(
                                    6.dp
                                )
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.RocketLaunch,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                    Spacer(
                                        modifier = Modifier.width(
                                            6.dp
                                        )
                                    )
                                    Text(
                                        text = "运行中",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight(600),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.weight(
                                            1f
                                        )
                                    )
                                }

                                val totalCount = info.tryList.size
                                val currentIndex = info.tryIndex
                                if (totalCount == 0) {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                                            alpha = 0.1f
                                        )
                                    )
                                } else {
                                    val progress = currentIndex.toFloat() / totalCount
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.tertiary,
                                        trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                                            alpha = 0.1f
                                        )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val progressText =
                                        if (totalCount > 0) "$currentIndex / $totalCount" else "-/-"
                                    val progress =
                                        if (totalCount > 0) (currentIndex.toFloat() / totalCount * 100) else null
                                    val progressNum =
                                        if (totalCount > 0) "${
                                            "%.1f".format(progress)
                                        }%" else "-%"
                                    Text(
                                        text = progressText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                                            alpha = 0.7f
                                        )
                                    )
                                    Text(
                                        text = progressNum,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }

                                Text(
                                    text = info.textTip,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                                        alpha = 0.8f
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}