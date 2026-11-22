package com.darkwisp.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.darkwisp.app.repo.TranslationRepository
import com.darkwisp.app.repo.TranslationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Android text-selection action. It deliberately uses the configured DeepL account,
 * independently of the default in-app translation engine.
 */
class TextTranslationActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (selectedText.isBlank()) {
            finish()
            return
        }

        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val requestId = "selection-${UUID.randomUUID()}"
        val repository = TranslationRepository(applicationContext)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.text_translation_title)
            .setMessage(R.string.text_translation_preparing)
            .setNegativeButton(R.string.btn_close) { _, _ -> finish() }
            .setPositiveButton(R.string.btn_copy, null)

        if (!readOnly) {
            builder.setNeutralButton(R.string.text_translation_replace, null)
        }

        val dialog = builder.create()
        dialog.setOnCancelListener { finish() }
        dialog.show()

        val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val replaceButton = if (readOnly) null else dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        copyButton.isEnabled = false
        replaceButton?.isEnabled = false

        activityScope.launch {
            repository.stateFor(requestId).collect { state ->
                if (isFinishing || isDestroyed) return@collect

                val message = when (state.status) {
                    TranslationStatus.DONE -> buildString {
                        append(getString(R.string.text_translation_original))
                        append(":\\n")
                        append(selectedText)
                        append("\\n\\n")
                        append(getString(R.string.text_translation_result))
                        append(":\\n")
                        append(state.translatedText)
                    }
                    TranslationStatus.ERROR -> buildString {
                        append(getString(R.string.text_translation_original))
                        append(":\\n")
                        append(selectedText)
                        append("\\n\\n")
                        append(state.errorMessage.ifBlank { getString(R.string.text_translation_failed) })
                    }
                    TranslationStatus.SAME_LANGUAGE -> getString(R.string.text_translation_same_language)
                    TranslationStatus.IDLE,
                    TranslationStatus.IDENTIFYING_LANGUAGE,
                    TranslationStatus.DOWNLOADING_MODEL,
                    TranslationStatus.TRANSLATING -> getString(R.string.text_translation_loading)
                }

                dialog.setMessage(message)
                val completed = state.status == TranslationStatus.DONE ||
                    state.status == TranslationStatus.SAME_LANGUAGE
                val resultText = state.translatedText.ifBlank {
                    if (state.status == TranslationStatus.SAME_LANGUAGE) selectedText else ""
                }
                copyButton.isEnabled = completed
                replaceButton?.isEnabled = completed

                if (completed) {
                    copyButton.setOnClickListener {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText(getString(R.string.text_translation_title), resultText)
                        )
                        Toast.makeText(this@TextTranslationActivity, R.string.text_translation_copied, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    replaceButton?.setOnClickListener {
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, resultText)
                        )
                        finish()
                    }
                }
            }
        }

        repository.translateWithDeepL(requestId, selectedText)
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}
