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
}
