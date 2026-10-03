package io.github.bszapp.wifitoolbox

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TextAppearanceSpan
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.key
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.error.ErrorReport
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.ui.component.DebugWatermark
import io.github.bszapp.wifitoolbox.ui.startup.StartupScreen
import io.github.bszapp.wifitoolbox.ui.theme.WifiToolboxMaterialTheme
import io.github.bszapp.wifitoolbox.uidefault.DefaultUI
import io.github.bszapp.wifitoolbox.uidefault.dictionary.DictionaryViewModel

class MainActivity : ComponentActivity() {

    private val uiSession by viewModels<ServiceUiSessionViewModel>()
    private val dictionaryViewModel by viewModels<DictionaryViewModel>()
    private val controller by lazy { AppControllerProvider.get() }
    private var errorReportFile: File? = null
    private var errorReportLogFile: File? = null
    private val exportErrorReport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val capturedLog = errorReportLogFile
        if (uri != null && capturedLog != null) {
            Thread({
                try {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: error("无法打开报告保存文件")
                    output.use { destination ->
                        capturedLog.inputStream().use { source -> source.copyTo(destination) }
                    }
                    runOnUiThread {
                        Toast.makeText(this, R.string.error_report_exported, Toast.LENGTH_SHORT).show()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            getString(R.string.error_report_export_failed, error.message ?: error.toString()),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }, "error-report-export").start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as ToolboxApp
        errorReportFile = app.errorReports.reportFor(intent)
        if (errorReportFile != null) setTheme(android.R.style.Theme_Holo)
        super.onCreate(savedInstanceState)
        errorReportFile?.let { reportFile ->
            showErrorReport(app, reportFile)
            return
        }
        app.startNormalRuntime()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val state by controller.startup.state.collectAsState()
            LaunchedEffect(controller) {
                controller.exitRequests.collect { finish() }
            }
            val running = state.status == StartupStatus.RUNNING
            val sessionOwner = remember(if (running) state.connectionId else null) {
                if (running) object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                    override val viewModelStore = uiSession.storeFor(state.connectionId)
                    override val defaultViewModelProviderFactory
                        get() = this@MainActivity.defaultViewModelProviderFactory
                    override val defaultViewModelCreationExtras
                        get() = this@MainActivity.defaultViewModelCreationExtras
                } else null
            }

            AnimatedContent(
                targetState = state.status == StartupStatus.RUNNING,
                label = "startup_state",
            ) { isRunning ->
                if (isRunning && running && sessionOwner != null) {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides sessionOwner) {
                        key(state.connectionId) {
                            DefaultUI(dictionaryViewModel = dictionaryViewModel)
                        }
                    }
                } else if (!isRunning) {
                    WifiToolboxMaterialTheme(settingsManager = controller.settings) {
                        @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
                        Scaffold(modifier = Modifier.fillMaxSize()) {
                            StartupScreen()
                        }
                    }
                }
            }
            DebugWatermark()
        }
    }

    /** This branch creates framework Views only; no Compose or UI ViewModel is loaded. */
    private fun showErrorReport(app: ToolboxApp, reportFile: File) {
        // A background-thread crash can briefly launch an Activity in the dying
        // process. Keep the fallback until a fresh report-only App has started.
        if (!app.hasStartedRuntime) app.errorReports.cancelScheduledRestart()
        title = getString(R.string.error_report_title)
        setContentView(R.layout.activity_error_report)
        val report = runCatching { ErrorReport.fromText(reportFile.readText(Charsets.UTF_8)) }
            .getOrElse { ErrorReport(getString(R.string.error_report_read_failed, it.message), "") }
        val highlightedDetails = SpannableString(report.details)
        val messageStart = report.details.indexOf("异常消息：")
        if (messageStart >= 0) {
            val messageEnd = Regex(
                "\n\n(?:Service AndroidApi 调用栈|Service 远端调用栈|App 调用栈)：",
            ).find(report.details, messageStart)?.range?.first ?: report.details.length
            highlightedDetails.setSpan(
                TextAppearanceSpan(this, android.R.style.TextAppearance_Holo_Large),
                messageStart,
                messageEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        findViewById<TextView>(R.id.error_report_details).text = highlightedDetails
        findViewById<TextView>(R.id.error_report_device).text = report.deviceInformation
        findViewById<Button>(R.id.error_report_copy).setOnClickListener {
            getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.error_report_title), report.fullText),
                )
                Toast.makeText(this, R.string.error_report_copied, Toast.LENGTH_SHORT).show()
            }
        }
        errorReportLogFile = app.errorReports.capturedLogFor(reportFile)
        findViewById<Button>(R.id.error_report_export).apply {
            isEnabled = errorReportLogFile != null
            setOnClickListener {
                val reportTime = reportFile.name.substringBefore('_').toLong()
                val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(reportTime))
                exportErrorReport.launch("report$date.txt")
            }
        }
        findViewById<Button>(R.id.error_report_restart).setOnClickListener {
            app.restartFromErrorReport()
        }
    }
}
