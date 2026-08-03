package io.github.bszapp.wifitoolbox

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.bszapp.wifitoolbox.contract.AppControllerProvider
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.ui.component.DebugWatermark
import io.github.bszapp.wifitoolbox.ui.startup.StartupScreen
import io.github.bszapp.wifitoolbox.ui.theme.WifiToolboxMaterialTheme
import io.github.bszapp.wifitoolbox.uidefault.DefaultUI

class MainActivity : ComponentActivity() {

    private val controller by lazy { AppControllerProvider.get() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            val state by controller.startup.state.collectAsState()
            val isExiting by controller.isExiting.collectAsState()

            LaunchedEffect(isExiting) {
                if (isExiting) finish()
            }

            AnimatedContent(
                targetState = state.status == StartupStatus.RUNNING,
                label = "startup_state",
            ) { isRunning ->
                if (isRunning) {
                    DefaultUI()
                } else {
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
}
