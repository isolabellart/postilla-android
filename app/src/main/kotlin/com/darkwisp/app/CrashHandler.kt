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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        "wss://auth1.nostr.com",
        "wss://nos.lol"
    )

    /**
     * Sends one NIP-17 gift wrap to the configured report relays.
     * Success is returned only after at least one relay answers with an accepted NIP-20 OK.
     * A queued WebSocket write is not treated as successful delivery.
     */
    suspend fun sendCrashDm(keyRepo: KeyRepository, relayPool: RelayPool, crashLog: String): Boolean {
        return try {
            val keypair = keyRepo.getKeypair() ?: return false
            val recipientPubkey = CRASH_REPORT_RECIPIENT_PUBKEY
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
            if (recipientPubkey.size != 32) return false

            val wrap = Nip17.createGiftWrap(
                senderPrivkey = keypair.privkey,
                senderPubkey = keypair.pubkey,
                recipientPubkey = recipientPubkey,
                message = crashLog
            )
            val message = ClientMessage.event(wrap)

            // Start listening before opening the report relays. If a relay requires
            // NIP-42, RelayPool will ask the user for approval; once AUTH is sent,
            // retry the same Gift Wrap so it can receive a real accepted NIP-20 OK.
            val authRetryJob = CoroutineScope(Dispatchers.IO).launch(
                start = CoroutineStart.UNDISPATCHED
            ) {
                relayPool.authCompleted.collect { authenticatedUrl ->
                    if (authenticatedUrl in CRASH_REPORT_RELAYS) {
                        relayPool.sendToRelayOrEphemeral(
                            url = authenticatedUrl,
                            message = message,
                            write = true,
                            autoReconnect = true
                        )
                    }
                }
            }

            try {
                val outcome = relayPool.publishWithConfirmation(
                    eventId = wrap.id,
                    timeoutMs = 15_000L
                ) {
                    CRASH_REPORT_RELAYS.count { url ->
                        relayPool.markDmDeliveryTarget(url)
                        relayPool.sendToRelayOrEphemeral(
                            url = url,
                            message = message,
                            write = true,
                            autoReconnect = true
                        )
                    }
                }
                outcome.confirmed
            } finally {
                authRetryJob.cancel()
            }
        } catch (_: Exception) {
            false
        }
    }
}
