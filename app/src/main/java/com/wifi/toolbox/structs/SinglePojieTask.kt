package com.wifi.toolbox.structs

data class SinglePojieTask(
    val ssid: String,
    val password: String
) {
    companion object {
        const val RESULT_SUCCESS = 0
        const val RESULT_FAILED = 1
        const val RESULT_TIMEOUT = 2
        const val RESULT_ERROR = -1
    }
}