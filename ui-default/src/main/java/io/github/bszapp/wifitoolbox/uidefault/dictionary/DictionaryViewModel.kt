package io.github.bszapp.wifitoolbox.uidefault.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.bszapp.wifitoolbox.uidefault.R

/** Activity-owned resource/editor state, retained across rotation and service session changes. */
class DictionaryViewModel(application: Application) : AndroidViewModel(application) {
    val state = DictionaryState(
        context = application,
        scope = viewModelScope,
        editor = DictionaryEditorState(viewModelScope),
        app = DictionaryAlerts(),
        defaultScriptContent = application.getString(R.string.dictionary_default_script_content),
    )
}
