package com.wifi.toolbox.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.FormatColorFill
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.PreferenceCategory
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import me.zhanghai.compose.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    dynamicColorSeed: Int,
    onDynamicColorSeedChange: (Int) -> Unit,
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

                    var showColorDialog = remember { mutableStateOf(false) }
                    var selectedColor = Color(dynamicColorSeed)
                    SuperDialog(
                        title = "选择颜色",
                        show = showColorDialog,
                        onDismissRequest = { showColorDialog.value = false }
                    ) {
                        Column {
                            ColorPicker(
                                initialColor = selectedColor,
                                onColorChanged = { selectedColor = it },

                                )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    text = "取消",
                                    onClick = { showColorDialog.value = false }
                                )
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    text = "确认",
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                    onClick = {
                                        showColorDialog.value = false
                                        onDynamicColorSeedChange(selectedColor.toArgb())
                                    }
                                )
                            }
                        }
                    }
                    AnimatedVisibility(
                        visible = !dynamicColor,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Preference(
                            title = { Text("颜色种子") },
                            icon = {
                                Icon(
                                    imageVector = Icons.Filled.FormatColorFill,
                                    contentDescription = null
                                )
                            },
                            summary = { Text(selectedColor.toHexString()) },
                            onClick = {
                                showColorDialog.value = true
                            }
                        )
                    }

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


fun Color.toHexString(): String {
    return String.format("#%08X", this.toArgb())
}