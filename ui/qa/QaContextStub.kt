// QA 无头渲染专用 android.content 桩：LocalContext 的提供值来源。
// qa 源里的 Context 会遮蔽 android.jar 的同名类（编译优先取源），所以生产源以平台方式
// 访问的属性（filesDir/assets）也要在此出现，保持编译语义一致；值只在渲染时被惰性构造。

package android.content

import android.content.res.AssetManager

open class Context {
    val filesDir: java.io.File by lazy { java.io.File(System.getProperty("java.io.tmpdir"), "qa-files") }
    val assets: AssetManager by lazy { AssetManager() }
}
