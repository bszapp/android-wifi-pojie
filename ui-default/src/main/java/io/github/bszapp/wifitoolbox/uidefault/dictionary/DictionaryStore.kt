package io.github.bszapp.wifitoolbox.uidefault.dictionary

import android.content.Context
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryResource
import org.json.JSONObject
import java.io.File
import java.util.UUID

object DictionaryStore {
    private const val DIR_NAME = "dictionary"

    private fun getDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAll(context: Context): List<DictionaryResource> {
        val dir = getDir(context)
        val files = dir.listFiles()?.filter { it.extension == "json" || it.extension == "js" }
            ?: emptyList()
        val fileIds = files.map { it.nameWithoutExtension }.toSet()

        val builtinNames = context.assets.list(DIR_NAME)
            ?.filter { it.endsWith(".json") || it.endsWith(".js") } ?: emptyList()
        val builtinIds = builtinNames.map { it.substringBeforeLast(".") }.toSet()

        val list = mutableListOf<DictionaryResource>()

        // 处理文件系统中的资源
        fileIds.forEach { id ->
            get(context, id)?.let { res ->
                if (builtinIds.contains(id)) {
                    res.isBuiltin = 2 // 被覆写的内置
                }
                list.add(res)
            }
        }

        // 处理仅在 Assets 中的资源
        builtinIds.forEach { id ->
            if (!fileIds.contains(id)) {
                get(context, id)?.let { res ->
                    res.isBuiltin = 1 // 原始内置
                    list.add(res)
                }
            }
        }

        return list.sortedByDescending { res ->
            if (res.isBuiltin == 1) 0L else {
                val ext = if (res.type == 0) "json" else "js"
                File(dir, "${res.id}.$ext").lastModified()
            }
        }
    }
    fun get(context: Context, id: String): DictionaryResource? {
        val dir = getDir(context)
        val jsonFile = File(dir, "$id.json")
        val jsFile = File(dir, "$id.js")

        if (jsonFile.exists() || jsFile.exists()) {
            val targetFile = if (jsonFile.exists()) jsonFile else jsFile
            val res = try {
                if (jsonFile.exists()) DictionaryResource.parseJSON(context, jsonFile.readText())
                else DictionaryResource.parseScript(context, jsFile.readText())
            } catch (_: Exception) {
                null
            }

            if (res != null) {
                res.localPath = targetFile.absolutePath
                val assetsList = context.assets.list(DIR_NAME) ?: emptyArray()
                res.isBuiltin = if (assetsList.any { it.startsWith("$id.") }) 2 else 0
                return res
            }
        }

        return try {
            val assetsList = context.assets.list(DIR_NAME) ?: emptyArray()
            if (assetsList.contains("$id.json")) {
                val content =
                    context.assets.open("$DIR_NAME/$id.json").bufferedReader().use { it.readText() }
                DictionaryResource.parseJSON(context, content).apply { isBuiltin = 1 }
            } else if (assetsList.contains("$id.js")) {
                val content =
                    context.assets.open("$DIR_NAME/$id.js").bufferedReader().use { it.readText() }
                DictionaryResource.parseScript(context, content).apply { isBuiltin = 1 }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun testExists(context: Context, res: DictionaryResource, excludeId: String?) {
        DictionaryResource.testId(context, res.id)
        if (res.id == excludeId) return

        val dir = getDir(context)
        val fileExists = File(dir, "${res.id}.json").exists() || File(dir, "${res.id}.js").exists()

        val assetExists = context.assets.list(DIR_NAME)?.any { it.startsWith("${res.id}.") } == true

        if (fileExists || assetExists) {
            throw Exception(context.getString(R.string.dictionary_error_id_exists, res.id))
        }
    }

    fun save(context: Context, res: DictionaryResource, excludeId: String? = null) {
        val dir = getDir(context)
        testExists(context, res, excludeId)

        val ext = if (res.type == 0) "json" else "js"
        val file = File(dir, "${res.id}.$ext")
        if (res.type == 0) {
            val json = JSONObject().apply {
                put("id", res.id)
                put("name", res.name)
                put("description", res.description)
                put("type", 0)
                put("content", res.content)
                put("author", res.author)
                put("version", res.version)
            }
            file.writeText(json.toString())
        } else {
            file.writeText(res.content)
        }
        res.localPath = file.absolutePath
    }

    fun update(context: Context, oldId: String, res: DictionaryResource) {
        save(context, res, excludeId = oldId)
        if (oldId != res.id) {
            delete(context, oldId)
        }
    }

    fun delete(context: Context, id: String): Boolean {
        val dir = getDir(context)
        return File(dir, "$id.json").delete() || File(dir, "$id.js").delete()
    }

    fun randomID(): String {
        val chars = "abcdef"
        val prefix = chars.random()
        val suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 7)
        return "$prefix$suffix"
    }
}
