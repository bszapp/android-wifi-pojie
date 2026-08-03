package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemShapes
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed

private val CornerLarge = 12.dp
private val CornerMedium = 6.dp
private val CornerSmall = 4.dp
private val CornerAnimSpec = tween<Dp>(durationMillis = 200)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun animatedMenuItemShapes(
    index: Int,
    count: Int,
    groupIndex: Int = 0,
    groupCount: Int = 1,
    checked: Boolean,
): MenuItemShapes {
    val isFirst = index == 0
    val isLast = index == count - 1

    val topCorner by animateDpAsState(
        targetValue = when {
            checked || (isFirst && groupIndex == 0) -> CornerLarge   // 选中 或 整体顶部
            isFirst -> CornerMedium                                   // 组内首项但非顶部组
            else -> CornerSmall
        },
        animationSpec = CornerAnimSpec,
        label = "menuItemTopCorner",
    )
    val bottomCorner by animateDpAsState(
        targetValue = when {
            checked || (isLast && groupIndex == groupCount - 1) -> CornerLarge   // 选中 或 整体底部
            isLast -> CornerMedium                                                // 组内末项但非底部组
            else -> CornerSmall
        },
        animationSpec = CornerAnimSpec,
        label = "menuItemBottomCorner",
    )

    val shape = RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = bottomCorner,
        bottomEnd = bottomCorner,
    )

    return MenuItemShapes(
        shape,
        selectedShape = shape
    )
}

// ──────────────────────────────────────────────
// 数据模型
// ──────────────────────────────────────────────

data class ActionButtonConfig(
    val icon: ImageVector,
    val text: String,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val onClick: () -> Unit,
)

/**
 * @param icon  标题行左侧图标，为 null 时不渲染图标
 * @param title 分组标题文字，为 null 时整个标题区域不渲染
 */
data class MenuGroupConfig(
    val icon: ImageVector?=null,
    val title: String?,
    val items: List<MenuItemConfig>,
)

/**
 * @param checkedIcon 选中时的图标，为 null 则复用 [icon]
 */
data class MenuItemConfig(
    val title: String,
    val icon: ImageVector,
    val checkedIcon: ImageVector? = null,
    val checked: Boolean = false,
    val onCheckedChange: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActionButtonGroupWithMenu(
    buttonConfig: ActionButtonConfig,
    menuGroups: List<MenuGroupConfig>,
    modifier: Modifier = Modifier,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val groupInteractionSource = remember { MutableInteractionSource() }
    val customContainerColor = buttonConfig.containerColor
    val customContentColor = buttonConfig.contentColor
    val leadingButtonColors = if (customContainerColor != null && customContentColor != null) {
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = customContainerColor,
            contentColor = customContentColor,
            checkedContainerColor = customContainerColor,
            checkedContentColor = customContentColor,
        )
    } else {
        ToggleButtonDefaults.toggleButtonColors()
    }
    val trailingButtonColors = if (customContainerColor != null && customContentColor != null) {
        ToggleButtonDefaults.toggleButtonColors(
            containerColor = customContainerColor,
            contentColor = customContentColor,
            checkedContainerColor = customContainerColor,
            checkedContentColor = customContentColor,
        )
    } else {
        ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        // ── 左侧主操作按钮（无持久选中态）──────────────────
        ToggleButton(
            checked = false,
            onCheckedChange = { buttonConfig.onClick() },
            shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
            colors = leadingButtonColors,
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier
                .requiredWidthIn(min = 0.dp)
                .height(40.dp),
        ) {
            AnimatedContent(
                targetState = buttonConfig.icon to buttonConfig.text,
                label = "ActionButtonContent",
            ) { (icon, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(text)
                }
            }
        }

        // ── 右侧更多按钮 + 下拉菜单 ──────────────────────
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text("更多") } },
                state = rememberTooltipState(),
            ) {
                ToggleButton(
                    checked = menuExpanded,
                    onCheckedChange = onMenuExpandedChange,
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    colors = trailingButtonColors,
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier
                        .requiredWidthIn(min = 0.dp)
                        .width(36.dp)
                        .height(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            DropdownMenuPopup(
                expanded = menuExpanded,
                onDismissRequest = {
                    onMenuExpandedChange(false)
                },
            ) {
                val groupCount = menuGroups.size
                menuGroups.fastForEachIndexed { groupIndex, group ->
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(groupIndex, groupCount),
                        interactionSource = groupInteractionSource,
                    ) {
                        // icon 或 title 任意非空才渲染标题区域
                        if (group.icon != null || group.title != null) {
                            MenuDefaults.DropdownMenuGroupLabel {
                                Row {
                                    if (group.icon != null) {
                                        Icon(
                                            imageVector = group.icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.size(4.dp))
                                    }
                                    if (group.title != null) {
                                        Text(
                                            text = group.title,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }

                        val itemCount = group.items.size
                        group.items.fastForEachIndexed { itemIndex, item ->
                            val resolvedCheckedIcon = item.checkedIcon ?: item.icon

                            DropdownMenuItem(
                                text = { Text(item.title) },
                                shapes = animatedMenuItemShapes(
                                    index = itemIndex,
                                    count = itemCount,
                                    groupIndex = groupIndex,
                                    groupCount = groupCount,
                                    checked = item.checked,
                                ),
                                checked = item.checked,
                                onCheckedChange = { item.onCheckedChange(!item.checked) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.icon,
                                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                        contentDescription = null,
                                    )
                                },
                                checkedLeadingIcon = {
                                    Icon(
                                        imageVector = resolvedCheckedIcon,
                                        modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                        contentDescription = null,
                                    )
                                },
                                trailingIcon = if (item.checked) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            modifier = Modifier.size(MenuDefaults.TrailingIconSize),
                                            contentDescription = null,
                                        )
                                    }
                                } else null,
                            )
                        }
                    }

                    if (groupIndex != groupCount - 1) {
                        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                    }
                }
            }
        }
    }
}
