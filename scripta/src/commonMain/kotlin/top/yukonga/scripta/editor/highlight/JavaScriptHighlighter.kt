package top.yukonga.scripta.editor.highlight

/**
 * JavaScript 词法高亮。模板字符串 / 插值、块注释及续行字符串通过不可变行状态传递；
 * 正则与除法按前一个 token 是否能结束表达式区分，不做语法或语义解析。
 */
class JavaScriptHighlighter : SyntaxHighlighter {
    override val lineCommentPrefix: String = "//"
    override val initialState: LineState = State(firstLine = true)

    override fun highlightLine(text: String, entryState: LineState?): LineHighlight =
        Scanner(text, entryState as? State ?: initialState as State).scan()

    private data class State(
        val frames: List<Frame> = listOf(Frame.Code()),
        val blockComment: Boolean = false,
        val continuedQuote: Char? = null,
        val firstLine: Boolean = false,
    ) : LineState

    private sealed interface Frame {
        data class Code(
            val interpolation: Boolean = false,
            val expressionAllowed: Boolean = true,
            val statementStart: Boolean = true,
            val afterMember: Boolean = false,
            val pendingControl: Boolean = false,
            val afterArrow: Boolean = false,
            val parens: List<Boolean> = emptyList(),
            val braces: List<Boolean> = emptyList(),
        ) : Frame

        data object Template : Frame
    }

    private sealed interface Context {
        fun snapshot(): Frame

        class Code(state: Frame.Code) : Context {
            val interpolation = state.interpolation
            var expressionAllowed = state.expressionAllowed
            var statementStart = state.statementStart
            var afterMember = state.afterMember
            var pendingControl = state.pendingControl
            var afterArrow = state.afterArrow
            val parens = state.parens.toMutableList()
            val braces = state.braces.toMutableList()

            override fun snapshot() = Frame.Code(
                interpolation, expressionAllowed, statementStart, afterMember,
                pendingControl, afterArrow, parens.toList(), braces.toList(),
            )
        }

        data object Template : Context {
            override fun snapshot(): Frame = Frame.Template
        }
    }

    private class Scanner(private val text: String, entry: State) {
        private val spans = ArrayList<HighlightSpan>()
        private val frames = entry.frames.mapTo(ArrayList()) { frame ->
            when (frame) {
                is Frame.Code -> Context.Code(frame)
                Frame.Template -> Context.Template
            }
        }
        private var blockComment = entry.blockComment
        private var continuedQuote = entry.continuedQuote
        private val firstLine = entry.firstLine
        private var i = 0
        private var templateOpening = false

        fun scan(): LineHighlight {
            if (text.isEmpty() && continuedQuote != null) continuedQuote = null
            if (firstLine && text.startsWith("#!")) {
                span(0, text.length, TokenType.Comment)
                return done()
            }
            while (i < text.length) {
                when {
                    blockComment -> scanBlockComment()
                    continuedQuote != null -> scanQuoted(continuedQuote!!, opening = false)
                    frames.last() == Context.Template -> scanTemplate()
                    else -> scanCode(frames.last() as Context.Code)
                }
            }
            return done()
        }

        private fun scanCode(code: Context.Code) {
            val c = text[i]
            if (c.isWhitespace()) {
                i++
                return
            }
            if (text.startsWith("//", i)) {
                span(i, text.length, TokenType.Comment)
                i = text.length
                return
            }
            if (text.startsWith("/*", i)) {
                blockComment = true
                scanBlockComment()
                return
            }
            if (c == '\'' || c == '"') {
                scanQuoted(c, opening = true)
                valueEnded(code)
                return
            }
            if (c == '`') {
                frames.add(Context.Template)
                templateOpening = true
                code.pendingControl = false
                return
            }
            if (c == '/' && code.expressionAllowed && scanRegex()) {
                valueEnded(code)
                return
            }
            if (c == '#' && i + 1 < text.length && isIdentifierStart(text[i + 1])) {
                val start = i++
                scanIdentifierTail()
                span(start, i, TokenType.Property)
                valueEnded(code)
                return
            }
            if (isIdentifierStart(c) || identifierEscapeEnd(i) > i) {
                scanIdentifier(code)
                return
            }
            if (c.isDigit() || c == '.' && i + 1 < text.length && text[i + 1].isDigit()) {
                scanNumber()
                valueEnded(code)
                return
            }
            scanSymbol(code)
        }

        private fun scanBlockComment() {
            val end = text.indexOf("*/", i)
            if (end < 0) {
                span(i, text.length, TokenType.Comment)
                i = text.length
            } else {
                span(i, end + 2, TokenType.Comment)
                i = end + 2
                blockComment = false
            }
        }

