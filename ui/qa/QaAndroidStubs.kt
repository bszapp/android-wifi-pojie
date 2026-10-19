// QA 无头渲染专用的 android 框架类型最小桩。
// 只覆盖渲染路径实际加载/验证的成员：列出列表页 UI 直接消费的字段、构造与常量。
// （桩源与渲染器一起编译进 ui/build/ui-qa/classes，随 JVM 类路径优先命中。）

package android.os

internal fun qaUnsupported(): Nothing = throw IllegalStateException("Render QA 桩未实现")

interface Parcelable {
    fun describeContents(): Int
    fun writeToParcel(dest: Parcel, flags: Int)

    interface Creator<T> {
        fun createFromParcel(source: Parcel): T
        fun newArray(size: Int): Array<T?>
    }
}

class Parcel private constructor() {
    fun readByte(): Byte = 0
    fun readInt(): Int = 0
    fun readLong(): Long = 0L
    fun writeInt(value: Int): Unit = qaUnsupported()
    fun writeLong(value: Long): Unit = qaUnsupported()
}

class Build private constructor() {
    object VERSION_CODES {
        const val Q = 29
        const val R = 30
        const val S = 31
        const val S_V2 = 32
        const val TIRAMISU = 33
        const val UPSIDE_DOWN_CAKE = 34
    }

    object VERSION {
        const val SDK_INT = 34
    }

    companion object {
        const val UNKNOWN: String = "unknown"
        const val DEVICE = "qa-device"
        const val MODEL = "QA Thin Phone"
        const val HARDWARE = "qa-hardware"
        const val SOC_MODEL = "QA SoC"
        const val SOC_MANUFACTURER = "QA Semiconductor"
        const val BRAND = "qa"
        const val MANUFACTURER = "qa"
        const val PRODUCT = "qa_product"
    }
}
