package io.github.bszapp.wifitoolbox.ui.startup

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Shield
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun SplicedGroupItem(
    title: String,
    description: String,
    icon: ImageVector,
    showArrow: Boolean,
    isFirst: Boolean,
    isEnd: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val topRadius by animateDpAsState(if (isFirst) 16.dp else 4.dp, label = "topRadius")
    val bottomRadius by animateDpAsState(if (isEnd) 16.dp else 4.dp, label = "bottomRadius")
    val topPadding by animateDpAsState(if (isFirst) 8.dp else 1.dp, label = "topPadding")
    val bottomPadding by animateDpAsState(if (isEnd) 8.dp else 0.dp, label = "bottomPadding")

    val contentMaxWidth = 480.dp

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(
            topStart = topRadius, topEnd = topRadius,
            bottomStart = bottomRadius, bottomEnd = bottomRadius
        ),
        modifier = modifier
            .padding(top = topPadding, bottom = bottomPadding)
            .fillMaxWidth()
            .widthIn(max = contentMaxWidth)
            .wrapContentWidth(Alignment.CenterHorizontally)
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = contentMaxWidth)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
                io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

data class StartupItemData(
    val id: String,
    val title: String,
    val desc: String,
    val icon: ImageVector
)

@Preview(showBackground = true)
@Composable
fun SplicedGroupItemPreview() {
    val items = listOf(
        StartupItemData("shizuku", "Shizuku", "需要额外安装并启动Shizuku，有无root均支持，启动速度最快", Icons.TwoTone.Bolt),
        StartupItemData("terminal", "Shizuku Terminal", "如果上一种方式无法启动，可尝试此方法，启动速度较慢", Icons.TwoTone.Terminal),
        StartupItemData("root", "Root", "适合已root的设备，不需要额外安装应用", Icons.TwoTone.Shield)
    )

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
            ) {
                items(items, key = { it.id }) { item ->
                    val index = items.indexOf(item)
                    SplicedGroupItem(
                        title = item.title,
                        description = item.desc,
                        icon = item.icon,
                        showArrow = true,
                        isFirst = index == 0,
                        isEnd = index == items.size - 1,
                        onClick = { Log.d("SplicedList", "Clicked: ${item.title}") },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}
