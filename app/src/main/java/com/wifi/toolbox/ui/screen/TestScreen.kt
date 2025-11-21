package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.items.FoldCard
import com.wifi.toolbox.ui.items.LogView
import com.wifi.toolbox.ui.items.rememberLogState
import com.wifi.toolbox.ui.screen.test.ConnectWifiTest
import com.wifi.toolbox.ui.screen.test.ShellTest
import com.wifi.toolbox.ui.screen.test.ShizukuTest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(onMenuClick: () -> Unit) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Shizuku", "系统API", "终端命令")
    val logState = rememberLogState()
    var logCardExpanded by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column(
                    modifier = Modifier.padding(0.dp, 8.dp)
                ) {
                    Text(
                        text = "实验室", style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }, navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu, contentDescription = null
                    )
                }
            })
        }) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    text = title,
                                    color = if (selectedTabIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            })
                    }
                }

                when (selectedTabIndex) {
                    0 -> ShizukuTest(logState = logState, modifier = Modifier.fillMaxSize())
                    1 -> ConnectWifiTest(logState = logState, modifier = Modifier.fillMaxSize())
                    2 -> ShellTest(logState = logState, modifier = Modifier.fillMaxSize())
                }
            }

            FoldCard(
                title = "运行输出",
                expanded = logCardExpanded,
                onExpandedChange = { logCardExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                LogView(
                    logState = logState,
                    modifier = Modifier
                        .padding(8.dp)
                        .then(if (logCardExpanded) Modifier.fillMaxHeight(0.5f) else Modifier)
                )
            }
        }
    }
}

@Preview
@Composable
fun TestScreenPreview() {
    TestScreen(onMenuClick = {})
}