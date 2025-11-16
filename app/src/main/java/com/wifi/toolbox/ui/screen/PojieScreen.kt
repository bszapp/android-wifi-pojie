package com.wifi.toolbox.ui.screen

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wifi.toolbox.ui.screen.pojie.HelpPage
import com.wifi.toolbox.ui.screen.pojie.HistoryPage
import com.wifi.toolbox.ui.screen.pojie.HomePage
import com.wifi.toolbox.ui.screen.pojie.ResourcesPage
import com.wifi.toolbox.ui.screen.pojie.SettingsPage

sealed class PojieScreenPages(val route: String, val name: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    object Home : PojieScreenPages("home", "主页", Icons.Filled.Home, Icons.Outlined.Home)
    object History : PojieScreenPages("history", "历史", Icons.Filled.History, Icons.Outlined.History)
    object Resources : PojieScreenPages("resources", "资源", Icons.Filled.Inbox, Icons.Outlined.Inbox)
    object Settings : PojieScreenPages("settings", "设置", Icons.Filled.Settings, Icons.Outlined.Settings)
    object Help : PojieScreenPages("help", "帮助", Icons.Filled.Info, Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PojieScreen(onMenuClick: () -> Unit) {
    val items = listOf(
        PojieScreenPages.Resources,
        PojieScreenPages.History,
        PojieScreenPages.Home,
        PojieScreenPages.Settings,
        PojieScreenPages.Help,
    )
    val navController = rememberNavController()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("密码字典破解") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Open navigation drawer"
                        )
                    }
                }
            )
        },
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.name
                            )
                        },
                        label = { Text(screen.name) },
                        selected = selected,
                        alwaysShowLabel = false,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = PojieScreenPages.Home.route,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            enterTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }

                if (targetIndex > initialIndex) {
                    slideInHorizontally(initialOffsetX = { it })
                } else {
                    slideInHorizontally(initialOffsetX = { -it })
                }
            },
            exitTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }
                if (targetIndex > initialIndex) {
                    slideOutHorizontally(targetOffsetX = { -it })
                } else {
                    slideOutHorizontally(targetOffsetX = { it })
                }
            },
            popEnterTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }

                if (initialIndex > targetIndex) {
                    slideInHorizontally(initialOffsetX = { -it })
                } else {
                    slideInHorizontally(initialOffsetX = { it })
                }
            },
            popExitTransition = {
                val initialIndex = items.indexOfFirst { it.route == initialState.destination.route }
                val targetIndex = items.indexOfFirst { it.route == targetState.destination.route }
                if (initialIndex > targetIndex) {
                    slideOutHorizontally(targetOffsetX = { it })
                } else {
                    slideOutHorizontally(targetOffsetX = { -it })
                }
            }
        ) {
            composable(PojieScreenPages.Home.route) {
                HomePage()
            }
            composable(PojieScreenPages.History.route) {
                HistoryPage()
            }
            composable(PojieScreenPages.Resources.route) {
                ResourcesPage()
            }
            composable(PojieScreenPages.Settings.route) {
                SettingsPage()
            }
            composable(PojieScreenPages.Help.route) {
                HelpPage()
            }
        }
    }
}
