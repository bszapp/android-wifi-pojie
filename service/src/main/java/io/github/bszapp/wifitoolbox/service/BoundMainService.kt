package io.github.bszapp.wifitoolbox.service

import androidx.annotation.Keep

/**
 * UserService 绑定入口。进程名仍由启动参数固定为 :service。
 */
@Keep
class BoundMainService : MainService(
    startupMode = "SHIZUKU"
)
