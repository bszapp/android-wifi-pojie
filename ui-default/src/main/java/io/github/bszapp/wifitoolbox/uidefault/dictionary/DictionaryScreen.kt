package io.github.bszapp.wifitoolbox.uidefault.dictionary

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicMaterialThemeState
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import io.github.bszapp.wifitoolbox.uidefault.theme.isInDarkTheme

class DictionaryAlerts {
    var current by mutableStateOf<Pair<String, String>?>(null)
        private set

    fun alert(title: String, text: String) {
        current = title to text
    }

    fun dismiss() {
        current = null
    }
}

val LocalDictionaryAlerts = staticCompositionLocalOf<DictionaryAlerts> {
    error("Dictionary alerts not provided")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(state: DictionaryState) {
    val navigator = LocalNavigator.current
    DictionaryMaterialTheme(state.app) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("资源") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                DictionaryPage(state)
            }
        }
    }
}

@Composable
internal fun DictionaryMaterialTheme(alerts: DictionaryAlerts, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isInDarkTheme()

    // Keep the source resource screen's Material palette inside the Miuix host.
    val seedColor = remember(context, dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme = if (dark) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
            scheme.primary
        } else {
            Color(0xFF008B8A)
        }
    }
    val materialTheme = rememberDynamicMaterialThemeState(
        isDark = dark,
        style = PaletteStyle.TonalSpot,
        seedColor = seedColor,
        modifyColorScheme = { scheme ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !dark) {
                scheme.copy(primary = Color(0xFF008B8A))
            } else {
                scheme
            }
        },
    )
    DynamicMaterialTheme(state = materialTheme, animate = true) {
        CompositionLocalProvider(
            LocalDictionaryAlerts provides alerts,
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                alerts.current?.let { (title, message) ->
                    AlertDialog(
                        onDismissRequest = { alerts.dismiss() },
                        title = { Text(title) },
                        text = { Text(message) },
                        confirmButton = {
                            TextButton(onClick = { alerts.dismiss() }) {
                                Text(stringResource(R.string.dictionary_btn_ok))
                            }
                        },
                    )
                }
            }
        }
    }
}
