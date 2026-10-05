package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.navigation.Route
import io.github.bszapp.wifitoolbox.uidefault.theme.isInDarkTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
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
                DictionaryEditorPage(state.editor, dark)
            }
        }
    }
}

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
        color = MiuixTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = stringResource(R.string.dictionary_code_editor),
                navigationIcon = {
                    IconButton(onClick = {
                        state.handleBackPress()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    TextButton(
                        text = stringResource(R.string.dictionary_save),
                        onClick = { state.save() },
                    )
                },
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

    OverlayDialog(
        show = state.showExitConfirmDialog,
        title = stringResource(R.string.dictionary_default_alert_title),
        summary = stringResource(R.string.dictionary_dialog_exit_confirm_text),
        onDismissRequest = { state.showExitConfirmDialog = false },
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.dictionary_btn_save_not),
                    onClick = { state.close() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.dictionary_save),
                    onClick = { state.save(closeAfterSave = true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
    val errorMessage = state.errorMessage
    OverlayDialog(
        show = errorMessage != null,
        title = stringResource(R.string.dictionary_save_failed),
        summary = errorMessage.orEmpty(),
        onDismissRequest = { state.errorMessage = null },
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    text = stringResource(R.string.dictionary_btn_ok),
                    onClick = {
                        state.errorMessage = null
                        state.showExitConfirmDialog = false
                    },
                    modifier = Modifier.width(120.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
