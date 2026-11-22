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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import io.ratex.RaTeXView

internal enum class ArticleMarkdownFormat {
    HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6,
    BOLD, ITALIC, STRIKE, INLINE_CODE, FENCED_CODE,
    QUOTE, LIST, ORDERED_LIST, TASK_LIST, TABLE, DIVIDER,
    LINK, IMAGE, FOOTNOTE, MATH
}

internal data class ArticleMarkdownEdit(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

internal fun formatArticleMarkdownSelection(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    format: ArticleMarkdownFormat
): ArticleMarkdownEdit {
    val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val hasSelection = start < end
    val selected = if (hasSelection) text.substring(start, end) else ""

    val inlineMarkers = when (format) {
        ArticleMarkdownFormat.BOLD -> "**" to "**"
        ArticleMarkdownFormat.ITALIC -> "*" to "*"
        ArticleMarkdownFormat.STRIKE -> "~~" to "~~"
        ArticleMarkdownFormat.INLINE_CODE -> 96.toChar().toString() to 96.toChar().toString()
        else -> null
    }
    if (inlineMarkers != null) {
        val (opening, closing) = inlineMarkers
        if (hasSelection) {
            val replacement = opening + selected + closing
            val updated = text.replaceRange(start, end, replacement)
            return ArticleMarkdownEdit(updated, start + opening.length, start + opening.length + selected.length)
        }
        val updated = text.substring(0, start) + opening + closing + text.substring(start)
        val cursor = start + opening.length
        return ArticleMarkdownEdit(updated, cursor, cursor)
    }

    if (format == ArticleMarkdownFormat.MATH) {
        val replacement = mathFence + "\n" + selected + "\n" + mathFence
        val updated = text.replaceRange(start, end, replacement)
        val cursor = start + mathFence.length + 1
        return ArticleMarkdownEdit(updated, cursor, if (hasSelection) cursor + selected.length else cursor)
    }

    if (format == ArticleMarkdownFormat.FOOTNOTE) {
        val body = if (hasSelection) selected else "id"
        val replacement = "[^" + body + "]"
        val updated = text.replaceRange(start, end, replacement)
        val cursor = start + 2
        return ArticleMarkdownEdit(updated, cursor, cursor + body.length)
    }

    if (format == ArticleMarkdownFormat.LINK || format == ArticleMarkdownFormat.IMAGE) {
        val label = if (hasSelection) selected else if (format == ArticleMarkdownFormat.LINK) "link text" else "alt text"
        val prefix = if (format == ArticleMarkdownFormat.IMAGE) "![" else "["
        val replacement = prefix + label + "](https://)"
        val updated = text.replaceRange(start, end, replacement)
        val cursor = start + prefix.length + label.length + 2 + 8
        return ArticleMarkdownEdit(updated, cursor, cursor)
    }

    if (format == ArticleMarkdownFormat.FENCED_CODE) {
        val fence = 96.toChar().toString().repeat(3)
        val replacement = fence + "\n" + selected + "\n" + fence
        val updated = text.replaceRange(start, end, replacement)
        val cursor = start + fence.length + 1
        return ArticleMarkdownEdit(updated, cursor, if (hasSelection) cursor + selected.length else cursor)
    }

    if (format == ArticleMarkdownFormat.TABLE) {
        val rows = if (hasSelection) selected.lines().filter { it.isNotBlank() } else emptyList()
        val table = buildString {
            append("| Header 1 | Header 2 |\n")
            append("| --- | --- |\n")
            if (rows.isEmpty()) append("| Cell 1 | Cell 2 |")
            else rows.forEach { row -> append("| ").append(row.replace("|", "\\|")).append(" |  |").append('\n') }
        }.trimEnd()
        val updated = text.replaceRange(start, end, table)
        val cursor = start + 2
        return ArticleMarkdownEdit(updated, cursor, cursor)
    }

    if (format == ArticleMarkdownFormat.DIVIDER) {
        val before = if (start > 0 && text[start - 1] != '\n') "\n" else ""
        val after = if (end < text.length && text[end] != '\n') "\n" else ""
        val replacement = before + "---" + after
        val updated = text.replaceRange(start, end, replacement)
        val cursor = start + replacement.length
        return ArticleMarkdownEdit(updated, cursor, cursor)
    }

    val marker = when (format) {
        ArticleMarkdownFormat.HEADING_1 -> "# "
        ArticleMarkdownFormat.HEADING_2 -> "## "
        ArticleMarkdownFormat.HEADING_3 -> "### "
        ArticleMarkdownFormat.HEADING_4 -> "#### "
        ArticleMarkdownFormat.HEADING_5 -> "##### "
        ArticleMarkdownFormat.HEADING_6 -> "###### "
        ArticleMarkdownFormat.QUOTE -> "> "
        ArticleMarkdownFormat.LIST -> "- "
        ArticleMarkdownFormat.ORDERED_LIST -> "1. "
        ArticleMarkdownFormat.TASK_LIST -> "- [ ] "
        else -> error("Unsupported Markdown format: " + format)
    }
    if (hasSelection) {
        val replacement = selected.split("\n").joinToString("\n") { line ->
            if (line.isEmpty()) marker.trimEnd() else marker + line
        }
        val updated = text.replaceRange(start, end, replacement)
        return ArticleMarkdownEdit(updated, start + marker.length, start + replacement.length)
    }
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val updated = text.substring(0, lineStart) + marker + text.substring(lineStart)
    val cursor = start + marker.length
    return ArticleMarkdownEdit(updated, cursor, cursor)
}

internal sealed interface ArticleMarkdownBlock {
    data class Heading(val level: Int, val text: String) : ArticleMarkdownBlock
    data class Paragraph(val text: String) : ArticleMarkdownBlock
    data class Quote(val text: String) : ArticleMarkdownBlock
    data class ListItem(val ordered: Boolean, val number: Int?, val text: String) : ArticleMarkdownBlock
    data class TaskItem(val checked: Boolean, val text: String) : ArticleMarkdownBlock
    data class Code(val text: String, val language: String? = null) : ArticleMarkdownBlock
    data class Image(val url: String, val alt: String?) : ArticleMarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ArticleMarkdownBlock
    data class NostrEmbed(val address: String) : ArticleMarkdownBlock
    data class Footnote(val id: String, val text: String) : ArticleMarkdownBlock
    data class Math(val text: String) : ArticleMarkdownBlock
    data object Divider : ArticleMarkdownBlock
}

private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")
private val orderedListRegex = Regex("""^(\d+)[.)]\s+(.+)$""")
private val unorderedListRegex = Regex("""^[-*+]\s+(.+)$""")
private val taskRegex = Regex("""^[-*+]\s+\[([ xX])]\s+(.+)$""")
private val imageRegex = Regex("""^!\[([^\]]*)]\((https?://\S+?)(?:\s+"[^"]*")?\)\s*$""")
private val nostrRegex = Regex("""^(?:nostr:)?(?:note1|nevent1|nprofile1|npub1|naddr1)[a-z0-9]+$""", RegexOption.IGNORE_CASE)
private val footnoteDefinitionRegex = Regex("""^\[\^([^\]]+)]\s*:\s*(.+)$""")
private val inlineRegex = Regex("""(\*\*.+?\*\*|~~.+?~~|\u0060[^\u0060]+\u0060|\[[^\]]+]\(https?://[^)]+\)|(?<!\*)\*[^*\n]+\*)""")
private val ruleRegex = Regex("""^(?:-{3,}|\*{3,}|_{3,})$""")
private val tableSeparatorRegex = Regex("""^:?-{3,}:?$""")
private val mathFence = "\$" + "\$"
private val bracketMathOpen = "\\" + "["
private val bracketMathClose = "\\" + "]"
private val singleLineMathRegex = Regex("""^(?:\$\$(.+)\$\$|\\\[(.+)\\\]|\\\((.+)\\\))$""")
private val codeFence = 96.toChar().toString().repeat(3)

internal data class InlineMathMatch(
    val formula: String,
    val endExclusive: Int
)

/** Finds inline TeX starting exactly at [startIndex]. Block delimiters ($) are excluded. */
internal fun findInlineMathAt(text: String, startIndex: Int): InlineMathMatch? {
    if (startIndex !in text.indices) return null

    if (text[startIndex] == '
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}
 &&
        text.getOrNull(startIndex + 1) != '
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}
 &&
        (startIndex == 0 || text[startIndex - 1] != '\\')
    ) {
        var closingIndex = text.indexOf('
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}
, startIndex + 1)
        while (closingIndex >= 0) {
            val isEscaped = closingIndex > 0 && text[closingIndex - 1] == '\\'
            val isBlockDelimiter = text.getOrNull(closingIndex + 1) == '
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}

            if (!isEscaped && !isBlockDelimiter) {
                val formula = text.substring(startIndex + 1, closingIndex)
                if (formula.isNotBlank()) {
                    return InlineMathMatch(formula, closingIndex + 1)
                }
            }
            closingIndex = text.indexOf('
private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}
, closingIndex + 1)
        }
    }

    if (text.startsWith("\\(", startIndex) &&
        (startIndex == 0 || text[startIndex - 1] != '\\')
    ) {
        val closingIndex = text.indexOf("\\)", startIndex + 2)
        if (closingIndex > startIndex + 2) {
            return InlineMathMatch(
                text.substring(startIndex + 2, closingIndex),
                closingIndex + 2
            )
        }
    }

    return null
}

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim().replace("\\|", "|") }

