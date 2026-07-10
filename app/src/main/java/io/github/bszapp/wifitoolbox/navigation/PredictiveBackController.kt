package io.github.bszapp.wifitoolbox.navigation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Owns the Android-specific predictive-back preference and applies it globally.
 *
 * The preference is intentionally kept outside the UI settings contract. UI code only
 * writes the shared preference; this app-layer listener performs the hidden-API call and
 * recreates active activities so navigation, pages and Miuix overlays all see the change.
 */
internal class PredictiveBackController(
    private val application: Application,
) : Application.ActivityLifecycleCallbacks,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val preferences: SharedPreferences by lazy {
        application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    private val resumedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var started = false

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || started) return
        started = true

        migrateLegacySettingIfNeeded()
        application.registerActivityLifecycleCallbacks(this)
        preferences.registerOnSharedPreferenceChangeListener(this)

        // Apply before the first activity is created.
        applySetting(preferences.getBoolean(KEY_ENABLE_PREDICTIVE_BACK, false))
    }

    fun stop() {
        if (!started) return
        started = false
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        application.unregisterActivityLifecycleCallbacks(this)
        resumedActivities.clear()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key != KEY_ENABLE_PREDICTIVE_BACK) return
        val enabled = sharedPreferences.getBoolean(KEY_ENABLE_PREDICTIVE_BACK, false)
        if (applySetting(enabled)) {
            recreateResumedActivities()
        }
    }

    private fun recreateResumedActivities() {
        resumedActivities.toList().forEach { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.recreate()
            }
        }
    }

    private fun applySetting(value: Boolean): Boolean = runCatching {
        HiddenApiBypass.addHiddenApiExemptions(PREDICTIVE_BACK_HIDDEN_API)
        val method = ApplicationInfo::class.java.getDeclaredMethod(
            "setEnableOnBackInvokedCallback",
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(application.applicationInfo, value)
        true
    }.onFailure {
        Log.w(TAG, "无法更新预测式返回设置", it)
    }.getOrDefault(false)

    /** Preserve the value written by builds that temporarily stored this in settings.json. */
    private fun migrateLegacySettingIfNeeded() {
        if (preferences.contains(KEY_ENABLE_PREDICTIVE_BACK)) return
        val file = File(application.filesDir, LEGACY_SETTINGS_FILE)
        if (!file.isFile) return
        val legacyValue = runCatching {
            JSONObject(file.readText())
                .optJSONObject("theme")
                ?.takeIf { it.has(LEGACY_SETTINGS_KEY) }
                ?.getBoolean(LEGACY_SETTINGS_KEY)
        }.getOrNull() ?: return
        preferences.edit().putBoolean(KEY_ENABLE_PREDICTIVE_BACK, legacyValue).apply()
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivities += activity
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities -= activity
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        resumedActivities -= activity
    }

    private companion object {
        const val TAG = "PredictiveBack"
        const val PREFERENCES_NAME = "android_ui_preferences"
        const val KEY_ENABLE_PREDICTIVE_BACK = "enable_predictive_back"
        const val LEGACY_SETTINGS_FILE = "settings.json"
        const val LEGACY_SETTINGS_KEY = "enablePredictiveBack"
        const val PREDICTIVE_BACK_HIDDEN_API =
            "Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback"
    }
}
