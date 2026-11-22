package com.darkwisp.app.ui.component

/**
 * A tokenized piece of post text. Display formulas are separate blocks;
 * inline formulas remain in the surrounding text run.
 */
internal sealed interface MathContentToken {
    data class Text(val value: String) : MathContentToken
    data class InlineMath(val formula: String) : MathContentToken
    data class DisplayMath(val formula: String) : MathContentToken
}

private data class MathDelimiter(
    val opening: String,
    val closing: String,
    val display: Boolean,
    val allowNewlines: Boolean
)

private fun isMathDelimiterEscaped(text: String, index: Int): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        slashes++
        cursor--
    }
    return slashes % 2 == 1
}

private fun findMathClosing(text: String, delimiter: String, start: Int): Int {
    var candidate = text.indexOf(delimiter, start)
    while (candidate >= 0) {
        if (!isMathDelimiterEscaped(text, candidate)) return candidate
        candidate = text.indexOf(delimiter, candidate + delimiter.length)
    }
    return -1
}

/**
 * Recognizes LaTeX delimiters in ordinary Nostr post text:
 * $$...$$ and \[...\] are display formulas;
 * $...$ and \(...\) are inline formulas.
 *
 * Double-dollar delimiters are checked before single-dollar delimiters so a
 * display formula can never be accidentally consumed as an inline fragment.
 * Unmatched and escaped delimiters are returned untouched as text.
 */
internal fun splitMathContent(text: String): List<MathContentToken> {
    if (text.isEmpty()) return listOf(MathContentToken.Text(text))

    val result = mutableListOf<MathContentToken>()
    var cursor = 0
    var textStart = 0

    fun emitText(endExclusive: Int) {
        if (endExclusive > textStart) {
            result.add(MathContentToken.Text(text.substring(textStart, endExclusive)))
        }
    }

    while (cursor < text.length) {
        if (isMathDelimiterEscaped(text, cursor)) {
            cursor++
            continue
        }

        val delimiter = when {
            text.startsWith("$$", cursor) ->
                MathDelimiter("$$", "$$", display = true, allowNewlines = true)
            text.startsWith("\\[", cursor) ->
                MathDelimiter("\\[", "\\]", display = true, allowNewlines = true)
            text.startsWith("\\(", cursor) ->
                MathDelimiter("\\(", "\\)", display = false, allowNewlines = false)
            text[cursor] == '$' &&
                (cursor + 1 >= text.length || (!text[cursor + 1].isWhitespace() && !text[cursor + 1].isDigit())) ->
                MathDelimiter("$", "$", display = false, allowNewlines = false)
            else -> null
        }

        if (delimiter == null) {
            cursor++
            continue
        }

        val contentStart = cursor + delimiter.opening.length
        var closingAt = findMathClosing(text, delimiter.closing, contentStart)
        if (delimiter.opening == "$") {
            // Do not consume one half of a display-math closing delimiter.
            while (closingAt >= 0 &&
                (text.startsWith("$$", closingAt) || (closingAt > 0 && text[closingAt - 1] == '$'))
            ) {
                closingAt = findMathClosing(text, delimiter.closing, closingAt + 1)
            }
        }
        if (closingAt < 0) {
            cursor += delimiter.opening.length
            continue
        }

        val rawFormula = text.substring(contentStart, closingAt)
        if ((!delimiter.allowNewlines && rawFormula.contains('\n')) || rawFormula.isBlank()) {
            cursor += delimiter.opening.length
            continue
        }

        emitText(cursor)
        val formula = rawFormula.trim()
        result.add(
            if (delimiter.display) MathContentToken.DisplayMath(formula)
            else MathContentToken.InlineMath(formula)
        )
        cursor = closingAt + delimiter.closing.length
        textStart = cursor
    }

    emitText(text.length)
    return if (result.isEmpty()) listOf(MathContentToken.Text(text)) else result
}
