package com.wifi.toolbox.structs

import android.content.SharedPreferences
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieSettings(
    val readLogMode: Int = READ_LOG_MODE_DEFAULT,
    val connectMode: Int = CONNECT_MODE_DEFAULT,
    val scanMode: Int = SCAN_MODE_DEFAULT,
    val allowScanUseCommand: Boolean = ALLOW_SCAN_USE_COMMAND_DEFAULT,
    val enableMode: Int = ENABLE_MODE_DEFAULT,
    val screenAlwaysOn: Boolean = SCREEN_ALWAYS_ON_DEFAULT,
    val showRunningNotification: Boolean = SHOW_RUNNING_NOTIFICATION_DEFAULT,
    val exitToPictureInPicture: Boolean = EXIT_TO_PICTURE_IN_PICTURE_DEFAULT,
    val commandMethod: Int = COMMAND_METHOD_DEFAULT
) : Parcelable {
    companion object {
        const val READ_LOG_MODE_KEY = "read_log_mode"
        const val READ_LOG_MODE_DEFAULT = 0

        const val CONNECT_MODE_KEY = "connect_mode"
        const val CONNECT_MODE_DEFAULT = 0

        const val SCAN_MODE_KEY = "scan_mode"
        const val SCAN_MODE_DEFAULT = 0

        const val ENABLE_MODE_KEY = "enable_mode"
        const val ENABLE_MODE_DEFAULT = 0

        const val SCREEN_ALWAYS_ON_KEY = "screen_always_on"
        const val SCREEN_ALWAYS_ON_DEFAULT = true

        const val ALLOW_SCAN_USE_COMMAND_KEY = "allow_scan_use_command"
        const val ALLOW_SCAN_USE_COMMAND_DEFAULT = true

        const val SHOW_RUNNING_NOTIFICATION_KEY = "show_running_notification"
        const val SHOW_RUNNING_NOTIFICATION_DEFAULT = true

        const val EXIT_TO_PICTURE_IN_PICTURE_KEY = "exit_to_picture_in_picture"
        const val EXIT_TO_PICTURE_IN_PICTURE_DEFAULT = false

        const val COMMAND_METHOD_KEY = "command_method"
        const val COMMAND_METHOD_DEFAULT = 0


        fun from(prefs: SharedPreferences): PojieSettings {
            return PojieSettings(
                readLogMode = prefs.getInt(READ_LOG_MODE_KEY, READ_LOG_MODE_DEFAULT),
                connectMode = prefs.getInt(CONNECT_MODE_KEY, CONNECT_MODE_DEFAULT),
                scanMode = prefs.getInt(SCAN_MODE_KEY, SCAN_MODE_DEFAULT),
                allowScanUseCommand = prefs.getBoolean(ALLOW_SCAN_USE_COMMAND_KEY, ALLOW_SCAN_USE_COMMAND_DEFAULT),
                enableMode = prefs.getInt(ENABLE_MODE_KEY, ENABLE_MODE_DEFAULT),
                screenAlwaysOn = prefs.getBoolean(SCREEN_ALWAYS_ON_KEY, SCREEN_ALWAYS_ON_DEFAULT),
                showRunningNotification = prefs.getBoolean(SHOW_RUNNING_NOTIFICATION_KEY, SHOW_RUNNING_NOTIFICATION_DEFAULT),
                exitToPictureInPicture = prefs.getBoolean(EXIT_TO_PICTURE_IN_PICTURE_KEY, EXIT_TO_PICTURE_IN_PICTURE_DEFAULT),
                commandMethod = prefs.getInt(COMMAND_METHOD_KEY, COMMAND_METHOD_DEFAULT)
            )
        }
    }
}