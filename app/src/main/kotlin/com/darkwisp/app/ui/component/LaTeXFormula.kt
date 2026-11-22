package com.darkwisp.app.ui.component

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import io.ratex.RaTeXSpan

@Composable
internal fun LaTeXFormula(
    formula: String,
    displayMode: Boolean,
    fontSize: TextUnit,
    formulaColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val fontSizeDp = with(LocalDensity.current) { fontSize.toDp().value }
    val colorInt = formulaColor.toArgb()
    var renderedText by remember(formula, displayMode, fontSizeDp, colorInt) {
        mutableStateOf<SpannableStringBuilder?>(null)
    }
    var renderFailed by remember(formula, displayMode, fontSizeDp, colorInt) {
        mutableStateOf(false)
    }
    LaunchedEffect(formula, displayMode, fontSizeDp, colorInt) {
        renderedText = null
        renderFailed = false
        try {
            val span = RaTeXSpan.create(
                context = context, latex = formula, fontSize = fontSizeDp.coerceAtLeast(12f),
                color = colorInt, displayMode = displayMode
            )
            renderedText = SpannableStringBuilder("\u200B").apply {
                setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (error: Throwable) {
            renderFailed = true
            Log.e("PostillaLaTeX", "Unable to render formula: $formula", error)
        }
    }
    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                includeFontPadding = false
                gravity = if (displayMode) Gravity.CENTER else Gravity.CENTER_VERTICAL
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value.coerceAtLeast(12f))
                setTextColor(colorInt)
                contentDescription = formula
            }
        },
        modifier = modifier,
        update = { textView ->
            textView.includeFontPadding = false
            textView.gravity = if (displayMode) Gravity.CENTER else Gravity.CENTER_VERTICAL
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value.coerceAtLeast(12f))
            textView.setTextColor(colorInt)
            textView.typeface = if (renderFailed) Typeface.MONOSPACE else Typeface.DEFAULT
            textView.contentDescription = formula
            textView.text = renderedText ?: formula
        }
    )
}
