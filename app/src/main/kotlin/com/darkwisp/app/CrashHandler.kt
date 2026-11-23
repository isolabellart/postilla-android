package com.darkwisp.app

import android.content.Context
import android.os.Build
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Nip17
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.KeyRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    // Decoded from the Postilla account owner's npub; crash reports are sent back to this account.
    const val CRASH_REPORT_RECIPIENT_PUBKEY = "f4db5270bd991b17bea1e6d035f45dee392919c29474bbac10342d223c74e0d0"
    private const val CRASH_LOG_FILE = "crash_log.txt"

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(context, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val version = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pInfo.versionName} (${pInfo.longVersionCode})"
            } catch (_: Exception) {
                "unknown"
            }

            val log = buildString {
                appendLine("Wisp Crash Report")
                appendLine("=================")
                appendLine("Time: $timestamp")
                appendLine("Version: $version")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.SDK_INT}")
                appendLine()
                appendLine("Stack trace:")
                appendLine(throwable.stackTraceToString())
            }

            File(context.filesDir, CRASH_LOG_FILE).writeText(log)
        } catch (_: Exception) {
            // Best-effort — don't crash the crash handler
        }
    }

    fun hasCrashLog(context: Context): Boolean =
        File(context.filesDir, CRASH_LOG_FILE).exists()

    fun getCrashLog(context: Context): String =
        File(context.filesDir, CRASH_LOG_FILE).readText()

    fun clearCrashLog(context: Context) {
        File(context.filesDir, CRASH_LOG_FILE).delete()
    }

    private val CRASH_REPORT_RELAYS = listOf(
        "wss://relay.0xchat.com",
        "wss://relay.utxo.one/chat",
        "wss://relay.damus.io"
    )

    suspend fun sendCrashDm(keyRepo: KeyRepository, relayPool: RelayPool, crashLog: String) {
        val keypair = keyRepo.getKeypair() ?: return

        val recipientPubkey = CRASH_REPORT_RECIPIENT_PUBKEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val wrap = Nip17.createGiftWrap(
            senderPrivkey = keypair.privkey,
            senderPubkey = keypair.pubkey,
            recipientPubkey = recipientPubkey,
            message = crashLog
        )
        val msg = ClientMessage.event(wrap)

        for (url in CRASH_REPORT_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, msg)
        }
    }
}
