package com.wifi.toolbox.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import com.wifi.toolbox.structs.PojieSettings

object PojieSettingsManager {
    private const val PREFS_NAME = "settings_pojie"

    fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(prefs: SharedPreferences): PojieSettings {
        return PojieSettings(
            readLogMode = prefs.getInt(PojieSettings.READ_LOG_MODE_KEY, PojieSettings.READ_LOG_MODE_DEFAULT),
            connectMode = prefs.getInt(PojieSettings.CONNECT_MODE_KEY, PojieSettings.CONNECT_MODE_DEFAULT),
            manageSavedMode = prefs.getInt(PojieSettings.MANAGE_SAVED_MODE_KEY, PojieSettings.MANAGE_SAVED_MODE_DEFAULT),
            scanMode = prefs.getInt(PojieSettings.SCAN_MODE_KEY, PojieSettings.SCAN_MODE_DEFAULT),
            allowScanUseCommand = prefs.getBoolean(PojieSettings.ALLOW_SCAN_USE_COMMAND_KEY, PojieSettings.ALLOW_SCAN_USE_COMMAND_DEFAULT),
            enableMode = prefs.getInt(PojieSettings.ENABLE_MODE_KEY, PojieSettings.ENABLE_MODE_DEFAULT),
            screenAlwaysOn = prefs.getBoolean(PojieSettings.SCREEN_ALWAYS_ON_KEY, PojieSettings.SCREEN_ALWAYS_ON_DEFAULT),
            showRunningNotification = prefs.getBoolean(PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY, PojieSettings.SHOW_RUNNING_NOTIFICATION_DEFAULT),
            exitToPictureInPicture = prefs.getBoolean(PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY, PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_DEFAULT),
            commandMethod = prefs.getInt(PojieSettings.COMMAND_METHOD_KEY, PojieSettings.COMMAND_METHOD_DEFAULT)
        )
    }

    fun save(prefs: SharedPreferences, s: PojieSettings) {
        prefs.edit {
            putInt(PojieSettings.READ_LOG_MODE_KEY, s.readLogMode)
            putInt(PojieSettings.CONNECT_MODE_KEY, s.connectMode)
            putInt(PojieSettings.MANAGE_SAVED_MODE_KEY, s.manageSavedMode)
            putInt(PojieSettings.SCAN_MODE_KEY, s.scanMode)
            putBoolean(PojieSettings.ALLOW_SCAN_USE_COMMAND_KEY, s.allowScanUseCommand)
            putInt(PojieSettings.ENABLE_MODE_KEY, s.enableMode)
            putBoolean(PojieSettings.SCREEN_ALWAYS_ON_KEY, s.screenAlwaysOn)
            putBoolean(PojieSettings.SHOW_RUNNING_NOTIFICATION_KEY, s.showRunningNotification)
            putBoolean(PojieSettings.EXIT_TO_PICTURE_IN_PICTURE_KEY, s.exitToPictureInPicture)
            putInt(PojieSettings.COMMAND_METHOD_KEY, s.commandMethod)
        }
    }
}

@Composable
fun rememberPojieSettings(context: Context): MutableState<PojieSettings> {
    val prefs = remember { PojieSettingsManager.getPrefs(context) }
    val state = remember { mutableStateOf(PojieSettingsManager.read(prefs)) }

    return remember {
        object : MutableState<PojieSettings> {
            override var value: PojieSettings
                get() = state.value
                set(newValue) {
                    state.value = newValue
                    PojieSettingsManager.save(prefs, newValue)
                }
            override fun component1() = value
            override fun component2(): (PojieSettings) -> Unit = { value = it }
        }
    }
}