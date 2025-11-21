package com.wifi.toolbox.ui.screen.test

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.items.LogState


@Composable
fun ShellTest(logState: LogState, modifier: Modifier = Modifier) {

    val logView by rememberUpdatedState(logState)

    var selectedTabIndex by remember { mutableStateOf(0) }
    val buttonLabels = arrayOf("Normal", "Shizuku", "Root")

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(
                    "运行方式",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    buttonLabels.forEachIndexed { index,
                                                  label ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = buttonLabels.size
                            ),
                            onClick = {
                                selectedTabIndex = index
                            },
                            selected = selectedTabIndex == index
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}
