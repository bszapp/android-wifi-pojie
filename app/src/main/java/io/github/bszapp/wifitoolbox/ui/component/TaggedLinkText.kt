package io.github.bszapp.wifitoolbox.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaggedLinkText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    textAlign: TextAlign = TextAlign.Start
) {
    val context = LocalContext.current
    val segments = remember(text) { parseTaggedLinks(text) }
    val arrangement = when (textAlign) {
        TextAlign.Center -> Arrangement.Center
        TextAlign.End, TextAlign.Right -> Arrangement.End
        else -> Arrangement.Start
    }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = arrangement
    ) {
        segments.forEach { segment ->
            if (segment.isLink) {
                Text(
                    text = segment.text,
                    style = style.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    ),
                    modifier = Modifier.clickable {
                        segment.href?.let { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                        segment.openPackage?.let { packageName ->
                            runCatching {
                                context.packageManager
                                    .getLaunchIntentForPackage(packageName)
                                    ?.let { context.startActivity(it) }
                            }
                        }
                    }
                )
            } else {
                Text(
                    text = segment.text,
                    style = style
                )
            }
        }
    }
}

private data class TaggedSegment(
    val text: String,
    val href: String? = null,
    val openPackage: String? = null
) {
    val isLink: Boolean get() = href != null || openPackage != null
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
