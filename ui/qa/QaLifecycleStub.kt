// QA 无头渲染专用 lifecycle 桩：AndroidViewModel 在 lifecycle 的 KMP desktop 变体里不存在，
// qa 单体编译 DefaultViewModel/CaptureViewModel 只需要这个类型存在。

package androidx.lifecycle

open class AndroidViewModel(application: android.app.Application) : ViewModel() {
    @get:Suppress("unused")
    val application: android.app.Application = application
}
