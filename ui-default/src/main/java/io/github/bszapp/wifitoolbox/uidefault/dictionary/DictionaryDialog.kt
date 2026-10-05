package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun AddResourceDialog(
    isVisible: Boolean,
    selectedOption: Int?,
    onOptionSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onContinue: () -> Unit
) {
    OverlayDialog(
        show = isVisible,
        title = stringResource(R.string.dictionary_add_resource),
        onDismissRequest = onDismiss,
        content = {
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
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    text = stringResource(R.string.dictionary_import_external),
                    onClick = onImport,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = stringResource(R.string.dictionary_btn_cancel),
                        onClick = onDismiss,
                    )
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

    OverlayDialog(
        show = true,
        title = if (isNew) stringResource(R.string.dictionary_new_resource) else stringResource(R.string.dictionary_edit_resource),
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TextField(
                    value = id,
                    onValueChange = { id = it },
                    label = stringResource(R.string.dictionary_id_required),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.dictionary_label_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.dictionary_description),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = author,
                        onValueChange = { author = it },
                        label = stringResource(R.string.dictionary_developer),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextField(
                        value = version,
                        onValueChange = { version = it },
                        label = stringResource(R.string.dictionary_version),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.dictionary_clipboard),
                        onClick = {
                            scope.launch {
                                clipboardManager.getClipEntry()?.clipData?.getItemAt(0)?.text?.let {
                                    contentState = it.toString()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.dictionary_import_file),
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    text = stringResource(R.string.dictionary_text_editor),
                    onClick = { onContentEdit(getCurrentResource()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Text(
                    text = stringResource(R.string.dictionary_data_count_tip, lineCount),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.dictionary_btn_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                Button(
                    onClick = {
                        try {
                            onSave(getCurrentResource())
                        } catch (e: Exception) {
                            app.alert(context.getString(R.string.dictionary_save_failed), e.message.toString())
                        }
                    },
                    enabled = id.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dictionary_save))
                }
            }
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
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MiuixTheme.textStyles.headline2)
                Text(text = description, style = MiuixTheme.textStyles.footnote1)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
