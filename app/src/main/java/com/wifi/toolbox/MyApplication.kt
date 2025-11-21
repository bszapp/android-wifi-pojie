package com.wifi.toolbox

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wifi.toolbox.structs.PojieConfig
import com.wifi.toolbox.ui.items.LogState

class MyApplication : Application() {

    var pojieConfig by mutableStateOf(PojieConfig())
        private set // We only allow internal modification

    var logState by mutableStateOf(LogState())
        private set

    fun updatePojieConfig(newConfig: PojieConfig) {
        pojieConfig = newConfig
    }
}