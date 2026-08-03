package io.github.bszapp.wifitoolbox.contract.terminal

class TerminalOutputSanitizer {
    private enum class Mode {
        TEXT,
        ESC,
        CSI,
        OSC,
        OSC_ESC,
    }

    private var mode = Mode.TEXT

    fun consume(input: String): String {
        val display = StringBuilder(input.length)

        input.forEach { ch ->
            when (mode) {
                Mode.TEXT -> when (ch) {
                    '\u001B' -> mode = Mode.ESC
                    '\n', '\t' -> display.append(ch)
                    else -> {
                        if (ch >= ' ') {
                            display.append(ch)
                        }
                    }
                }

                Mode.ESC -> when (ch) {
                    '[' -> mode = Mode.CSI
                    ']' -> mode = Mode.OSC
                    else -> mode = Mode.TEXT
                }

                Mode.CSI -> {
                    if (ch in '@'..'~') {
                        mode = Mode.TEXT
                    }
                }

                Mode.OSC -> when (ch) {
                    '\u0007' -> mode = Mode.TEXT
                    '\u001B' -> mode = Mode.OSC_ESC
                }

                Mode.OSC_ESC -> {
                    mode = if (ch == '\\') {
                        Mode.TEXT
                    } else {
                        Mode.OSC
                    }
                }
            }
        }

        return display.toString()
    }
}

data class TerminalOutputUpdate(
    val completedLines: List<String>,
    val inputPrompt: String?,
    val inputPromptChanged: Boolean,
)

class TerminalOutputAccumulator {
    private val sanitizer = TerminalOutputSanitizer()
    private val incompleteLine = StringBuilder()

    fun consume(input: String): TerminalOutputUpdate {
        val previousPrompt = incompleteLine.toString().takeIf(String::isNotEmpty)
        val completedLines = mutableListOf<String>()

        sanitizer.consume(input).forEach { ch ->
            if (ch == '\n') {
                completedLines += incompleteLine.toString()
                incompleteLine.clear()
            } else {
                incompleteLine.append(ch)
            }
        }

        val inputPrompt = incompleteLine.toString().takeIf(String::isNotEmpty)
        return TerminalOutputUpdate(
            completedLines = completedLines,
            inputPrompt = inputPrompt,
            inputPromptChanged = previousPrompt != inputPrompt,
        )
    }
}
