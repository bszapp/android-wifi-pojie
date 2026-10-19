package io.github.bszapp.wifitoolbox.ui.component

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 日志页与日志sheet共用的逐行懒加载视图，支持双指缩放和关闭自动换行后的横向滚动。 */
@Composable
internal fun <T> RawLogView(
    entries: List<T>,
    text: (T) -> String,
    listState: LazyListState,
    softWrap: Boolean,
    scrollBehavior: ScrollBehavior? = null,
    topPadding: Dp = 0.dp,
    startPadding: Dp = 0.dp,
    endPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier.fillMaxSize(),
    fontSizeSp: Float? = null,
    onFontSizeChanged: (Float) -> Unit = {},
) {
    var fontSize by remember { mutableFloatStateOf(fontSizeSp ?: 13f) }
    val configuredFontSize by rememberUpdatedState(fontSizeSp)
    val fontSizeChanged by rememberUpdatedState(onFontSizeChanged)
    var multiplePointersPressed by remember { mutableStateOf(false) }
    val horizontalScrollState = rememberScrollState()
    val transformableState = rememberTransformableState { _, zoomChange, _, _ ->
        fontSize = (fontSize * zoomChange).coerceIn(6f, 32f)
    }
    LaunchedEffect(fontSizeSp) {
        fontSizeSp?.let { fontSize = it.coerceIn(6f, 32f) }
    }
    LaunchedEffect(transformableState) {
        snapshotFlow { transformableState.isTransformInProgress }.collect { transforming ->
            if (!transforming && configuredFontSize != null && fontSize != configuredFontSize) {
                fontSizeChanged(fontSize)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            multiplePointersPressed = event.changes.count { it.pressed } > 1
                        }
                    }
                } finally {
                    multiplePointersPressed = false
                }
            }
            .padding(horizontal = 12.dp)
            .transformable(transformableState)
            .overScrollVertical()
            .scrollEndHaptic()
            .then(scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier)
            .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState)),
        state = listState,
        contentPadding = PaddingValues(
            top = topPadding, start = startPadding, end = endPadding, bottom = bottomPadding,
        ),
        userScrollEnabled = !multiplePointersPressed,
        overscrollEffect = null,
    ) {
        items(count = entries.size) { index ->
            Text(
                text = text(entries[index]),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                softWrap = softWrap,
                maxLines = if (softWrap) Int.MAX_VALUE else 1,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.body2,
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
