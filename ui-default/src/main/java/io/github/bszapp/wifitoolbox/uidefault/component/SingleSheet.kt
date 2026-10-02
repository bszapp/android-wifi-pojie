package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** 一个主 UI 会话中只有一个显示中的 Sheet；退场期间仍持有显示权。 */
class SheetPresentationManager {
    var owner by mutableStateOf<Any?>(null)
        private set
    fun acquire(token: Any) { if (owner == null) owner = token }
    fun release(token: Any) { if (owner === token) owner = null }
}

val LocalSheetPresentationManager = staticCompositionLocalOf<SheetPresentationManager> {
    error("Sheet 必须位于主 UI 会话宿主内")
}

class SheetLease internal constructor(
    private val manager: SheetPresentationManager,
    internal val token: Any,
) {
    val ownsPresentation: Boolean get() = manager.owner === token
    fun release() = manager.release(token)
}

@Composable
fun rememberSheetLease(show: Boolean): SheetLease {
    val manager = LocalSheetPresentationManager.current
    val lease = remember(manager) { SheetLease(manager, Any()) }
    LaunchedEffect(show, manager.owner) {
        if (show) manager.acquire(lease.token)
    }
    DisposableEffect(lease) { onDispose { lease.release() } }
    return lease
}

@Composable
fun SingleOverlayBottomSheet(
    show: Boolean,
    title: String? = null,
    modifier: Modifier = Modifier,
    allowDismiss: Boolean = true,
    enableNestedScroll: Boolean = true,
    renderInRootScaffold: Boolean = true,
    onDismissRequest: () -> Unit,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val lease = rememberSheetLease(show)
    OverlayBottomSheet(
        show = show && lease.ownsPresentation,
        title = title,
        modifier = modifier,
        allowDismiss = allowDismiss,
        enableNestedScroll = enableNestedScroll,
        renderInRootScaffold = renderInRootScaffold,
        onDismissRequest = onDismissRequest,
        onDismissFinished = {
            if (lease.ownsPresentation) {
                lease.release()
                onDismissFinished?.invoke()
            }
        },
        content = content,
    )
}
