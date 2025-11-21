package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.MyApplication
import com.wifi.toolbox.ui.items.FoldCard
import com.wifi.toolbox.ui.items.LogView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage() {
    val app = LocalContext.current.applicationContext as MyApplication

    // Card expanded state
    var expanded by rememberSaveable { mutableStateOf(true) }

    // Custom saver for ScrollState
    val scrollStateSaver = Saver<ScrollState, Int>(
        save = { it.value },
        restore = { ScrollState(it) }
    )

    // Scroll state for ConfigItems
    val configScrollState = rememberSaveable(saver = scrollStateSaver) {
        ScrollState(0)
    }

    // Use the logState from MyApplication
    val logState = app.logState

    LaunchedEffect(Unit) {
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logState.addLog("HomePage resumed at: $currentTime")
    }

    val failureOptions = listOf("密码错误", "握手超时", "握手超次")


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FoldCard(
            title = "运行参数",
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {

            Column(
                modifier = Modifier
                    .fillMaxHeight(0.4f)
                    .verticalScroll(configScrollState)
                    .padding(16.dp,0.dp,16.dp,16.dp),
            ) {
                ConfigItems(
                    config = app.pojieConfig,
                    onConfigChange = { app.updatePojieConfig(it) },
                    failureOptions = failureOptions
                )
            }
        }
        Card(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("这是一个卡片")
            }
        }
        FoldCard(
            title = "运行输出",
            expanded = !expanded,
            onExpandedChange = { expanded = !it }
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
