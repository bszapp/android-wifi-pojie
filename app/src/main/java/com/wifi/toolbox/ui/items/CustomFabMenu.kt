package com.wifi.toolbox.ui.items
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

data class FabMenuItem(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val isSelected: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.CustomFabMenu(
    expanded: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    items: List<FabMenuItem>,
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val focusRequester = remember { FocusRequester() }

    FloatingActionButtonMenu(
        modifier = modifier.align(Alignment.BottomEnd),
        expanded = expanded,
        button = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    if (expanded) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text("更多操作") } },
                state = rememberTooltipState(),
            ) {
                ToggleFloatingActionButton(
                    modifier = Modifier
                        .semantics {
                            traversalIndex = -1f
                        }
                        .animateFloatingActionButton(
                            visible = visible || expanded,
                            alignment = Alignment.BottomEnd,
                        )
                        .focusRequester(focusRequester),
                    checked = expanded,
                    onCheckedChange = onCheckedChange,
                    containerSize = { 48.dp },
                ) {
                    val imageVector by remember {
                        derivedStateOf {
                            if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add
                        }
                    }
                    Icon(
                        painter = rememberVectorPainter(imageVector),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).animateIcon({ checkedProgress }),
                    )
                }
            }
        },
    ) {
        items.forEachIndexed { i, item ->
            FloatingActionButtonMenuItem(
                modifier = Modifier
                    .height(42.dp)
                    .semantics {
                        isTraversalGroup = true
                        if (i == items.size - 1) {
                            customActions = listOf(
                                CustomAccessibilityAction(label = "Close menu") {
                                    onCheckedChange(false); true
                                }
                            )
                        }
                    }
                    .then(
                        if (i == 0) {
                            Modifier.onKeyEvent {
                                if (it.type == KeyEventType.KeyDown &&
                                    (it.key == Key.DirectionUp || (it.isShiftPressed && it.key == Key.Tab))
                                ) {
                                    focusRequester.requestFocus()
                                    return@onKeyEvent true
                                }
                                false
                            }
                        } else Modifier
                    ),
                onClick = {
                    item.onClick()
                    onCheckedChange(false)
                },
                containerColor = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (item.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                text = {
                    Text(
                        text = item.label,
                        style = if (item.isSelected) MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        else MaterialTheme.typography.labelLarge
                    )
                },
            )
        }
    }
}