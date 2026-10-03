package top.yukonga.scripta.editor.highlight

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaScriptHighlighterTest {
    private val highlighter = JavaScriptHighlighter()

    private fun lex(vararg lines: String): List<LineHighlight> {
        var state: LineState? = highlighter.initialState
        return lines.map { line ->
            highlighter.highlightLine(line, state).also { state = it.exitState }
        }
    }

    private fun typeAt(line: String, result: LineHighlight, value: String, occurrence: Int = 0): TokenType? {
        var index = -1
        repeat(occurrence + 1) { index = line.indexOf(value, index + 1) }
        require(index >= 0) { "$value not found in $line" }
        return result.spans.firstOrNull { index in it.start until it.end }?.type
    }

    @Test
    fun overrideScriptDistinguishesKeywordsCallsPropertiesAndLiterals() {
        val line = "function main(config) { config.rules.unshift(\"DOMAIN,a.com,PROXY\"); return config; }"
        val result = lex(line).single()
        assertEquals(TokenType.Keyword, typeAt(line, result, "function"))
        assertEquals(TokenType.Function, typeAt(line, result, "main"))
        assertEquals(TokenType.Variable, typeAt(line, result, "config"))
        assertEquals(TokenType.Property, typeAt(line, result, "rules"))
        assertEquals(TokenType.Property, typeAt(line, result, "unshift"))
        assertEquals(TokenType.String, typeAt(line, result, "DOMAIN"))
        assertEquals(TokenType.Keyword, typeAt(line, result, "return"))
    }

    @Test
    fun commentsDoNotStartInsideStringsOrRegex() {
        val line = "const url = \"https://host/#route\"; const re = /https?:\\/\\/[^/]+/gi; // note"
        val result = lex(line).single()
        assertEquals(TokenType.String, typeAt(line, result, "https://host"))
        assertEquals(TokenType.Regex, typeAt(line, result, "/https?"))
        assertEquals(TokenType.Comment, typeAt(line, result, "// note"))
    }

    @Test
    fun multilineBlockCommentResumesCodeAfterClose() {
        val lines = arrayOf("const a = 1; /* start", "still comment * /", "end */ const b = true;")
        val results = lex(*lines)
        assertEquals(TokenType.Comment, typeAt(lines[0], results[0], "/* start"))
        assertEquals(TokenType.Comment, typeAt(lines[1], results[1], "still comment"))
        assertEquals(TokenType.Comment, typeAt(lines[2], results[2], "end */"))
        assertEquals(TokenType.Keyword, typeAt(lines[2], results[2], "const"))
        assertEquals(TokenType.Boolean, typeAt(lines[2], results[2], "true"))
    }

    @Test
    fun quoteEscapesAndContinuationDoNotLeakIntoFollowingCode() {
        val lines = arrayOf("const s = \"a\\\"b\\", "continued\"; const n = null;", "const value = 'unfinished", "const after = 1;")
        val results = lex(*lines)
        assertEquals(TokenType.Escape, typeAt(lines[0], results[0], "\\\""))
        assertEquals(TokenType.String, typeAt(lines[1], results[1], "continued"))
        assertEquals(TokenType.Null, typeAt(lines[1], results[1], "null"))
        assertEquals(TokenType.Keyword, typeAt(lines[3], results[3], "const"))
    }

    @Test
    fun emptyLineEndsAnUnterminatedStringContinuation() {
        val lines = arrayOf("const s = 'continued\\", "", "const next = 1;")
        val results = lex(*lines)
        assertEquals(TokenType.Keyword, typeAt(lines[2], results[2], "const"))
    }

    @Test
    fun nestedMultilineTemplatesReturnToTheCorrectFrame() {
        val lines = arrayOf(
            "const output = `head ${'$'}{({value: `inner ${'$'}{",
            "name}`}).value} tail` + 1;",
        )
        val results = lex(*lines)
        assertEquals(TokenType.String, typeAt(lines[0], results[0], "head"))
        assertEquals(TokenType.Property, typeAt(lines[0], results[0], "value"))
        assertEquals(TokenType.String, typeAt(lines[0], results[0], "inner"))
        assertEquals(TokenType.Variable, typeAt(lines[1], results[1], "name"))
        assertEquals(TokenType.Property, typeAt(lines[1], results[1], "value"))
        assertEquals(TokenType.String, typeAt(lines[1], results[1], "tail"))
        assertEquals(TokenType.Number, typeAt(lines[1], results[1], "1"))
    }

    @Test
    fun escapedInterpolationRemainsTemplateText() {
        val line = "const text = `literal \\${'$'}{name} and ${'$'}{name}`;"
        val result = lex(line).single()
        assertEquals(TokenType.String, typeAt(line, result, "{name}"))
        assertEquals(TokenType.Variable, typeAt(line, result, "name", occurrence = 1))
        assertEquals(TokenType.Escape, typeAt(line, result, "\\${'$'}"))
    }

    @Test
    fun regexAndDivisionUseExpressionContextAcrossLines() {
        val lines = arrayOf(
            "const a = 8 / 2;",
            "return",
            "/[/\\/]+/giu.test(path);",
            "if (ready) /ok/.test(path);",
            "call(value) / 3;",
        )
        val results = lex(*lines)
        assertEquals(TokenType.Operator, typeAt(lines[0], results[0], "/"))
        assertEquals(TokenType.Regex, typeAt(lines[2], results[2], "/[/"))
        assertEquals(TokenType.Regex, typeAt(lines[3], results[3], "/ok/"))
        assertEquals(TokenType.Operator, typeAt(lines[4], results[4], "/"))
    }

    @Test
    fun adjacentOperatorsDoNotSwallowCommentsOrRegex() {
        val lines = arrayOf("const a = left+// note", "right+/ok/.test(path);", "const b = x++/* block */+1;")
        val results = lex(*lines)
        assertEquals(TokenType.Operator, typeAt(lines[0], results[0], "+"))
        assertEquals(TokenType.Comment, typeAt(lines[0], results[0], "// note"))
        assertEquals(TokenType.Regex, typeAt(lines[1], results[1], "/ok/"))
        assertEquals(TokenType.Comment, typeAt(lines[2], results[2], "/* block */"))
    }

    @Test
    fun blocksAndObjectLiteralsHaveDifferentRegexContexts() {
        val lines = arrayOf("if (ready) { run(); } /ok/.test(name);", "const object = {value: 1} / 2;")
        val results = lex(*lines)
        assertEquals(TokenType.Regex, typeAt(lines[0], results[0], "/ok/"))
        assertEquals(TokenType.Operator, typeAt(lines[1], results[1], "/"))
    }

    @Test
    fun optionalMemberNamesDoNotBehaveLikeKeywords() {
        val line = "const value = obj?.return(value) / 2;"
        val result = lex(line).single()
        assertEquals(TokenType.Operator, typeAt(line, result, "?."))
        assertEquals(TokenType.Property, typeAt(line, result, "return"))
        assertEquals(TokenType.Operator, typeAt(line, result, "/"))
    }

    @Test
    fun numericLiteralsAndPrivateFields() {
        val line = "const values = [0xffn, 0b1010, 0o77, 1_000.5e-2, .5]; this.#field = null;"
        val result = lex(line).single()
        for (number in listOf("0xffn", "0b1010", "0o77", "1_000.5e-2", ".5")) {
            assertEquals(TokenType.Number, typeAt(line, result, number), number)
        }
        assertEquals(TokenType.Property, typeAt(line, result, "#field"))
        assertEquals(TokenType.Null, typeAt(line, result, "null"))
    }

    @Test
    fun hashbangAppliesOnlyToTheFirstLine() {
        val lines = arrayOf("#!/usr/bin/env node", "const value = 1;", "#!invalid")
        val results = lex(*lines)
        assertEquals(TokenType.Comment, results[0].spans.single().type)
        assertEquals(TokenType.Keyword, typeAt(lines[1], results[1], "const"))
        assertFalse(results[2].spans.any { it.type == TokenType.Comment })
    }

    @Test
    fun editingTemplateOpeningRecomputesFollowingLines() {
        val lines = mutableListOf("const s = `", "inside", "`; const n = 1;")
        val cache = HighlightCache(highlighter)
        val getLine: (Int) -> String = { lines[it] }
        assertEquals(TokenType.String, cache.spansForLine(1, getLine).single().type)
        lines[0] = "const s = 1;"
        cache.invalidate(0, 1, structural = false)
        assertEquals(TokenType.Variable, cache.spansForLine(1, getLine).single().type)
    }

    @Test
    fun equivalentLineStatesHaveStructuralEqualityForCacheConvergence() {
        val entry = highlighter.initialState
        val a = highlighter.highlightLine("const s = `text ${'$'}{", entry)
        val b = highlighter.highlightLine("const s = `text ${'$'}{", entry)
        assertEquals(a.exitState, b.exitState)
    }

    @Test
    fun sampleLinesKeepSpansSortedAndWithinBounds() {
        val lines = arrayOf(
            "const main = (config) => ({ ...config, rules: [...config.rules] });",
            "/* comment", "still comment */ const a = /[a-z\\/]+/i;",
            "const output = `text ${'$'}{value ? `nested ${'$'}{name}` : null}`;",
            "obj?.property ??= 0x1fn; // trailing",
        )
        lex(*lines).zip(lines).forEach { (result, line) ->
            var end = 0
            for (span in result.spans) {
                assertTrue(span.start >= end, "overlapping spans in $line: ${result.spans}")
                assertTrue(span.end > span.start && span.end <= line.length, "invalid span in $line: $span")
                end = span.end
            }
        }
    }

    @Test
    fun mixedPunctuationNeverProducesOverlappingSpans() {
        val random = Random(17)
        val alphabet = "ab01/'\"`#{}[]()+=?*\\${'$'} \t"
        val lines = Array(160) { String(CharArray(120) { alphabet[random.nextInt(alphabet.length)] }) }
        lex(*lines).zip(lines).forEach { (result, line) ->
            var end = 0
            result.spans.forEach { span ->
                assertTrue(span.start >= end && span.end <= line.length && span.end > span.start)
                end = span.end
            }
        }
    }
}