        private fun scanQuoted(quote: Char, opening: Boolean) {
            var segmentStart = i
            if (opening) i++
            continuedQuote = null
            while (i < text.length) {
                when (text[i]) {
                    '\\' -> {
                        span(segmentStart, i, TokenType.String)
                        val end = (i + 2).coerceAtMost(text.length)
                        span(i, end, TokenType.Escape)
                        continuedQuote = if (end == text.length && end == i + 1) quote else null
                        i = end
                        segmentStart = i
                    }
                    quote -> {
                        i++
                        span(segmentStart, i, TokenType.String)
                        return
                    }
                    else -> i++
                }
            }
            span(segmentStart, i, TokenType.String)
        }

        private fun scanTemplate() {
            var segmentStart = i
            if (templateOpening) {
                i++
                templateOpening = false
            }
            while (i < text.length) {
                when {
                    text[i] == '\\' -> {
                        span(segmentStart, i, TokenType.String)
                        val end = (i + 2).coerceAtMost(text.length)
                        span(i, end, TokenType.Escape)
                        i = end
                        segmentStart = i
                    }
                    text.startsWith("${'$'}{", i) -> {
                        span(segmentStart, i, TokenType.String)
                        span(i, i + 2, TokenType.Punctuation)
                        i += 2
                        frames.add(Context.Code(Frame.Code(interpolation = true, statementStart = false)))
                        return
                    }
                    text[i] == '`' -> {
                        i++
                        span(segmentStart, i, TokenType.String)
                        frames.removeAt(frames.lastIndex)
                        valueEnded(frames.last() as Context.Code)
                        return
                    }
                    else -> i++
                }
            }
            span(segmentStart, i, TokenType.String)
        }

        private fun scanRegex(): Boolean {
            val start = i
            var j = i + 1
            var inClass = false
            while (j < text.length) {
                when (text[j]) {
                    '\\' -> j = (j + 2).coerceAtMost(text.length)
                    '[' -> { inClass = true; j++ }
                    ']' -> { inClass = false; j++ }
                    '/' -> {
                        if (inClass) {
                            j++
                        } else {
                            j++
                            while (j < text.length && isIdentifierPart(text[j])) j++
                            span(start, j, TokenType.Regex)
                            i = j
                            return true
                        }
                    }
                    else -> j++
                }
            }
            return false
        }

        private fun scanIdentifier(code: Context.Code) {
            val start = i
            if (text[i] == '\\') i = identifierEscapeEnd(i) else i++
            scanIdentifierTail()
            val word = text.substring(start, i)
            val next = nextNonSpace(i)
            val type = when {
                code.afterMember -> TokenType.Property
                word == "true" || word == "false" -> TokenType.Boolean
                word == "null" -> TokenType.Null
                word in KEYWORDS -> TokenType.Keyword
                next < text.length && text[next] == ':' -> TokenType.Property
                next < text.length && text[next] == '(' -> TokenType.Function
                word.firstOrNull()?.isUpperCase() == true -> TokenType.Type
                else -> TokenType.Variable
            }
            span(start, i, type)
            val keyword = type == TokenType.Keyword
            code.afterMember = false
            code.pendingControl = keyword && word in CONTROL_KEYWORDS
            code.afterArrow = false
            code.expressionAllowed = keyword && word in EXPRESSION_KEYWORDS
            code.statementStart = keyword && word in STATEMENT_KEYWORDS
        }

        private fun scanIdentifierTail() {
            while (i < text.length) {
                when {
                    isIdentifierPart(text[i]) -> i++
                    identifierEscapeEnd(i) > i -> i = identifierEscapeEnd(i)
                    else -> return
                }
            }
        }

        private fun scanNumber() {
            val start = i
            if (text[i] == '0' && i + 1 < text.length && text[i + 1] in "xXbBoO") {
                val radix = when (text[i + 1].lowercaseChar()) {
                    'x' -> 16
                    'b' -> 2
                    else -> 8
                }
                i += 2
                while (i < text.length && (text[i] == '_' || text[i].digitToIntOrNull(radix) != null)) i++
                if (i < text.length && text[i] == 'n') i++
            } else {
                while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
                var fractional = false
                if (i < text.length && text[i] == '.') {
                    fractional = true
                    i++
                    while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
                }
                if (i < text.length && text[i] in "eE") {
                    var j = i + 1
                    if (j < text.length && text[j] in "+-") j++
                    if (j < text.length && text[j].isDigit()) {
                        fractional = true
                        i = j + 1
                        while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
                    }
                }
                if (!fractional && i < text.length && text[i] == 'n') i++
            }
            span(start, i, TokenType.Number)
        }

