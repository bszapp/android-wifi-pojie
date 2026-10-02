package io.github.bszapp.wifitoolbox

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import io.github.bszapp.wifitoolbox.contract.startup.StartupStatus
import io.github.bszapp.wifitoolbox.ui.component.DebugWatermark
import io.github.bszapp.wifitoolbox.ui.startup.StartupScreen
import io.github.bszapp.wifitoolbox.ui.theme.WifiToolboxMaterialTheme
import io.github.bszapp.wifitoolbox.uidefault.DefaultUI

class MainActivity : ComponentActivity() {

    private val uiSession by viewModels<ServiceUiSessionViewModel>()
    private val controller by lazy { AppControllerProvider.get() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                        key(state.connectionId) { DefaultUI() }
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
}
