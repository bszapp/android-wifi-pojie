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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import androidx.compose.material.icons.filled.Api // Assuming an icon for API
import androidx.compose.ui.text.AnnotatedString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    darkTheme: Int,
    onDarkThemeChange: (Int) -> Unit,
    hiddenApiBypass: Int,
    onHiddenApiBypassChange: (Int) -> Unit,
    onMenuClick: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hiddenApiBypassValues = listOf("不使用", "LSPass", "HiddenApiBypass")
    val darkThemeValues = listOf("跟随设备", "开启", "关闭")

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                },
                scrollBehavior = scrollBehavior
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
                    ListPreference(
                        value = darkTheme,
                        onValueChange = onDarkThemeChange,
                        values = darkThemeValues.indices.toList(),
                        valueToText = { AnnotatedString(darkThemeValues[it]) },
                        title = { Text("深色主题") },
                        summary = { Text(text = darkThemeValues[darkTheme]) },
                        type = ListPreferenceType.DROPDOWN_MENU,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Brightness4,
                                contentDescription = null
                            )
                        }
                    )
                    PreferenceCategory(title = { Text("隐藏API调用") })
                    ListPreference(
                        value = hiddenApiBypass,
                        onValueChange = onHiddenApiBypassChange,
                        values = hiddenApiBypassValues.indices.toList(),
                        valueToText = { AnnotatedString(hiddenApiBypassValues[it]) },
                        title = { Text("实现方式") },
                        summary = { Text(text = hiddenApiBypassValues[hiddenApiBypass]) },
                        type = ListPreferenceType.DROPDOWN_MENU,
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Api,
                                contentDescription = null
                            )
                        }
                    )
                }
            }
        }
    }
}
