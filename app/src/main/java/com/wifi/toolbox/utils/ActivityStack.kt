package com.wifi.toolbox.utils

import android.app.Activity
import java.lang.ref.WeakReference

object ActivityStack {
    private var currentActivity: WeakReference<Activity>? = null

    fun register(activity: Activity) {
        currentActivity = WeakReference(activity)
    }

    fun unregister() {
        currentActivity = null
    }

    fun get(): Activity? = currentActivity?.get()
}