package io.github.bszapp.wifitoolbox.uidefault.dictionary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.bszapp.wifitoolbox.uidefault.R
import io.github.bszapp.wifitoolbox.uidefault.navigation.LocalNavigator
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog

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

@Composable
fun DictionaryScreen(state: DictionaryState) {
    val navigator = LocalNavigator.current
    DictionaryMaterialTheme(state.app) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = "资源",
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
    val currentAlert = alerts.current
    CompositionLocalProvider(
        LocalDictionaryAlerts provides alerts,
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            OverlayDialog(
                show = currentAlert != null,
                title = currentAlert?.first.orEmpty(),
                summary = currentAlert?.second,
                onDismissRequest = { alerts.dismiss() },
                content = {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            text = stringResource(R.string.dictionary_btn_ok),
                            onClick = { alerts.dismiss() },
                            modifier = Modifier.width(120.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                },
            )
        }
    }
}