private fun isTableSeparator(line: String): Boolean {
    val cells = tableCells(line)
    return cells.size >= 2 && cells.all { tableSeparatorRegex.matches(it) }
}

internal fun parseArticleMarkdown(markdown: String): List<ArticleMarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<ArticleMarkdownBlock>()
    var index = 0

    fun isBlockStart(line: String): Boolean =
        headingRegex.matches(line) || line.startsWith(">") || line.startsWith(codeFence) ||
            line == mathFence || line == bracketMathOpen ||
            singleLineMathRegex.matches(line) ||
            orderedListRegex.matches(line) || taskRegex.matches(line) || unorderedListRegex.matches(line) ||
            ruleRegex.matches(line) || imageRegex.matches(line) || nostrRegex.matches(line) ||
            footnoteDefinitionRegex.matches(line) ||
            (line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()))

    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isEmpty() -> index++
            line.startsWith(codeFence) -> {
                val language = line.removePrefix(codeFence).trim().ifEmpty { null }
                index++
                val codeLines = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim().startsWith(codeFence)) {
                    codeLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Code(codeLines.joinToString("\n"), language))
            }
            singleLineMathRegex.matches(line) -> {
                val match = singleLineMathRegex.matchEntire(line)!!
                val formula = match.groupValues.drop(1).first { it.isNotEmpty() }
                blocks.add(ArticleMarkdownBlock.Math(formula))
                index++
            }
            line == mathFence || line == bracketMathOpen -> {
                val closingFence = if (line == mathFence) mathFence else bracketMathClose
                index++
                val mathLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim() != closingFence) {
                    mathLines.add(lines[index])
                    index++
                }
                if (index < lines.size) index++
                blocks.add(ArticleMarkdownBlock.Math(mathLines.joinToString("\n")))
            }
            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2]))
                index++
            }
            ruleRegex.matches(line) -> {
                blocks.add(ArticleMarkdownBlock.Divider)
                index++
            }
            imageRegex.matches(line) -> {
                val match = imageRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Image(match.groupValues[2], match.groupValues[1].ifEmpty { null }))
                index++
            }
            nostrRegex.matches(line) -> {
                val address = if (line.startsWith("nostr:", ignoreCase = true)) line else "nostr:" + line
                blocks.add(ArticleMarkdownBlock.NostrEmbed(address))
                index++
            }
            footnoteDefinitionRegex.matches(line) -> {
                val match = footnoteDefinitionRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.Footnote(match.groupValues[1], match.groupValues[2]))
                index++
            }
            line.contains("|") && index + 1 < lines.size && isTableSeparator(lines[index + 1].trim()) -> {
                val headers = tableCells(line)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && lines[index].trim().contains("|") && lines[index].isNotBlank()) {
                    rows.add(tableCells(lines[index].trim()))
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Table(headers, rows))
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines.add(lines[index].trim().removePrefix(">").trimStart())
                    index++
                }
                blocks.add(ArticleMarkdownBlock.Quote(quoteLines.joinToString(" ")))
            }
            taskRegex.matches(line) -> {
                val match = taskRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.TaskItem(match.groupValues[1].equals("x", true), match.groupValues[2]))
                index++
            }
            orderedListRegex.matches(line) -> {
                val match = orderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(true, match.groupValues[1].toIntOrNull(), match.groupValues[2]))
                index++
            }
            unorderedListRegex.matches(line) -> {
                val match = unorderedListRegex.matchEntire(line)!!
                blocks.add(ArticleMarkdownBlock.ListItem(false, null, match.groupValues[1]))
                index++
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (index < lines.size) {
                    val candidate = lines[index].trim()
                    if (candidate.isEmpty() || isBlockStart(candidate)) break
                    paragraph.add(candidate)
                    index++
                }
                if (paragraph.isNotEmpty()) blocks.add(ArticleMarkdownBlock.Paragraph(paragraph.joinToString(" ")))
                else index++
            }
        }
    }
    return blocks
}

