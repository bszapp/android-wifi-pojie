package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.uidefault.dictionary.TagItem
import io.github.bszapp.wifitoolbox.uidefault.dictionary.TagType
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
                    if (res.isBuiltin == 1) add(R.string.dictionary_tag_builtin to TagType.Secondary)
                    if (res.isBuiltin == 2) add(R.string.dictionary_tag_overwrite to TagType.Secondary)
                    if (res.type == 1) add(R.string.dictionary_tag_script to TagType.Tertiary)
                }
                tagResources.forEach { (textResource, type) ->
                    TagItem(text = stringResource(textResource), type = type)
                }
            }
            Text(
                text = res.description ?: stringResource(R.string.dictionary_no_description),
                maxLines = 2,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }

            io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
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
