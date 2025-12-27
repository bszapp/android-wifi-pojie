package com.wifi.toolbox.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LogState {
    val logs = androidx.compose.runtime.mutableStateListOf<String>()
    var wordWrap by mutableStateOf(false)
    var autoScroll by mutableStateOf(true)

    fun addLog(log: String) {
        logs.add(log)
    }

    fun clear() {
        logs.clear()
    }

    fun setLine(log: String) {
        if (logs.isNotEmpty()) {
            logs[logs.size - 1] = log
        } else {
            addLog(log)
        }
    }
}