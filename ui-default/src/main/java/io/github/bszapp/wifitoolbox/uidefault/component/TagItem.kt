package io.github.bszapp.wifitoolbox.uidefault.component


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme


enum class TagStyle { Primary, Secondary, Tertiary }

@Composable
fun TagItem(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: TagStyle = TagStyle.Primary,
) {
    val colorScheme = MiuixTheme.colorScheme
    val containerColor = when (style) {
        TagStyle.Primary -> colorScheme.primaryContainer
        TagStyle.Secondary -> colorScheme.secondaryContainer
        TagStyle.Tertiary -> colorScheme.tertiaryContainer
    }
    val contentColor = when (style) {
        TagStyle.Primary -> colorScheme.onPrimaryContainer
        TagStyle.Secondary -> colorScheme.onSecondaryContainer
        TagStyle.Tertiary -> colorScheme.onTertiaryContainer
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(4.dp),
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
                style = MiuixTheme.textStyles.footnote2,
                color = contentColor,
            )
        }
    }
}
