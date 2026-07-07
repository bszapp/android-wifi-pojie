package io.github.bszapp.wifitoolbox.uidefault.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.bszapp.wifitoolbox.uidefault.model.DefaultViewModel
import io.github.bszapp.wifitoolbox.uidefault.widget.WifiList
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(vm: DefaultViewModel = viewModel()) {
    val isScanning by vm.wifiList.isScanning.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val isListAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    val topBarColor by animateColorAsState(
        targetValue = if (isListAtTop) MaterialTheme.colorScheme.surface
        else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(200),
        label = "topBarColor",
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Surface(
                color = topBarColor,
                modifier = Modifier.graphicsLayer { clip = false },
            ) {
                Box {
                    TopAppBar(
                        title = { Text("连接", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            TooltipBox(
                                positionProvider = rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Below
                                ),
                                tooltip = { PlainTooltip { Text("展开") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = {},
                                    shapes = IconButtonDefaults.shapes()
                                ) {
                                    Icon(
                                        painter = rememberVectorPainter(Icons.Rounded.Menu),
                                        contentDescription = "展开",
                                    )
                                }
                            }
                        },
                        actions = {
                            TooltipBox(
                                positionProvider = rememberTooltipPositionProvider(
                                    TooltipAnchorPosition.Below
                                ),
                                tooltip = { PlainTooltip { Text("刷新") } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = { vm.wifiList.startScan() },
                                    shapes = IconButtonDefaults.shapes(),
                                    enabled = !isScanning,
                                ) {
                                    Icon(Icons.TwoTone.Refresh, contentDescription = null)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            scrolledContainerColor = Color.Transparent,
                        ),
                    )

                    AnimatedVisibility(
                        visible = isScanning,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = 4.dp),
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                    ) {
                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        val startPadding = innerPadding.calculateStartPadding(layoutDirection)
        val endPadding = innerPadding.calculateEndPadding(layoutDirection)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = startPadding, end = endPadding),
            ) {
                WifiList(
                    modifier = Modifier,
                    vm = vm,
                    listState = listState,
                )

                AnimatedVisibility(
                    visible = !isListAtTop,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(300, easing = FastOutSlowInEasing),
                    ),
                ) {
                    Box(modifier = Modifier.padding(end = 24.dp, bottom = 24.dp)) {
                        TooltipBox(
                            positionProvider = rememberTooltipPositionProvider(
                                TooltipAnchorPosition.Above
                            ),
                            tooltip = { PlainTooltip { Text("回到顶部") } },
                            state = rememberTooltipState(),
                        ) {
                            FloatingActionButton(
                                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "回到顶部")
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = isScanning,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
            ) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .offset(y = (-4).dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}