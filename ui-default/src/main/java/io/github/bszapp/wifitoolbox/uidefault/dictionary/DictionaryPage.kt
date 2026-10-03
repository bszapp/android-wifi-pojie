package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.dictionary.*
import io.github.bszapp.wifitoolbox.uidefault.dictionary.*
import io.github.bszapp.wifitoolbox.uidefault.dictionary.*
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryPage(state: DictionaryState) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val uiScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> state.handleImport(uri) }

    LaunchedEffect(state.refreshKey) {
        state.loadResources()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                state.selectedFabOption = null
                state.showAddDialog = true
            }) {
                Icon(Icons.Filled.Add, null)
            }
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        if (state.resources.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dictionary_resources_nothing),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(
                    items = state.resources,
                    key = { it.id }
                ) { res ->
                    DictionaryResourceItem(
                        modifier = Modifier
                            .animateItem()
                            .clickable {
                                state.selectedResource = res
                                state.showDetailSheet = true
                            },
                        res = res
                    )
                }
            }
        }

        ResourceDetailSheet(
            isVisible = state.showDetailSheet,
            resource = state.selectedResource,
            sheetState = sheetState,
            onDismiss = {
                state.showDetailSheet = false
                state.selectedResource = null
            },
            onEdit = { currentRes ->
                uiScope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) {
                        state.showDetailSheet = false
                        if (currentRes.type == 1) {
                            state.openEditorForScript(currentRes, false)
                            navigator.push(Route.DictionaryEditor)
                        } else {
                            state.draftState = currentRes
                            state.originalIdForEdit = currentRes.id
                            state.showEditDialog = true
                        }
                    }
                }
            },
            onDelete = { state.deleteResource(it) }
        )

        val currentDraft = state.draftState
        if (state.showEditDialog && currentDraft != null) {
            EditResourceDialog(
                draft = currentDraft,
                isNew = state.originalIdForEdit == null,
                onDismiss = { state.showEditDialog = false },
                onContentEdit = { updatedDraft ->
                    state.draftState = updatedDraft
                    state.editor.open(updatedDraft.content) { newContent ->
                        val draft = checkNotNull(state.draftState) { "编辑器的资源草稿已丢失" }
                        state.draftState = draft.copy(content = newContent)
                    }
                    navigator.push(Route.DictionaryEditor)
                },
                onSave = { finalDraft ->
                    val originalId = state.originalIdForEdit
                    DictionaryStore.testExists(state.context, finalDraft, originalId)
                    state.scope.launch(Dispatchers.IO) {
                        try {
                            if (originalId != null) {
                                DictionaryStore.update(
                                    state.context,
                                    originalId,
                                    finalDraft
                                )
                            } else {
                                DictionaryStore.save(state.context, finalDraft)
                            }
                            state.refreshKey++
                            withContext(Dispatchers.Main) {
                                state.showEditDialog = false
                            }
                        } catch (e: Exception) {
                            state.app.alert(context.getString(R.string.dictionary_save_failed), e.message.toString())
                        }
                    }
                }
            )
        }

        AddResourceDialog(
            isVisible = state.showAddDialog,
            selectedOption = state.selectedFabOption,
            onOptionSelect = { state.selectedFabOption = it },
            onDismiss = { state.showAddDialog = false },
            onImport = {
                importLauncher.launch(
                    arrayOf(
                        "application/javascript",
                        "application/json",
                        "text/plain"
                    )
                )
            },
            onContinue = {
                state.showAddDialog = false
                state.currentEditingId = null
                if (state.selectedFabOption == 0) {
                    state.originalIdForEdit = null
                    state.draftState = DictionaryResource(
                        id = DictionaryStore.randomID(),
                        name = "",
                        description = "",
                        type = 0,
                        content = "",
                        author = "",
                        version = ""
                    )
                    state.showEditDialog = true
                } else {
                    val tempId = DictionaryStore.randomID()
                    val content = state.defaultScriptContent.replace("{ID}", tempId)
                    state.openEditorForScript(DictionaryResource.parseScript(context, content), true)
                    navigator.push(Route.DictionaryEditor)
                }
            }
        )
    }
}
