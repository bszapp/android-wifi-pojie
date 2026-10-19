package io.github.bszapp.wifitoolbox.ui.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

/** Activity-owned resource/editor state, retained across rotation and service session changes. */
class DictionaryViewModel(application: Application) : AndroidViewModel(application) {
    val state = DictionaryState(
        context = application,
        scope = viewModelScope,
        editor = DictionaryEditorState(viewModelScope),
        app = DictionaryAlerts(),
        defaultScriptContent = DictionaryStr.defaultScriptContent,
    )
}
