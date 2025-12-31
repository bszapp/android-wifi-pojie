package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import com.wifi.toolbox.ui.items.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(onMenuClick: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        NavContainer(

            listOf(
                object : NavPage {
                    override val name = "扫描"
                    override val selectedIcon = Icons.Filled.Radar
                    override val unselectedIcon = Icons.Outlined.Radar
                    override val content = @Composable {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("前面的区域，以后再来探索吧")
                        }
                    }
                },
                object : NavPage {
                    override val name = "本地"
                    override val selectedIcon = Icons.Filled.Dns
                    override val unselectedIcon = Icons.Outlined.Dns
                    override val content = @Composable {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("前面的区域，以后再来探索吧")
                        }
                    }
                },
                object : NavPage {
                    override val name = "设置"
                    override val selectedIcon = Icons.Filled.Settings
                    override val unselectedIcon = Icons.Outlined.Settings
                    override val content = @Composable {
                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("前面的区域，以后再来探索吧")
                        }
                    }
                }
            ), 0, "wifi管理器", onMenuClick)
    }
}
