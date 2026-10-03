package io.github.bszapp.wifitoolbox.error

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import io.github.bszapp.wifitoolbox.MainActivity

/** Keeps a foreground window alive while replacing the App's main process. */
class AppRestartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var restartRequested = false
    private var previousProcessId = -1
    private var mainActivityLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previousProcessId = intent.getIntExtra(EXTRA_PREVIOUS_PROCESS_ID, -1)
        if (previousProcessId <= 0 || previousProcessId == Process.myPid()) finish()
    }

    override fun onResume() {
        super.onResume()
        // The transparent foreground Activity only performs the handoff. It
        // creates no report Views and does not wait for an empty page to draw.
        if (restartRequested || isFinishing) return
        restartRequested = true
        Log.i(TAG, "重启交接：旧应用进程=$previousProcessId，重启进程=${Process.myPid()}")
        Process.killProcess(previousProcessId)
        launchAfterPreviousProcessEnds()
    }

    @Suppress("DEPRECATION")
    private fun launchAfterPreviousProcessEnds() {
        val activityManager = getSystemService(ActivityManager::class.java)
        // Wait for ActivityManager's process-death handling, not a fixed delay:
        // the new launch must not be delivered to the dying main process.
        val previousProcessExists = activityManager.runningAppProcesses.orEmpty().any {
            it.pid == previousProcessId && it.uid == Process.myUid()
        }
        if (previousProcessExists) {
            handler.postDelayed({ launchAfterPreviousProcessEnds() }, 50L)
            return
        }
        Log.i(TAG, "旧应用进程已退出，启动正常 MainActivity")
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
        mainActivityLaunched = true
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        if (mainActivityLaunched) Process.killProcess(Process.myPid())
    }

    companion object {
        internal const val EXTRA_PREVIOUS_PROCESS_ID =
            "io.github.bszapp.wifitoolbox.PREVIOUS_PROCESS_ID"
        private const val TAG = "AppRestart"
    }
}
