package io.github.bszapp.wifitoolbox.ui.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DictionaryResourceItem(
    modifier: Modifier = Modifier,
    res: DictionaryResource,
    checkbox: Boolean? = null
) {
    Row(
        modifier = modifier
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (res.type == 1) Icons.Default.Code else Icons.Default.Description,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = res.name ?: res.id,
                    style = MiuixTheme.textStyles.body1
                )
                val tagResources = buildList {
                    if (res.isBuiltin == 1) add(DictionaryStr.tagBuiltin to TagType.Secondary)
                    if (res.isBuiltin == 2) add(DictionaryStr.tagOverwrite to TagType.Secondary)
                    if (res.type == 1) add(DictionaryStr.tagScript to TagType.Tertiary)
                }
                tagResources.forEach { (textResource, type) ->
                    TagItem(text = textResource, type = type)
                }
            }
            Text(
                text = res.description ?: DictionaryStr.noDescription,
                maxLines = 2,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

            io.github.bszapp.wifitoolbox.ui.component.ImmediateContent(
                targetState = checkbox,
                label = "dictionary-resource-selection",
            ) { checked ->
                if (checked != null) Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = null,
                )
            }
    }
}
