package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.uidefault.dictionary.TagItem
import io.github.bszapp.wifitoolbox.uidefault.dictionary.TagType

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
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = res.name ?: res.id,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (res.isBuiltin == 1) {
                    TagItem(stringResource(R.string.dictionary_tag_builtin))
                } else if (res.isBuiltin == 2) {
                    TagItem(stringResource(R.string.dictionary_tag_overwrite))
                }
                if (res.type == 1) {
                    TagItem(text = stringResource(R.string.dictionary_tag_script), type = TagType.Tertiary)
                }
            }
            Text(
                text = res.description ?: stringResource(R.string.dictionary_no_description),
                maxLines = 2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (checkbox != null) {
            Checkbox(
                checked = checkbox,
                onCheckedChange = null
            )
        }
    }
}
