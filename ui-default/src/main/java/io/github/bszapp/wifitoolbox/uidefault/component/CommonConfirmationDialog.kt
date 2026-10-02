package io.github.bszapp.wifitoolbox.uidefault.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun CommonConfirmationDialog(
    show: Boolean,
    title: String,
    content: String,
    cancelButtonText: String = "取消",
    confirmButtonText: String = "确定",
    onDismissRequest: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onDismissFinished: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = content,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        content = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = cancelButtonText,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(20.dp))
                TextButton(
                    text = confirmButtonText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        },
    )
}
