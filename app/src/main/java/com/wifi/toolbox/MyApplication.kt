package com.wifi.toolbox

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wifi.toolbox.structs.PojieConfig
import com.wifi.toolbox.ui.items.LogState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertDialogData(
    val title: String,
    val text: String
)

class MyApplication : Application() {

    var pojieConfig by mutableStateOf(PojieConfig())
        private set

    var logState by mutableStateOf(LogState())
        private set

    private val _alertDialogState = MutableStateFlow<AlertDialogData?>(null)
    val alertDialogState = _alertDialogState.asStateFlow()

    fun updatePojieConfig(newConfig: PojieConfig) {
        pojieConfig = newConfig
    }

    fun alert(title: String, text: String) {
        _alertDialogState.value = AlertDialogData(title, text)
    }

    fun dismissAlert() {
        _alertDialogState.value = null
    }
}