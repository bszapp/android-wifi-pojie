package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    darkTheme: String,
    onDarkThemeChange: (String) -> Unit,
    onMenuClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(
                        modifier = Modifier.padding(0.dp, 8.dp)
                    ) {
                        Text(
                            text = "设置",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open navigation drawer"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        ProvidePreferenceLocals {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                item {
                    PreferenceCategory(title = { Text("主题") })
                }
                item {
                    SwitchPreference(
                        value = dynamicColor,
                        onValueChange = onDynamicColorChange,
                        title = { Text("动态主题色（Android12+）") },
                        summary = { Text("使用系统主题的动态颜色") },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.ColorLens,
                                contentDescription = null
                            )
                        }
                    )
                }
                item {
                    ListPreference(
                        value = darkTheme,
                        onValueChange = onDarkThemeChange,
                        values = listOf("跟随设备", "开启", "关闭"),
                        title = { Text("深色主题") },
                        summary = { Text(text = darkTheme) },
                        type = ListPreferenceType.DROPDOWN_MENU,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Brightness4,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}
