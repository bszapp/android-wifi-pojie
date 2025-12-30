package com.wifi.toolbox.ui.items

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

interface NavPage {
    val name: String
    val selectedIcon: ImageVector
    val unselectedIcon: ImageVector
    val content: @Composable () -> Unit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavContainer(
    pages: List<NavPage>,
    defaultIndex: Int,
    subtitle: String,
    onMenuClick: () -> Unit
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(defaultIndex) }
    var previousIndex by rememberSaveable { mutableIntStateOf(defaultIndex) }

    val view = LocalView.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var navBarWidth by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = currentIndex != defaultIndex) {
        previousIndex = currentIndex
        currentIndex = defaultIndex
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                ),
                title = {
                    Column(modifier = Modifier.padding(0.dp, 8.dp)) {
                        Text(text = pages[currentIndex].name, style = MaterialTheme.typography.titleLarge)
                        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .onGloballyPositioned { navBarWidth = it.size.width.toFloat() }
                    .pointerInput(pages.size) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val index = (offset.x / (navBarWidth / pages.size)).toInt().coerceIn(0, pages.size - 1)
                                if (currentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    previousIndex = currentIndex
                                    currentIndex = index
                                }
                            },
                            onDrag = { change, _ ->
                                val index = (change.position.x / (navBarWidth / pages.size)).toInt().coerceIn(0, pages.size - 1)
                                if (currentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    previousIndex = currentIndex
                                    currentIndex = index
                                }
                            }
                        )
                    }
                    .pointerInput(pages.size) {
                        detectTapGestures(
                            onPress = { offset ->
                                val index = (offset.x / (navBarWidth / pages.size)).toInt().coerceIn(0, pages.size - 1)
                                if (currentIndex != index) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    previousIndex = currentIndex
                                    currentIndex = index
                                }
                            }
                        )
                    }
            ) {
                pages.forEachIndexed { index, page ->
                    val selected = currentIndex == index
                    NavigationBarItem(
                        icon = { Icon(imageVector = if (selected) page.selectedIcon else page.unselectedIcon, contentDescription = page.name) },
                        label = { Text(page.name) },
                        selected = selected,
                        alwaysShowLabel = false,
                        onClick = {
                            if (currentIndex != index) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                previousIndex = currentIndex
                                currentIndex = index
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            pages.forEachIndexed { index, page ->
                val isVisible = index == currentIndex
                val isForward = currentIndex > previousIndex

                AnimatedVisibility(
                    visible = isVisible,
                    enter = slideInHorizontally(tween(300)) { if (isForward) it else -it } + fadeIn(),
                    exit = slideOutHorizontally(tween(300)) { if (isForward) -it else it } + fadeOut()
                ) {
                    Box(Modifier.fillMaxSize()) {
                        key(page.name) {
                            page.content()
                        }
                    }
                }
            }
        }
    }
}