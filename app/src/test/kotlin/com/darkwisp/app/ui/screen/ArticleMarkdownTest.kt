package com.darkwisp.app.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleMarkdownTest {
    @Test
    fun boldWrapsOnlySelectedTextAndPreservesSurroundingText() {
        val result = formatArticleMarkdownSelection("before word after", 7, 11, ArticleMarkdownFormat.BOLD)
        assertEquals("before **word** after", result.text)
        assertEquals(9, result.selectionStart)
        assertEquals(13, result.selectionEnd)
    }

    @Test
    fun italicWithoutSelectionLeavesCursorBetweenMarkers() {
        val result = formatArticleMarkdownSelection("hello world", 5, 5, ArticleMarkdownFormat.ITALIC)
        assertEquals("hello** world", result.text)
        assertEquals(6, result.selectionStart)
        assertEquals(6, result.selectionEnd)
    }

    @Test
    fun headingPrefixesSelectedText() {
        val result = formatArticleMarkdownSelection("A title", 0, 7, ArticleMarkdownFormat.HEADING_2)
        assertEquals("## A title", result.text)
        assertEquals(3, result.selectionStart)
        assertEquals(10, result.selectionEnd)
    }

    @Test
    fun quoteAndListPrefixEverySelectedLine() {
        val quote = formatArticleMarkdownSelection("first\nsecond", 0, 12, ArticleMarkdownFormat.QUOTE)
        val list = formatArticleMarkdownSelection("first\nsecond", 0, 12, ArticleMarkdownFormat.LIST)
        assertEquals("> first\n> second", quote.text)
        assertEquals("- first\n- second", list.text)
    }

    @Test
    fun previewParserRecognizesHeadingParagraphQuoteAndLists() {
        val blocks = parseArticleMarkdown("## A heading\n\nA **bold** and *italic* paragraph.\n\n> A quotation\n- first item\n2. second item")
        assertEquals(
            listOf(
                ArticleMarkdownBlock.Heading(2, "A heading"),
                ArticleMarkdownBlock.Paragraph("A **bold** and *italic* paragraph."),
                ArticleMarkdownBlock.Quote("A quotation"),
                ArticleMarkdownBlock.ListItem(false, null, "first item"),
                ArticleMarkdownBlock.ListItem(true, 2, "second item")
            ),
            blocks
        )
    }
    @Test
    fun strikeCodeAndHeadingSixFormattingAreAvailable() {
        val strike = formatArticleMarkdownSelection("before word after", 7, 11, ArticleMarkdownFormat.STRIKE)
        val code = formatArticleMarkdownSelection("snippet", 0, 7, ArticleMarkdownFormat.INLINE_CODE)
        val heading = formatArticleMarkdownSelection("Heading", 0, 7, ArticleMarkdownFormat.HEADING_6)
        assertEquals("before ~~word~~ after", strike.text)
        assertEquals(96.toChar() + "snippet" + 96.toChar(), code.text)
        assertEquals("###### Heading", heading.text)
    }

    @Test
    fun previewParserPreservesAllSixHeadingLevels() {
        val titles = listOf("One", "Two", "Three", "Four", "Five", "Six")
        val markdown = titles.mapIndexed { index, title -> "#".repeat(index + 1) + " " + title }.joinToString("\n")
        val expected = titles.mapIndexed { index, title -> ArticleMarkdownBlock.Heading(index + 1, title) }
        assertEquals(expected, parseArticleMarkdown(markdown))
    }

    @Test
    fun tableAndTaskListAreParsedForPreview() {
        val blocks = parseArticleMarkdown("| Name | Value |\n| --- | --- |\n| A | B |\n- [x] done\n- [ ] next")
        assertEquals(3, blocks.size)
        assertEquals(ArticleMarkdownBlock.Table(listOf("Name", "Value"), listOf(listOf("A", "B"))), blocks[0])
        assertEquals(ArticleMarkdownBlock.TaskItem(true, "done"), blocks[1])
        assertEquals(ArticleMarkdownBlock.TaskItem(false, "next"), blocks[2])
    }

    @Test
    fun markdownLinkAndFootnoteBlocksKeepTheirContent() {
        val blocks = parseArticleMarkdown("[Postilla](https://example.com)\n\n[^note]: Extra context")
        assertEquals(ArticleMarkdownBlock.Paragraph("[Postilla](https://example.com)"), blocks[0])
        assertEquals(ArticleMarkdownBlock.Footnote("note", "Extra context"), blocks[1])
    }
    @Test
    fun bracketDelimitedDisplayMathIsParsedAsFormula() {
        val open = "\\" + "["
        val close = "\\" + "]"
        assertEquals(
            listOf(
                ArticleMarkdownBlock.Paragraph("Lead-in text"),
                ArticleMarkdownBlock.Math("\\frac{a}{b}")
            ),
            parseArticleMarkdown("Lead-in text\n" + open + "\n\\frac{a}{b}\n" + close)
        )
    }

    @Test
    fun singleLineDisplayMathIsParsedAsFormula() {
        val delimiter = 36.toChar().toString().repeat(2)
        assertEquals(
            listOf(ArticleMarkdownBlock.Math("E=mc^2")),
            parseArticleMarkdown(delimiter + "E=mc^2" + delimiter)
        )
    }

    @Test
    fun mathBlockFormattingInsertsDollarFencedFormulaAndParsesIt() {
        val delimiter = 36.toChar().toString().repeat(2)
        val edit = formatArticleMarkdownSelection("before after", 7, 7, ArticleMarkdownFormat.MATH)
        assertEquals("before " + delimiter + "\n\n" + delimiter + "after", edit.text)
        assertEquals(10, edit.selectionStart)
        assertEquals(
            listOf(ArticleMarkdownBlock.Math("x^2")),
            parseArticleMarkdown(delimiter + "\nx^2\n" + delimiter)
        )
    }
}
