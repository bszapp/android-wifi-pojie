package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class TagType { Primary, Secondary, Tertiary }

@Composable
fun TagItem(
    text: String,
    type: TagType = TagType.Secondary,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = when (type) {
        TagType.Primary -> MiuixTheme.colorScheme.primaryContainer to MiuixTheme.colorScheme.onPrimaryContainer
        TagType.Secondary -> MiuixTheme.colorScheme.secondaryContainer to MiuixTheme.colorScheme.onSecondaryContainer
        TagType.Tertiary -> MiuixTheme.colorScheme.tertiaryContainer to MiuixTheme.colorScheme.onTertiaryContainer
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
                style = MiuixTheme.textStyles.footnote2,
                color = contentColor
            )
        }
    }
}