@Composable
internal fun buildArticleInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        inlineRegex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") ->
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(token.substring(2, token.length - 2)) }
                token.startsWith("~~") && token.endsWith("~~") ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(token.substring(2, token.length - 2)) }
                token.startsWith(96.toChar()) && token.endsWith(96.toChar()) ->
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(token.substring(1, token.length - 1)) }
                token.startsWith("[") && token.contains("](") -> {
                    val close = token.indexOf("](")
                    val label = token.substring(1, close)
                    val url = token.substring(close + 2).removeSuffix(")")
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                    }
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
internal fun ArticleMarkdownListItem(block: ArticleMarkdownBlock.ListItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = if (block.ordered) (block.number ?: 1).toString() + "." else "•",
            modifier = Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTaskItem(block: ArticleMarkdownBlock.TaskItem) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(if (block.checked) "☑" else "☐", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.primary)
        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun ArticleMarkdownTable(block: ArticleMarkdownBlock.Table) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            block.headers.forEach { cell ->
                Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { cell ->
                    Text(buildArticleInlineMarkdown(cell), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun ArticleMarkdownPreview(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parseArticleMarkdown(markdown).forEach { block ->
            when (block) {
                is ArticleMarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(buildArticleInlineMarkdown(block.text), style = style, color = MaterialTheme.colorScheme.onSurface)
                }
                is ArticleMarkdownBlock.Paragraph ->
                    Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                is ArticleMarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        Box(modifier = Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(buildArticleInlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ArticleMarkdownBlock.ListItem -> ArticleMarkdownListItem(block)
                is ArticleMarkdownBlock.TaskItem -> ArticleMarkdownTaskItem(block)
                is ArticleMarkdownBlock.Table -> ArticleMarkdownTable(block)
                is ArticleMarkdownBlock.Image -> AsyncImage(model = block.url, contentDescription = block.alt, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                is ArticleMarkdownBlock.NostrEmbed -> {
                    val link = "https://njump.me/" + block.address.removePrefix("nostr:")
                    Text(buildAnnotatedString { withLink(LinkAnnotation.Url(link)) { append(block.address) } }, color = MaterialTheme.colorScheme.primary)
                }
                is ArticleMarkdownBlock.Footnote -> Text("[" + block.id + "] " + block.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ArticleMarkdownBlock.Math -> ArticleMathFormula(block.text)
                is ArticleMarkdownBlock.Code -> Text(block.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownBlock.Divider -> Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}


@Composable
private fun ArticleMathFormula(formula: String) {
    val formulaColor = MaterialTheme.colorScheme.onSurfaceVariant
    var renderError by remember(formula) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (renderError) {
            Text(
                text = formula,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = formulaColor
            )
        } else {
            AndroidView(
                factory = { context ->
                    RaTeXView(context).apply {
                        fontSize = 20f
                        displayMode = true
                        color = formulaColor.toArgb()
                        onError = { renderError = true }
                        latex = formula
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(12.dp),
                update = { view ->
                    view.fontSize = 20f
                    view.displayMode = true
                    view.color = formulaColor.toArgb()
                    view.onError = { renderError = true }
                    view.latex = formula
                }
            )
        }
    }
}
