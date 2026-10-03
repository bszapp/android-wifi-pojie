package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class TagType { Primary, Secondary, Tertiary }

@Composable
fun TagItem(
    text: String,
    type: TagType = TagType.Secondary,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = when (type) {
        TagType.Primary -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TagType.Secondary -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        TagType.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    Box(modifier = modifier) {
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(start = 4.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}
