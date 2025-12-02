package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.ui.items.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePage(
    runListView: @Composable () -> Unit = {}
) {
    val app = LocalContext.current.applicationContext as MyApplication

    var expandedParamsCard by rememberSaveable { mutableStateOf(true) }
    var expandedOutputCard by rememberSaveable { mutableStateOf(false) }

    val scrollStateSaver = Saver<ScrollState, Int>(
        save = { it.value },
        restore = { ScrollState(it) }
    )
    val configScrollState = rememberSaveable(saver = scrollStateSaver) {
        ScrollState(0)
    }

    val logState = app.logState

    LaunchedEffect(Unit) {
        val currentTime =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logState.addLog("HomePage resumed at: $currentTime")
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FoldCard(
            title = "运行参数",
            expanded = expandedParamsCard,
            onExpandedChange = {
                expandedParamsCard = it
                if (it) {
                    expandedOutputCard = false
                }
            }
        ) {

            Column(
                modifier = Modifier
                    .fillMaxHeight(0.4f)
                    .verticalScroll(configScrollState)
                    .padding(16.dp, 0.dp, 16.dp, 16.dp),
            ) {
                ConfigItems(
                    config = app.pojieConfig,
                    onConfigChange = { app.updatePojieConfig(it) },
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
        ) {
            runListView()
        }
        FoldCard(
            title = "运行输出",
            expanded = expandedOutputCard,
            onExpandedChange = {
                expandedOutputCard = it
                if (it) {
                    expandedParamsCard = false
                }
            }
        ) {
            LogView(
                logState = logState,
                modifier = Modifier
                    .fillMaxHeight(0.4f)
                    .padding(8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePagePreview() {
    HomePage()
}