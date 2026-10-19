// QA 无头渲染专用 android.graphics 桩：
// RuntimeShader 只在交互高亮路径构造（渲染场景无按压），空体即可；
// ShaderBrush 的 android.graphics.Shader 重载只在 compose-ui-android 变体存在，桩补上并用透明刷兜底。

package androidx.compose.ui.graphics

import androidx.compose.ui.graphics.Brush

fun ShaderBrush(shader: android.graphics.RuntimeShader) =
    Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