        private fun scanSymbol(code: Context.Code) {
            val start = i
            val c = text[i++]
            val pendingControl = code.pendingControl
            code.afterMember = false
            code.pendingControl = false
            when (c) {
                '(' -> {
                    code.parens.add(pendingControl)
                    code.expressionAllowed = true
                    code.statementStart = false
                }
                ')' -> {
                    val control = if (code.parens.isEmpty()) false else code.parens.removeAt(code.parens.lastIndex)
                    code.expressionAllowed = control
                    code.statementStart = control
                }
                '{' -> {
                    val block = code.statementStart || code.afterArrow || !code.expressionAllowed
                    code.braces.add(block)
                    code.expressionAllowed = true
                    code.statementStart = block
                }
                '}' -> {
                    if (code.interpolation && code.braces.isEmpty()) {
                        span(start, i, TokenType.Punctuation)
                        frames.removeAt(frames.lastIndex)
                        return
                    }
                    val block = if (code.braces.isEmpty()) true else code.braces.removeAt(code.braces.lastIndex)
                    code.expressionAllowed = block
                    code.statementStart = block
                }
                '[' -> {
                    code.expressionAllowed = true
                    code.statementStart = false
                }
                ']' -> valueEnded(code)
                ';' -> {
                    code.expressionAllowed = true
                    code.statementStart = true
                }
                ',', ':' -> {
                    code.expressionAllowed = true
                    code.statementStart = false
                }
                '.' -> {
                    if (text.startsWith("..", i)) i += 2
                    else code.afterMember = true
                    code.expressionAllowed = !code.afterMember
                    code.statementStart = false
                }
                else -> {
                    val operator = OPERATORS.firstOrNull { token ->
                        text.startsWith(token, start) &&
                            (token != "?." || start + 2 >= text.length || !text[start + 2].isDigit())
                    } ?: c.toString()
                    i = start + operator.length
                    when (operator) {
                        "?." -> code.afterMember = true
                        "++", "--" -> Unit
                        else -> code.expressionAllowed = true
                    }
                    code.afterArrow = operator == "=>"
                    code.statementStart = false
                    span(start, i, TokenType.Operator)
                    return
                }
            }
            if (c != '{') code.afterArrow = false
            span(start, i, TokenType.Punctuation)
        }

        private fun valueEnded(code: Context.Code) {
            code.expressionAllowed = false
            code.statementStart = false
            code.afterMember = false
            code.pendingControl = false
            code.afterArrow = false
        }

        private fun nextNonSpace(from: Int): Int {
            var j = from
            while (j < text.length && text[j].isWhitespace()) j++
            return j
        }

        private fun identifierEscapeEnd(from: Int): Int {
            if (from + 2 >= text.length || text[from] != '\\' || text[from + 1] != 'u') return from
            if (text[from + 2] == '{') {
                var j = from + 3
                while (j < text.length && text[j].isHexDigit()) j++
                return if (j > from + 3 && j < text.length && text[j] == '}') j + 1 else from
            }
            val end = from + 6
            return if (end <= text.length && (from + 2 until end).all { text[it].isHexDigit() }) end else from
        }

        private fun span(start: Int, end: Int, type: TokenType) {
            if (end > start) spans.add(HighlightSpan(start, end, type))
        }

        private fun done() = LineHighlight(
            spans,
            State(frames.map(Context::snapshot), blockComment, continuedQuote, firstLine = false),
        )
    }

    private companion object {
        val CONTROL_KEYWORDS = setOf("if", "while", "for", "switch", "catch", "with")
        val STATEMENT_KEYWORDS = setOf("else", "do", "try", "finally")
        val EXPRESSION_KEYWORDS = CONTROL_KEYWORDS + STATEMENT_KEYWORDS + setOf(
            "return", "throw", "case", "delete", "void", "typeof", "new", "instanceof",
            "in", "of", "await", "yield", "extends", "default", "import", "export",
        )
        val KEYWORDS = EXPRESSION_KEYWORDS + setOf(
            "break", "continue", "debugger", "function", "class", "const", "let", "var",
            "this", "super", "async", "static", "get", "set", "from", "as", "try",
        )
        val OPERATORS = listOf(
            ">>>=", "===", "!==", "**=", "&&=", "||=", "??=", ">>>",
            "<<=", ">>=", "=>", "==", "!=", "<=", ">=", "**", "&&", "||", "??",
            "?.", "++", "--", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        ).sortedByDescending(String::length)

        fun isIdentifierStart(c: Char): Boolean = c == '_' || c == '$' || c.isLetter()
        fun isIdentifierPart(c: Char): Boolean = isIdentifierStart(c) || c.isDigit()
        fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
