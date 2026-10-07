package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.component.SingleOverlayBottomSheet
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import io.github.bszapp.wifitoolbox.uidefault.dictionary.TagItem
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ResourceDetailSheet(
    isVisible: Boolean,
    resource: DictionaryResource?,
    onDismiss: () -> Unit,
    onEdit: (DictionaryResource) -> Unit,
    onDelete: (String) -> Unit
) {
    var lastResource by remember { mutableStateOf<DictionaryResource?>(null) }
    if (resource != null) lastResource = resource

    SingleOverlayBottomSheet(
        show = isVisible,
        onDismissRequest = onDismiss,
    ) {
        lastResource?.let { res ->
            ResourceDetailContent(
                resource = res,
                onEdit = { onEdit(res) },
                onDelete = {
                    onDismiss()
                    onDelete(res.id)
                }
            )
        }
    }
}

@Composable
fun ResourceDetailContent(
    resource: DictionaryResource,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val safeBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.dictionary_resource_details), style = MiuixTheme.textStyles.title2)

            IconButton(onClick = {
                val file = if (resource.localPath != null) {
                    java.io.File(resource.localPath!!)
                } else {
                    val cacheDir = java.io.File(context.cacheDir, "shared_res")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val ext = if (resource.type == 0) "json" else "js"
                    val tempFile = java.io.File(cacheDir, "${resource.id}.$ext")
                    val fileName = "dictionary/${resource.id}.$ext"
                    context.assets.open(fileName).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (resource.type == 0) "application/json" else "text/javascript"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, null))
            }) {
                Icon(Icons.Filled.Share, null)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier
                .weight(1f)
                .padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.dictionary_label_name),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resource.name ?: stringResource(R.string.dictionary_none),
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f, false)
                    )
                    io.github.bszapp.wifitoolbox.uidefault.component.ImmediateVisibility(visible = resource.type == 1) {
                        TagItem(text = stringResource(R.string.dictionary_tag_script), modifier = Modifier.padding(4.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.dictionary_id), resource.id)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.dictionary_developer), resource.author)
            }
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.dictionary_version), resource.version)
            }
        }

        Spacer(Modifier.height(12.dp))

        DetailItem(stringResource(R.string.dictionary_description), resource.description)

        io.github.bszapp.wifitoolbox.uidefault.component.ImmediateContent(
            targetState = resource.localPath,
            label = "dictionary-local-path",
        ) { localPath ->
            if (localPath != null) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    DetailItem(stringResource(R.string.dictionary_local_path), localPath)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (resource.isBuiltin) {
                1 -> {
                    TextButton(
                        text = stringResource(R.string.dictionary_builtin_resource),
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        enabled = false
                    )
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dictionary_overwrite_resource))
                    }
                }

                2 -> {
                    TextButton(
                        text = stringResource(R.string.dictionary_restore_builtin),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dictionary_edit_overwrite))
                    }
                }

                else -> {
                    TextButton(
                        text = stringResource(R.string.dictionary_delete),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(
                            textColor = MiuixTheme.colorScheme.error,
                        )
                    )
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.dictionary_edit))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
        Spacer(modifier = Modifier.height(safeBottom))
    }
}

@Composable
fun DetailItem(label: String, value: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary
        )
        Text(text = value ?: stringResource(R.string.dictionary_none), style = MiuixTheme.textStyles.body1)
    }
}
