package io.github.bszapp.wifitoolbox.uidefault.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiConfirmationDialogState(
    val id: Long,
    val show: Boolean,
    val title: String,
    val content: String,
    val cancelButtonText: String = "取消",
    val confirmButtonText: String = "确定",
)

class UiConfirmationDialogManager(
    private val scope: CoroutineScope,
) {
    private enum class Result {
        DISMISSED,
        CANCELLED,
        CONFIRMED,
    }

    private class Entry(
        var state: UiConfirmationDialogState,
        val onDismissed: suspend () -> Unit,
        val onCancelled: suspend () -> Unit,
        val onConfirmed: suspend () -> Unit,
        val onDismissFinished: suspend () -> Unit,
    ) {
        var result: Result? = null
        var finishFallbackJob: Job? = null
    }

    private val entries = linkedMapOf<Long, Entry>()
    private var nextDialogId = 1L

    private val _dialogs = MutableStateFlow<List<UiConfirmationDialogState>>(emptyList())
    val dialogs: StateFlow<List<UiConfirmationDialogState>> = _dialogs.asStateFlow()

    fun show(
        title: String,
        content: String,
        cancelButtonText: String = "取消",
        confirmButtonText: String = "确定",
        onDismissed: suspend () -> Unit = {},
        onCancelled: suspend () -> Unit = {},
        onConfirmed: suspend () -> Unit = {},
        onDismissFinished: suspend () -> Unit = {},
    ): Long {
        val id = nextDialogId++
        val entry = Entry(
            state = UiConfirmationDialogState(
                id = id,
                show = true,
                title = title,
                content = content,
                cancelButtonText = cancelButtonText,
                confirmButtonText = confirmButtonText,
            ),
            onDismissed = onDismissed,
            onCancelled = onCancelled,
            onConfirmed = onConfirmed,
            onDismissFinished = onDismissFinished,
        )
        entries[id] = entry
        publish()
        return id
    }

    fun dismiss(id: Long) {
        requestClose(id, Result.DISMISSED)
    }

    fun cancel(id: Long) {
        requestClose(id, Result.CANCELLED)
    }

    fun confirm(id: Long) {
        requestClose(id, Result.CONFIRMED)
    }

    fun onDismissFinished(id: Long) {
        finish(id)
    }

    private fun requestClose(id: Long, result: Result) {
        val entry = entries[id] ?: return
        if (entry.result != null) return

        entry.result = result
        entry.state = entry.state.copy(show = false)
        publish()

        entry.finishFallbackJob = scope.launch {
            delay(DISMISS_FINISH_FALLBACK_MILLIS)
            finish(id)
        }
    }

    private fun finish(id: Long) {
        val entry = entries.remove(id) ?: return
        entry.finishFallbackJob?.cancel()
        publish()

        scope.launch {
            when (entry.result) {
                Result.DISMISSED -> entry.onDismissed()
                Result.CANCELLED -> entry.onCancelled()
                Result.CONFIRMED -> entry.onConfirmed()
                null -> Unit
            }
            entry.onDismissFinished()
        }
    }

    private fun publish() {
        _dialogs.value = entries.values.map(Entry::state)
    }

    private companion object {
        const val DISMISS_FINISH_FALLBACK_MILLIS = 400L
    }
}
