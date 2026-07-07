package io.github.bszapp.wifitoolbox.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun TaggedLinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(text, linkColor) { buildTaggedAnnotatedString(text, linkColor) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier = modifier,
        contentAlignment = combinedAlignment(textAlign.toHorizontalAlignment(), verticalAlignment)
    ) {
        Text(
            text = annotatedText,
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(annotatedText) {
                    detectTapGestures { position: Offset ->
                        val layout = layoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        annotatedText.getStringAnnotations(TAG_HREF, offset, offset)
                            .firstOrNull()
                            ?.item
                            ?.let { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                                return@detectTapGestures
                            }

                        annotatedText.getStringAnnotations(TAG_OPEN, offset, offset)
                            .firstOrNull()
                            ?.item
                            ?.let { packageName ->
                                runCatching {
                                    context.packageManager
                                        .getLaunchIntentForPackage(packageName)
                                        ?.let { context.startActivity(it) }
                                }
                            }
                    }
                },
            style = style,
            color = color,
            textAlign = textAlign,
            overflow = overflow,
            softWrap = softWrap,
            maxLines = maxLines,
            minLines = minLines,
            onTextLayout = { result ->
                layoutResult = result
                onTextLayout(result)
            }
        )
    }
}

private data class TaggedSegment(
    val text: String,
    val href: String? = null,
    val openPackage: String? = null
) {
    val isLink: Boolean get() = href != null || openPackage != null
}

private fun buildTaggedAnnotatedString(input: String, linkColor: Color): AnnotatedString {
    val segments = parseTaggedLinks(input)
    return buildAnnotatedString {
        segments.forEach { segment ->
            val start = length
            if (segment.isLink) {
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(segment.text)
                }
                val end = length
                segment.href?.let { addStringAnnotation(TAG_HREF, it, start, end) }
                segment.openPackage?.let { addStringAnnotation(TAG_OPEN, it, start, end) }
            } else {
                append(segment.text)
            }
        }
    }
}

private fun parseTaggedLinks(input: String): List<TaggedSegment> {
    if (!input.contains("<a")) return listOf(TaggedSegment(input))

    val result = mutableListOf<TaggedSegment>()
    val tagRegex = Regex("<a\\s+([^>]*)>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val attrRegex = Regex("(href|open)\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    var cursor = 0

    tagRegex.findAll(input).forEach { match ->
        if (match.range.first > cursor) {
            result += TaggedSegment(input.substring(cursor, match.range.first))
        }

        val attrs = attrRegex.findAll(match.groupValues[1])
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        result += TaggedSegment(
            text = match.groupValues[2],
            href = attrs["href"],
            openPackage = attrs["open"]
        )
        cursor = match.range.last + 1
    }

    if (cursor < input.length) {
        result += TaggedSegment(input.substring(cursor))
    }

    return result
}

private fun TextAlign?.toHorizontalAlignment(): Alignment.Horizontal = when (this) {
    TextAlign.Center -> Alignment.CenterHorizontally
    TextAlign.End, TextAlign.Right -> Alignment.End
    else -> Alignment.Start
}

private fun combinedAlignment(
    horizontal: Alignment.Horizontal,
    vertical: Alignment.Vertical,
): Alignment = object : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
        return IntOffset(
            x = horizontal.align(size.width, space.width, layoutDirection),
            y = vertical.align(size.height, space.height)
        )
    }
}

private const val TAG_HREF = "href"
private const val TAG_OPEN = "open"
