package io.github.bszapp.wifitoolbox.ui.dictionary

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        title = DictionaryStr.addResource,
        onDismissRequest = onDismiss,
        content = {
            Column(Modifier.selectableGroup()) {
                ResourceOptionItem(
                    icon = Icons.Default.Description,
                    title = DictionaryStr.resourceNormal,
                    description = DictionaryStr.resourceNormalDesc,
                    selected = selectedOption == 0,
                    onClick = { onOptionSelect(0) }
                )
                ResourceOptionItem(
                    icon = Icons.Default.Code,
                    title = DictionaryStr.tagScript,
                    description = DictionaryStr.resourceScriptDesc,
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
                    text = DictionaryStr.importExternal,
                    onClick = onImport,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = DictionaryStr.btnCancel,
                        onClick = onDismiss,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = selectedOption != null,
                        onClick = onContinue
                    ) {
                        Text(DictionaryStr.continueText)
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
        title = if (isNew) DictionaryStr.newResource else DictionaryStr.editResource,
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
                    label = DictionaryStr.idRequired,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = DictionaryStr.labelName,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = DictionaryStr.description,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = author,
                        onValueChange = { author = it },
                        label = DictionaryStr.developer,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextField(
                        value = version,
                        onValueChange = { version = it },
                        label = DictionaryStr.version,
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
                        text = DictionaryStr.clipboard,
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
                        text = DictionaryStr.importFile,
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    text = DictionaryStr.textEditor,
                    onClick = { onContentEdit(getCurrentResource()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Text(
                    text = DictionaryStr.dataCountTip(lineCount),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = DictionaryStr.btnCancel,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                Button(
                    onClick = {
                        try {
                            onSave(getCurrentResource())
                        } catch (e: Exception) {
                            app.alert(DictionaryStr.saveFailed, e.message.toString())
                        }
                    },
                    enabled = id.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(DictionaryStr.save)
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
