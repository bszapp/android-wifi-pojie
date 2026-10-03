package io.github.bszapp.wifitoolbox.uidefault.dictionary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DictionaryState(
    val context: Context,
    val scope: CoroutineScope,
    val editor: DictionaryEditorState,
    val app: DictionaryAlerts,
    val defaultScriptContent: String,
) {
    var showAddDialog by mutableStateOf(false)
    var resources by mutableStateOf<List<DictionaryResource>>(emptyList())
    var refreshKey by mutableIntStateOf(0)
    var selectedResource by mutableStateOf<DictionaryResource?>(null)
    var showDetailSheet by mutableStateOf(false)
    var draftState by mutableStateOf<DictionaryResource?>(null)
    var showEditDialog by mutableStateOf(false)
    var selectedFabOption by mutableStateOf<Int?>(null)
    var currentEditingId by mutableStateOf<String?>(null)
    var originalIdForEdit by mutableStateOf<String?>(null)

    fun loadResources() {
        scope.launch(Dispatchers.IO) {
            resources = DictionaryStore.getAll(context)
        }
    }

    fun deleteResource(id: String) {
        scope.launch(Dispatchers.IO) {
            DictionaryStore.delete(context, id)
            refreshKey++
            withContext(Dispatchers.Main) {
                showDetailSheet = false
                selectedResource = null
            }
        }
    }

    fun handleImport(uri: Uri?) {
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: return@launch

                    var fileName = ""
                    context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst()) fileName = cursor.getString(nameIndex)
                    }
                    val lowerName = fileName.lowercase(Locale.ROOT)

                    val newRes = when {
                        lowerName.endsWith(".js") -> DictionaryResource.parseScript(context, content)
                        lowerName.endsWith(".json") -> DictionaryResource.parseJSON(context, content)
                        lowerName.endsWith(".txt") -> {
                            DictionaryResource(
                                id = DictionaryStore.randomID(),
                                name = fileName.removeSuffix(".txt"),
                                description = null,
                                type = 0,
                                content = content,
                                author = null,
                                version = null
                            )
                        }
                        else -> throw Exception(context.getString(R.string.dictionary_unsupported_file_format))
                    }

                    DictionaryStore.testExists(context, newRes, null)
                    DictionaryStore.save(context, newRes)

                    refreshKey++
                    withContext(Dispatchers.Main) { showAddDialog = false }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        app.alert(context.getString(R.string.dictionary_import_failed), e.message.toString())
                    }
                }
            }
        }
    }

    fun openEditorForScript(res: DictionaryResource, isNew: Boolean) {
        currentEditingId = res.id
        editor.open(res.content, "js") { newContent ->
            val oldId = currentEditingId ?: res.id
            val newRes = withContext(Dispatchers.IO) {
                val parsed = DictionaryResource.parseScript(context, newContent)
                DictionaryStore.testExists(context, parsed, oldId)
                if (isNew && oldId == res.id) {
                    DictionaryStore.save(context, parsed, oldId)
                } else {
                    if (oldId != parsed.id) {
                        DictionaryStore.update(context, oldId, parsed)
                    } else {
                        DictionaryStore.save(context, parsed, oldId)
                    }
                }
                parsed
            }
            currentEditingId = newRes.id
            refreshKey++
        }
    }
}
