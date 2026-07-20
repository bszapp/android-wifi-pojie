package io.github.bszapp.wifitoolbox.service

import android.content.Context
import androidx.annotation.Keep

/**
 * Shizuku UserService 绑定入口。
 */
@Keep
@Suppress("UNUSED_PARAMETER")
class BoundMainService(context: Context) : MainService(serviceContext = context)
