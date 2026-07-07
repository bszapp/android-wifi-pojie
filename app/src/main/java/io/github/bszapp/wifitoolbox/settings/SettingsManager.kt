package io.github.bszapp.wifitoolbox.settings

import android.content.Context
import android.util.Log
import io.github.bszapp.wifitoolbox.BuildConfig
import io.github.bszapp.wifitoolbox.contract.settings.SettingsManagerBase
import org.json.JSONObject
import java.io.File

class SettingsManager(context: Context) : SettingsManagerBase() {
    private val settingsFile = File(context.applicationContext.filesDir, FILE_NAME)
    private val versionCode = BuildConfig.VERSION_CODE.toLong()

    init {
        load()
        bindAutoSave()
    }

    private fun load() {
        val json = if (settingsFile.exists()) {
            runCatching { JSONObject(settingsFile.readText()) }
                .onFailure { Log.w(TAG, "读取设置文件失败，使用默认设置", it) }
                .getOrNull()
        } else {
            null
        }
        loadFromJson(json)
    }

    override fun save() {
        val json = exportJson(lastVersion = versionCode)
        runCatching {
            settingsFile.parentFile?.mkdirs()
            settingsFile.writeText(json.toString(2))
        }.onFailure {
            Log.e(TAG, "保存设置文件失败：${settingsFile.absolutePath}", it)
        }
    }

    companion object {
        private const val TAG = "SettingsManager"
        private const val FILE_NAME = "settings.json"
    }
}
