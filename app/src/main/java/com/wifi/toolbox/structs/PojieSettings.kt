package com.wifi.toolbox.structs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieSettings(
    val readLogMode: Int = READ_LOG_MODE_DEFAULT,
    val connectMode: Int = CONNECT_MODE_DEFAULT,
    val manageSavedMode: Int = MANAGE_SAVED_MODE_DEFAULT,
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

        const val MANAGE_SAVED_MODE_KEY = "manage_saved_mode"
        const val MANAGE_SAVED_MODE_DEFAULT = 0

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
    }
}
