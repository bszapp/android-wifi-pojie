package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.theme.isInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.CodeEditorController
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberSaveableCodeEditorController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class DictionaryEditorState(private val scope: CoroutineScope) {
    var initialContent by mutableStateOf("")
        private set
    var language by mutableStateOf("text")
        private set
    var showExitConfirmDialog by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var controller: CodeEditorController? = null
    private var originalContent = ""
    private var onSaveAction: (suspend (String) -> Unit)? = null
    private val closeRequestChannel = Channel<Unit>(Channel.BUFFERED)
    val closeRequests = closeRequestChannel.receiveAsFlow()

    fun open(content: String, language: String = "text", onSave: suspend (String) -> Unit) {
        initialContent = content
        originalContent = content
        this.language = language
        onSaveAction = onSave
        controller = null
        showExitConfirmDialog = false
        errorMessage = null
    }

    fun hasUnsavedChanges(editor: CodeEditorController? = controller): Boolean {
        val text = editor?.let { it.getText(it.lineEnding) } ?: initialContent
        return text != originalContent
    }

    fun handleBackPress() {
        if (hasUnsavedChanges()) {
            showExitConfirmDialog = true
        } else {
            close()
        }
    }

    fun save(closeAfterSave: Boolean = false) {
        val editor = controller ?: return
        val action = onSaveAction
        if (action == null) {
            errorMessage = "编辑器的保存目标已丢失，内容尚未保存"
            return
        }
        val version = editor.documentVersion
        val text = editor.getText(editor.lineEnding)
        scope.launch {
            try {
                action(text)
                originalContent = text
                editor.markSaved(version)
                if (closeAfterSave) close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            }
        }
    }

    fun close() {
        showExitConfirmDialog = false
        closeRequestChannel.trySend(Unit)
    }
}

@Composable
fun DictionaryEditorScreen(state: DictionaryState) {
    val dark = isInDarkTheme()
    DictionaryMaterialTheme(state.app) {
        MiuixTheme {
            val systemOverscroll = rememberPlatformOverscrollFactory()
            // Override Miuix overscroll with the platform's stretch/glow effect.
            CompositionLocalProvider(LocalOverscrollFactory provides systemOverscroll) {
                MaterialTheme {
                    DictionaryEditorPage(state.editor, dark)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryEditorPage(state: DictionaryEditorState, useDarkTheme: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    val navigator = LocalNavigator.current
    // The ViewModel retains the actual controller, including large documents and
    // selection, across rotation. Scripta's public factory creates the first one.
    val controller = state.controller ?: rememberSaveableCodeEditorController(state.initialContent)
    SideEffect {
        state.controller = controller
    }
    LaunchedEffect(state, navigator, keyboard) {
        state.closeRequests.collect {
            if (navigator.current() is Route.DictionaryEditor) {
                keyboard?.hide()
                navigator.pop()
            }
        }
    }
    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = navEventState,
        // An unchanged document returns through NavDisplay's normal back animation.
        isBackEnabled = navigator.current() is Route.DictionaryEditor &&
            controller.isModified && state.hasUnsavedChanges(controller),
        onBackCompleted = { state.handleBackPress() },
    )
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.dictionary_code_editor)) },
                navigationIcon = {
                    IconButton(onClick = {
                        state.handleBackPress()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    TextButton(onClick = { state.save() }) {
                        Text(stringResource(R.string.dictionary_save))
                    }
                }
            )
            CodeEditor(
                controller = controller,
                language = if (state.language == "js") {
                    EditorLanguage.JavaScript
                } else {
                    EditorLanguage.PlainText
                },
                colors = if (useDarkTheme) EditorColors.Default else EditorColors.Light,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (state.showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { state.showExitConfirmDialog = false },
            title = { Text(stringResource(R.string.dictionary_default_alert_title)) },
            text = { Text(stringResource(R.string.dictionary_dialog_exit_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { state.save(closeAfterSave = true) }) {
                    Text(stringResource(R.string.dictionary_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { state.close() }) {
                    Text(stringResource(R.string.dictionary_btn_save_not))
                }
            },
        )
    }
    if (state.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { state.errorMessage = null },
            title = { Text(stringResource(R.string.dictionary_save_failed)) },
            text = { Text(state.errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    state.errorMessage = null
                    state.showExitConfirmDialog = false
                }) {
                    Text(stringResource(R.string.dictionary_btn_ok))
                }
            },
        )
    }
}
