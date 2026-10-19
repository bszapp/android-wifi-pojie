package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val CornerLarge = 12.dp

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

private sealed interface MenuHeadingPart {
    data class Glyph(val icon: ImageVector) : MenuHeadingPart
    data class Label(val text: String) : MenuHeadingPart
}

private sealed interface MenuGroupRow {
    val key: Any
    data class Heading(override val key: Any, val parts: List<MenuHeadingPart>) : MenuGroupRow
    data class Option(
        override val key: Any,
        val item: MenuItemConfig,
        val index: Int,
        val count: Int,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : MenuGroupRow
    data class Gap(override val key: Any) : MenuGroupRow
}

@Composable
fun ActionButtonGroupWithMenu(
    buttonConfig: ActionButtonConfig,
    menuGroups: List<MenuGroupConfig>,
    modifier: Modifier = Modifier,
    menuExpanded: Boolean = false,
    onMenuExpandedChange: (Boolean) -> Unit = {},
) {
    val customContainerColor = buttonConfig.containerColor
    val customContentColor = buttonConfig.contentColor
    val hasCustomColors = customContainerColor != null && customContentColor != null
    val leadingButtonColors = if (hasCustomColors) {
        ButtonDefaults.buttonColors(
            color = customContainerColor,
            contentColor = customContentColor,
        )
    } else {
        ButtonDefaults.buttonColors()
    }
    val trailingButtonColors = when {
        hasCustomColors -> ButtonDefaults.buttonColors(
            color = customContainerColor,
            contentColor = customContentColor,
        )

        menuExpanded -> ButtonDefaults.buttonColors(
            color = MiuixTheme.colorScheme.primaryContainer,
            contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
        )

        else -> ButtonDefaults.buttonColors()
    }

    val menuRows = buildList<MenuGroupRow> {
        menuGroups.forEachIndexed { groupIndex, group ->
            val groupKey = group.title to group.items.map { it.title }
            val headingParts = buildList<MenuHeadingPart> {
                group.icon?.let { add(MenuHeadingPart.Glyph(it)) }
                group.title?.let { add(MenuHeadingPart.Label(it)) }
            }
            if (groupIndex > 0) add(MenuGroupRow.Gap("gap" to groupKey))
            if (headingParts.isNotEmpty()) add(MenuGroupRow.Heading("heading" to groupKey, headingParts))
            group.items.forEachIndexed { index, item ->
                add(MenuGroupRow.Option(
                    key = listOf("option", groupKey, item.title, index),
                    item = item,
                    index = index,
                    count = group.items.size,
                    isFirst = groupIndex == 0 && index == 0,
                    isLast = groupIndex == menuGroups.lastIndex && index == group.items.lastIndex,
                ))
            }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── 左侧主操作按钮 ─────────────────────────────
        Button(
            onClick = { buttonConfig.onClick() },
            modifier = Modifier
                .requiredWidthIn(min = 0.dp)
                .height(40.dp),
            cornerRadius = CornerLarge,
            minWidth = 0.dp,
            colors = leadingButtonColors,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
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
                    Text(text, style = MiuixTheme.textStyles.body2)
                }
            }
        }

        // ── 右侧更多按钮 + 下拉菜单 ──────────────────────
        Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
            TooltipBox(
                text = "更多",
                positioning = TooltipAnchorPosition.Above,
            ) {
                Button(
                    onClick = { onMenuExpandedChange(!menuExpanded) },
                    modifier = Modifier
                        .requiredWidthIn(min = 0.dp)
                        .width(36.dp)
                        .height(40.dp),
                    cornerRadius = CornerLarge,
                    minWidth = 0.dp,
                    colors = trailingButtonColors,
                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp)
                            .align(Alignment.CenterVertically),
                    )
                }
            }

            OverlayListPopup(
                show = menuExpanded,
                popupPositionProvider = ListPopupDefaults.MenuPositionProvider,
                alignment = PopupPositionProvider.Align.TopEnd,
                onDismissRequest = { onMenuExpandedChange(false) },
            ) {
                ListPopupColumn {
                    menuRows.forEach { row ->
                        key(row.key) {
                            when (row) {
                                is MenuGroupRow.Gap -> Spacer(Modifier.height(8.dp))
                                is MenuGroupRow.Heading -> Row(
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    row.parts.forEach { part ->
                                        when (part) {
                                            is MenuHeadingPart.Glyph -> Icon(
                                                imageVector = part.icon,
                                                contentDescription = null,
                                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            is MenuHeadingPart.Label -> Text(
                                                text = part.text,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                style = MiuixTheme.textStyles.footnote2,
                                            )
                                        }
                                    }
                                }
                                is MenuGroupRow.Option -> {
                                    val item = row.item
                                    val resolvedIcon = if (item.checked) item.checkedIcon ?: item.icon else item.icon
                                    DropdownImpl(
                                        item = DropdownItem(
                                            text = item.title,
                                            selected = item.checked,
                                            icon = { iconModifier -> Icon(resolvedIcon, null, iconModifier.size(20.dp)) },
                                        ),
                                        optionSize = row.count,
                                        index = row.index,
                                        isSelected = item.checked,
                                        isFirst = row.isFirst,
                                        isLast = row.isLast,
                                        onSelectedIndexChange = { item.onCheckedChange(!item.checked) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
