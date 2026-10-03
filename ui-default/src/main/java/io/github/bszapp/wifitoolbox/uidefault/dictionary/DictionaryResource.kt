package io.github.bszapp.wifitoolbox.uidefault.dictionary

import android.content.Context
import io.github.bszapp.wifitoolbox.uidefault.R

data class DictionaryResource(
    val id: String,
    val name: String?,
    val description: String?,
    val type: Int,
    val content: String,
    val author: String?,
    val version: String?,
    var isBuiltin: Int = 0,
    var localPath: String? = null
) {
    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")
        private val HEADER_REGEX = Regex("""// ==ToolboxScript==([\s\S]*?)// ==/ToolboxScript==""")
        private val PROPERTY_REGEX = Regex("""@(\w+)\s+(.*)""")

        private fun getString(context: Context, resId: Int): String {
            return context.getString(resId)
        }

        fun parseScript(context: Context, scriptContent: String): DictionaryResource {
            val matchResult = HEADER_REGEX.find(scriptContent) ?: throw Exception(getString(context, R.string.dictionary_error_script_header_not_found))
            val headerContent = matchResult.groupValues[1]

            val properties = mutableMapOf<String, String?>()
            headerContent.lines().forEach { line ->
                PROPERTY_REGEX.find(line)?.let {
                    properties[it.groupValues[1].trim()] = it.groupValues[2].trim()
                }
            }

            val id = properties["id"] ?: throw Exception(getString(context, R.string.dictionary_error_script_id_not_found))

            testId(context, id)

            return DictionaryResource(
                id = id,
                name = properties["name"],
                description = properties["description"],
                type = 1,
                content = scriptContent,
                author = properties["author"],
                version = properties["version"]
            )
        }

        fun parseJSON(context: Context, jsonString: String): DictionaryResource {
            val json = org.json.JSONObject(jsonString)
            val id = json.optString("id").takeIf { it.isNotEmpty() } ?: throw Exception(getString(context, R.string.dictionary_error_json_id_missing))

            testId(context, id)

            return DictionaryResource(
                id = id,
                name = if (json.isNull("name")) null else json.optString("name"),
                description = if (json.isNull("description")) null else json.optString("description"),
                type = 0,
                content = json.optString("content").takeIf { it.isNotEmpty() } ?: throw Exception(getString(context, R.string.dictionary_error_json_content_missing)),
                author = if (json.isNull("author")) null else json.optString("author"),
                version = if (json.isNull("version")) null else json.optString("version")
            )
        }

        fun testId(context: Context, id: String) {
            if (!ID_REGEX.matches(id)) {
                throw Exception(getString(context, R.string.dictionary_error_id_format))
            }
        }
    }
}
