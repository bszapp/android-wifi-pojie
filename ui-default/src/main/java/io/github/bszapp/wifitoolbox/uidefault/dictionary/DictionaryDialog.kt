package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AddResourceDialog(
    isVisible: Boolean,
    selectedOption: Int?,
    onOptionSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onContinue: () -> Unit
) {
    if (isVisible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.dictionary_add_resource)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    ResourceOptionItem(
                        icon = Icons.Default.Description,
                        title = stringResource(R.string.dictionary_resource_normal),
                        description = stringResource(R.string.dictionary_resource_normal_desc),
                        selected = selectedOption == 0,
                        onClick = { onOptionSelect(0) }
                    )
                    ResourceOptionItem(
                        icon = Icons.Default.Code,
                        title = stringResource(R.string.dictionary_tag_script),
                        description = stringResource(R.string.dictionary_resource_script_desc),
                        selected = selectedOption == 1,
                        onClick = { onOptionSelect(1) }
                    )
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.dictionary_import_external))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.dictionary_btn_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = selectedOption != null,
                            onClick = onContinue
                        ) {
                            Text(stringResource(R.string.dictionary_continue_text))
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun EditResourceDialog(
    draft: DictionaryResource,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onContentEdit: (DictionaryResource) -> Unit,
    onSave: (DictionaryResource) -> Unit
) {
    val app = LocalDictionaryAlerts.current
    var id by rememberSaveable { mutableStateOf(draft.id) }
    var name by rememberSaveable { mutableStateOf(draft.name ?: "") }
    var description by rememberSaveable { mutableStateOf(draft.description ?: "") }
    var author by rememberSaveable { mutableStateOf(draft.author ?: "") }
    var version by rememberSaveable { mutableStateOf(draft.version ?: "") }
    var contentState by rememberSaveable { mutableStateOf(draft.content) }
    var syncedDraftContent by rememberSaveable { mutableStateOf(draft.content) }
    LaunchedEffect(draft.content) {
        if (draft.content != syncedDraftContent) {
            contentState = draft.content
            syncedDraftContent = draft.content
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val text = stream.bufferedReader().readText()
                        contentState = text
                    }
                } catch (_: Exception) { }
            }
        }
    }

    val lineCount = remember(contentState) {
        if (contentState.isBlank()) 0
        else contentState.split("\n").filter { it.isNotBlank() }.size
    }

    fun emptyAsNull(s: String): String? = s.trim().ifEmpty { null }

    fun getCurrentResource() = draft.copy(
        id = id.trim(),
        name = emptyAsNull(name),
        description = emptyAsNull(description),
        author = emptyAsNull(author),
        version = emptyAsNull(version),
        content = contentState
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) stringResource(R.string.dictionary_new_resource) else stringResource(R.string.dictionary_edit_resource)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text(stringResource(R.string.dictionary_id_required)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dictionary_label_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.dictionary_description)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text(stringResource(R.string.dictionary_developer)) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it },
                        label = { Text(stringResource(R.string.dictionary_version)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.let {
                                    contentState = it.toString()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dictionary_clipboard))
                    }
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.dictionary_import_file))
                    }
                }
                OutlinedButton(
                    onClick = { onContentEdit(getCurrentResource()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Icon(Icons.Default.EditNote, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dictionary_text_editor))
                }
                Text(
                    text = stringResource(R.string.dictionary_data_count_tip, lineCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        onSave(getCurrentResource())
                    } catch (e: Exception) {
                        app.alert(context.getString(R.string.dictionary_save_failed), e.message.toString())
                    }
                },
                enabled = id.isNotBlank()
            ) {
                Text(stringResource(R.string.dictionary_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dictionary_btn_cancel)) }
        }
    )
}

@Composable
fun ResourceOptionItem(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = description, style = MaterialTheme.typography.bodySmall)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
