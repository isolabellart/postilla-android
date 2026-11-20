package com.darkwisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

internal enum class ArticleMarkdownFormat {
    HEADING_2, BOLD, ITALIC, QUOTE, LIST
}

internal data class ArticleMarkdownEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

/** Applies Markdown to the current selection or inserts an empty format at the cursor. */
internal fun formatArticleMarkdownSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    format: ArticleMarkdownFormat
): ArticleMarkdownEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val hasSelection = start < end

    val inlineMarkers = when (format) {
        ArticleMarkdownFormat.BOLD -> "**" to "**"
        ArticleMarkdownFormat.ITALIC -> "*" to "*"
        else -> null
    }
    if (inlineMarkers != null) {
        val (opening, closing) = inlineMarkers
        if (hasSelection) {
            val replacement = opening + text.substring(start, end) + closing
            val updated = text.replaceRange(start, end, replacement)
            return ArticleMarkdownEdit(updated, start + opening.length, start + opening.length + end - start)
        }
        val updated = text.substring(0, start) + opening + closing + text.substring(start)
        val cursor = start + opening.length
        return ArticleMarkdownEdit(updated, cursor, cursor)
    }

    val blockMarker = when (format) {
        ArticleMarkdownFormat.HEADING_2 -> "## "
        ArticleMarkdownFormat.QUOTE -> "> "
        ArticleMarkdownFormat.LIST -> "- "
        else -> error("Unsupported Markdown format: $format")
    }
    if (hasSelection) {
        val selected = text.substring(start, end)
        val replacement = selected.split("\n").joinToString("\n") { line ->
            if (line.isEmpty()) blockMarker.trimEnd() else blockMarker + line
        }
        val updated = text.replaceRange(start, end, replacement)
        return ArticleMarkdownEdit(updated, start, start + replacement.length)
    }

    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val updated = text.substring(0, lineStart) + blockMarker + text.substring(lineStart)
    val cursor = start + blockMarker.length
    return ArticleMarkdownEdit(updated, cursor, cursor)
}

internal sealed interface ArticleMarkdownBlock {
    data class Heading(val level: Int, val text: String) : ArticleMarkdownBlock
    data class Paragraph(val text: String) : ArticleMarkdownBlock
    data class Quote(val text: String) : ArticleMarkdownBlock
    data class ListItem(val ordered: Boolean, val number: Int?, val text: String) : ArticleMarkdownBlock
    data class Code(val text: String) : ArticleMarkdownBlock
    data object Divider : ArticleMarkdownBlock
}

private val markdownHeadingRegex = Regex("""^(#{1,6})\s+(.+)$""")
private val markdownOrderedListRegex = Regex("""^(\d+)[.)]\s+(.+)$""")
private val markdownUnorderedListRegex = Regex("""^[-*+]\s+(.+)$""")
private val markdownInlineRegex = Regex("""(\*\*.+?\*\*|~~.+?~~|`[^`]+`|(?<!\*)\*[^*\n]+\*)""")
private val markdownRuleRegex = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        line.startsWith("#") || line.startsWith(">") || line.startsWith("```") ||
            markdownOrderedListRegex.matches(line) || markdownUnorderedListRegex.matches(line) ||
            markdownRuleRegex.matches(line)

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++

            line.startsWith("```") -> {
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    codeLines += lines[index]
                    index++
                }
                if (index < lines.size) index++
                blocks += ArticleMarkdownBlock.Code(codeLines.joinToString("\n"))
            }

            markdownHeadingRegex.matches(line) -> {
                val match = markdownHeadingRegex.matchEntire(line)!!
                blocks += ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                index++
            }

            markdownRuleRegex.matches(line) -> {
                blocks += ArticleMarkdownBlock.Divider
                index++
            }

            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines += lines[index].trim().removePrefix(">").trimStart()
                    index++
                }
                blocks += ArticleMarkdownBlock.Quote(quoteLines.joinToString(" "))
            }

            markdownOrderedListRegex.matches(line) -> {
                val match = markdownOrderedListRegex.matchEntire(line)!!
                blocks += ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2])
                index++
            }

            markdownUnorderedListRegex.matches(line) -> {
                val match = markdownUnorderedListRegex.matchEntire(line)!!
                blocks += ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1])
                index++
            }

            else -> {
                val paragraphLines = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraphLines += candidate
                    index++
                }
                if (paragraphLines.isNotEmpty()) {
                    blocks += ArticleMarkdownBlock.Paragraph(paragraphLines.joinToString(" "))
                } else {
                    index++
                }
            }
        }
    }
    return blocks
}

@Composable
private fun buildArticleInlineMarkdown(text: String) : androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
    var cursor = 0
    markdownInlineRegex.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("~~") && token.endsWith("~~") ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
            token.startsWith("`") && token.endsWith("`") ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(token.substring(1, token.length - 1))
                }
            token.startsWith("*") && token.endsWith("*") ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(token.substring(1, token.length - 1)) }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier.width(3.dp).heightIn(min = 24.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            buildArticleInlineMarkdown(block.text),
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is ArticleMarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (block.ordered) "${block.number ?: 1}." else "•",
                            modifier = Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                is ArticleMarkdownBlock.Code -> Text(
                    text = block.text,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ArticleMarkdownBlock.Divider ->
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}
