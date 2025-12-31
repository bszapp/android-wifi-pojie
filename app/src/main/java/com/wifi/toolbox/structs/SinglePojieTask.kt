package com.wifi.toolbox.structs

data class SinglePojieTask(
    val ssid: String,
    val password: String
) {
    companion object {
        /** 连接成功 */
        const val RESULT_SUCCESS = 0

        /** 达到失败标志 */
        const val RESULT_FAILED = 1

        /** 执行超时（路由器无响应） */
        const val RESULT_TIMEOUT = 2

        /** 路由器拒绝接入等 */
        const val RESULT_ERROR_TRANSIENT = -1

        /** 未授权、其他原因 */
        const val RESULT_ERROR = -2
    }
}