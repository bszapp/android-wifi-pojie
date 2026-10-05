package io.github.bszapp.wifitoolbox.uidefault.component


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp


enum class TagStyle { Primary, Secondary, Tertiary }

@Composable
fun TagItem(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TagStyle = TagStyle.Primary,
) {
    val containerColor = when (style) {
        TagStyle.Primary -> MaterialTheme.colorScheme.primaryContainer
        TagStyle.Secondary -> MaterialTheme.colorScheme.secondaryContainer
        TagStyle.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = when (style) {
        TagStyle.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        TagStyle.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
        TagStyle.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.padding(horizontal = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            icon?.let { visibleIcon ->
                Icon(
                    imageVector = visibleIcon,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}
