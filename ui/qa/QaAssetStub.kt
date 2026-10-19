// QA 无头渲染专用 android.content.res 桩：Context.assets 的类型载体 + Configuration。

package android.content.res

class AssetManager {
    fun list(name: String): Array<String> = emptyArray()
    fun open(name: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
}

class Configuration {
    var fontScale: Float = 1f
    var screenWidthDp: Int = 0
    var screenHeightDp: Int = 0
    var smallestScreenWidthDp: Int = 0
    var screenLayout: Int = 0
    var densityDpi: Int = 0

    companion object {
        const val SCREENLAYOUT_SIZE_UNSPECIFIED = 0
        const val SCREENLAYOUT_SIZE_SMALL = 1
        const val SCREENLAYOUT_SIZE_NORMAL = 2
        const val SCREENLAYOUT_SIZE_LARGE = 3
        const val SCREENLAYOUT_SIZE_XLARGE = 4
        const val SCREENLAYOUT_SIZE_MASK = 15
        const val SCREENLAYOUT_LONG_NO = 16
        const val SCREENLAYOUT_LONG_YES = 32
        const val SCREENLAYOUT_LONG_MASK = 48
    }
}
