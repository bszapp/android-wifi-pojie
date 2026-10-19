package io.github.bszapp.wifitoolbox.ui.dictionary

/**
 * 字典页词条，从 res/values/dictionary_strings.xml 内嵌而来（res 已删除）。
 * 纯字符串常量，无 Android 资源依赖；带格式占位的词条用函数表达。
 */
internal object DictionaryStr {
    val edit = "编辑"
    val resourceNormalDesc = "通用格式，一行一个密码"
    val overwriteResource = "覆写资源"
    val save = "保存"
    val btnSaveNot = "不保存"
    val description = "描述"
    val labelName = "名称"
    val resourcesNothing = "¯\\_(ツ)_/¯\n暂无资源，点击 + 号添加"
    val tagScript = "脚本"
    val resourceScriptDesc = "使用JS脚本动态生成密码本"
    val errorJsonIdMissing = "信息缺少id"
    val btnOk = "确定"
    val errorScriptHeaderNotFound = "未找到脚本声明头"
    val importExternal = "导入"
    val defaultAlertTitle = "提示"

    fun dataCountTip(count: Int) = "当前包含 $count 条数据"

    val editResource = "编辑资源"
    val editOverwrite = "编辑覆写"

    fun errorIdExists(id: String) = "ID“$id”已经存在"

    val unsupportedFileFormat = "不支持的文件格式"
    val importFile = "导入文件"
    val builtinResource = "内置资源"
    val localPath = "本地路径"

    /** 新建脚本资源时的模板（原 translatable="false" 词条）。 */
    val defaultScriptContent =
        """// ==ToolboxScript==
// @id {ID}
// @name 脚本名称
// @description 脚本描述内容
// @author 作者
// @version 1.0.0
// ==/ToolboxScript==

console.log("先加一个密码…") //输出使用console.log
task.addItem("12345678") //添加使用task.addItem
console.log("等一秒…")
await wait(1000); //代码默认嵌套在async function内，可以使用await
console.log("再来一个！");
task.addItem("88888888")

try {
	console.log("请求网络试试…");
	const response = await fetch('https://uapis.cn/api/v1/network/myip'); //可以使用WebView自带的工具，但是有cors限制
	const data = await response.json();
	console.log("本机IP: " + data.ip);
	console.log("归属地: " + data.region);
} catch (err) {
	console.log("请求失败: " + err.message);
}
console.log("完成");"""

    val delete = "删除"
    val tagBuiltin = "内置"
    val restoreBuiltin = "还原内置"
    val addResource = "添加资源"
    val btnCancel = "取消"
    val textEditor = "文本编辑器"
    val version = "版本"
    val newResource = "新建资源"
    val errorIdFormat = "id必须匹配/^[a-zA-Z][a-zA-Z0-9._-]+\$/"
    val idRequired = "ID (必填)"
    val none = "-"
    val importFailed = "导入失败"
    val errorJsonContentMissing = "信息缺少content"
    val resourceDetails = "资源详情"
    val id = "ID"
    val dialogExitConfirmText = "文档已修改，是否保存？"
    val parseFailed = "解析失败"
    val errorScriptIdNotFound = "脚本声明中未找到id属性"
    val continueText = "继续"
    val saveFailed = "保存失败"
    val codeEditor = "文本编辑器"
    val developer = "开发者"
    val clipboard = "剪贴板"
    val resourceNormal = "普通"
    val tagOverwrite = "覆写内置"
    val noDescription = "无描述"
}
