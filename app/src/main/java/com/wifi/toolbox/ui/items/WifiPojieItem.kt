package com.wifi.toolbox.ui.items

import android.R
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.WifiInfo

@Composable
fun wifiPojieItem(
    modifier: Modifier,
    wifi: WifiInfo,
    runningInfo: PojieRunInfo?,
    onStartClick: ((String) -> Unit) = {},
    onStopClick: ((String) -> Unit) = {},
) {
    var stableInfo by remember { mutableStateOf<PojieRunInfo?>(null) }
    if (runningInfo != null) {
        stableInfo = runningInfo
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
            val levelIndex =
                if (wifi.level == 0) 0 else WifiManager.calculateSignalLevel(
                    wifi.level, 5
                )
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
                    }

                    Button(
                        onClick = {
                            if (runningInfo != null) onStopClick(
                                wifi.ssid
                            )
                            else onStartClick(wifi.ssid)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (runningInfo != null) colorResource(
                                R.color.holo_red_light
                            )
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