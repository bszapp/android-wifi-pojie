package io.github.bszapp.wifitoolbox.service

import android.content.Context
import androidx.annotation.Keep

/**
 * Shizuku UserService 绑定入口。
 *
 * 这里不再硬编码启动模式、App UID 或版本信息。
 * Shizuku 只负责创建 Binder；App 拿到 Binder 后必须先调用 initializeStartupInfo()，
 * 由启动服务的 App 进程把 mode / uid / version 明确告诉服务。
 */
@Keep
@Suppress("UNUSED_PARAMETER")
class BoundMainService(context: Context) : MainService()
