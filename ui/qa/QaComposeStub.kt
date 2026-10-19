// QA 无头渲染专用 compose 桩：desktop 产物缺失的 Android 专属静态 Local。
// LocalConfiguration / LocalContext 仅存在于 compose-ui-android 变体。
// 默认值与 412x915 手机窗口匹配；渲染器会按变体尺寸显式提供。

package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf

val LocalConfiguration = compositionLocalOf<Configuration> {
    Configuration().apply {
        screenWidthDp = 412
        screenHeightDp = 915
        screenLayout = Configuration.SCREENLAYOUT_SIZE_NORMAL or Configuration.SCREENLAYOUT_LONG_NO
        densityDpi = 420
        fontScale = 1f
    }
}

val LocalContext = compositionLocalOf<Context> { Context() }
