package com.darkwisp.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MathContentParserTest {
    @Test
    fun recognizesAllThreeDisplayFormulasFromTheReportedExample() {
        val formulas = listOf(
            """c = \pm\sqrt{a^2 + b^2}""",
            """P \impliedby Q""",
            """s = ut + \frac{1}{2}at^{2}"""
        )
        val content = formulas.mapIndexed { index, formula ->
            "Example " + (index + 1) + "\n\n$$" + formula + "$$"
        }.joinToString("\n\n")

        assertEquals(
            listOf(
                MathContentToken.Text("Example 1\n\n"),
                MathContentToken.DisplayMath(formulas[0]),
                MathContentToken.Text("\n\nExample 2\n\n"),
                MathContentToken.DisplayMath(formulas[1]),
                MathContentToken.Text("\n\nExample 3\n\n"),
                MathContentToken.DisplayMath(formulas[2])
            ),
            splitMathContent(content)
        )
    }

    @Test
    fun recognizesInlineDollarAndBracketFormulas() {
        val content = "before \$x^2\$ middle \\(\\frac{a}{b}\\) after"
        assertEquals(
            listOf(
                MathContentToken.Text("before "),
                MathContentToken.InlineMath("x^2"),
                MathContentToken.Text(" middle "),
                MathContentToken.InlineMath("\\frac{a}{b}"),
                MathContentToken.Text(" after")
            ),
            splitMathContent(content)
        )
    }

    @Test
    fun recognizesBracketDelimitedDisplayMathAcrossLines() {
        assertEquals(
            listOf(
                MathContentToken.Text("before\n"),
                MathContentToken.DisplayMath("\\frac{1}{2}"),
                MathContentToken.Text("\nafter")
            ),
            splitMathContent("before\n\\[\n\\frac{1}{2}\n\\]\nafter")
        )
    }

    @Test
    fun escapedCurrencyAndUnmatchedDelimitersRemainText() {
        val content = "price \\$5, and \$10 for the second item; unfinished \$"
        assertEquals(listOf(MathContentToken.Text(content)), splitMathContent(content))
    }

    @Test
    fun displayDelimiterIsNeverMisreadAsInlineDollarMath() {
        assertEquals(
            listOf(MathContentToken.DisplayMath("x^2")),
            splitMathContent("\$\$x^2\$\$")
        )
    }
}
