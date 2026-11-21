package com.darkwisp.app.repo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.darkwisp.app.BuildConfig
import com.darkwisp.app.relay.HttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

data class PostillaUpdateOffer(
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val previewOnly: Boolean = false
)

object PostillaUpdateManager {
    private const val TAG = "PostillaUpdater"
    private const val PREFS = "postilla_in_app_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_OFFER = "available_offer"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    private const val LATER_INTERVAL_MS = 3L * 24L * 60L * 60L * 1000L
    private const val STABLE_PACKAGE = "com.isolabellart.darkwispenhanced"
    private const val EXPECTED_CERT_SHA256 = "86868a3659729a6531f3e8a31d5dcfe04405e2a86866f3e0eb0abb6965cdb841"
    private const val RELEASES_API = "https://api.github.com/repos/isolabellart/postilla-android/releases/latest"
    private const val RELEASE_APK_PREFIX = "https://github.com/isolabellart/postilla-android/releases/download/"

    private val _availableUpdate = MutableStateFlow<PostillaUpdateOffer?>(null)
    val availableUpdate: StateFlow<PostillaUpdateOffer?> = _availableUpdate

    fun refreshVisibleUpdate(context: Context) {
        _availableUpdate.value = visibleOffer(context.applicationContext)
    }

    fun remindLater(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + LATER_INTERVAL_MS).apply()
        _availableUpdate.value = null
    }

    suspend fun checkIfDue(context: Context, forceCheck: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Preview builds show a local test offer, never a production APK for the
        // separate .staging application ID.
        if (BuildConfig.BUILD_TYPE == "staging") {
            val saved = readOffer(prefs)
            if (saved == null || !saved.previewOnly || saved.versionName != "1.0.48") {
                writeOffer(prefs, PostillaUpdateOffer("1.0.48", "", "", previewOnly = true))
            }
            publishVisibleOffer(appContext)
            return@withContext true
        }

        if (BuildConfig.APPLICATION_ID != STABLE_PACKAGE || BuildConfig.BUILD_TYPE != "release") {
            _availableUpdate.value = null
            return@withContext true
        }

        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!forceCheck && now - lastCheck < CHECK_INTERVAL_MS) {
            publishVisibleOffer(appContext)
            return@withContext true
        }

        var checkSucceeded = false
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Postilla-Android-InAppUpdater")
                .build()

            HttpClientFactory.getGeneralClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub returned HTTP " + response.code)
                val body = response.body?.string() ?: throw IOException("Empty GitHub release response")
                val release = JSONObject(body)
                if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) {
                    clearOffer(prefs)
                    return@use
                }

                val versionName = release.optString("tag_name").removePrefix("v")
                if (!isNewerVersion(versionName, BuildConfig.VERSION_NAME)) {
                    clearOffer(prefs)
                    return@use
                }

                val assets = release.optJSONArray("assets") ?: throw IOException("Release has no assets")
                var apkUrl: String? = null
                var digest: String? = null
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val assetName = asset.optString("name")
                    val label = asset.optString("label")
                    if (assetName == "app-release.apk" || label.endsWith(".apk", ignoreCase = true)) {
                        val candidateUrl = asset.optString("browser_download_url")
                        val candidateDigest = asset.optString("digest").removePrefix("sha256:")
                        if (candidateUrl.startsWith(RELEASE_APK_PREFIX) &&
                            candidateDigest.matches(Regex("^[0-9a-fA-F]{64}$"))
                        ) {
                            apkUrl = candidateUrl
                            digest = candidateDigest
                            break
                        }
                    }
                }

                if (apkUrl.isNullOrBlank() || digest.isNullOrBlank()) {
                    throw IOException("The release APK or its SHA-256 digest is missing")
                }

                val previousVersion = readOffer(prefs)?.versionName
                if (previousVersion != null && previousVersion != versionName) {
                    prefs.edit().putLong(KEY_SNOOZE_UNTIL, 0L).apply()
                }
                writeOffer(prefs, PostillaUpdateOffer(versionName, apkUrl, digest.lowercase()))
            }
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            checkSucceeded = true
        } catch (error: Exception) {
            // Retry within an hour after transient network failures.
            prefs.edit().putLong(KEY_LAST_CHECK, now - CHECK_INTERVAL_MS + 60L * 60L * 1000L).apply()
            Log.w(TAG, "Release check failed: " + error.message)
        }

        publishVisibleOffer(appContext)
        checkSucceeded
    }

    suspend fun downloadAndLaunchInstaller(activity: Activity, offer: PostillaUpdateOffer) {
        require(!offer.previewOnly) { "Updates cannot be installed from a Preview build." }
        val uri = withContext(Dispatchers.IO) {
            downloadAndVerify(activity.applicationContext, offer)
        }
        withContext(Dispatchers.Main) {
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(installIntent)
        }
    }

    private fun downloadAndVerify(context: Context, offer: PostillaUpdateOffer): Uri {
        if (!offer.apkUrl.startsWith(RELEASE_APK_PREFIX)) {
            throw SecurityException("The APK URL is not an official Postilla GitHub release asset.")
        }
        if (!offer.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            throw SecurityException("The release does not provide a valid SHA-256 digest.")
        }

        val request = Request.Builder()
            .url(offer.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Postilla-Android-InAppUpdater")
            .build()

        val updateDirectory = File(context.filesDir, "updates")
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw IOException("Could not create the update directory.")
        }
        val apkFile = File(updateDirectory, "postilla-update.apk")
        val temporaryFile = File(updateDirectory, "postilla-update.apk.part")

        try {
            HttpClientFactory.getDownloadClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("APK download failed (HTTP " + response.code + ").")
                val responseBody = response.body ?: throw IOException("The APK download was empty.")
                val contentLength = responseBody.contentLength()
                if (contentLength == 0L || contentLength > 200L * 1024L * 1024L) {
                    throw IOException("The APK size is invalid.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                responseBody.byteStream().use { input ->
                    FileOutputStream(temporaryFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualDigest.equals(offer.sha256, ignoreCase = true)) {
                    throw SecurityException("The APK SHA-256 digest does not match the GitHub release.")
                }
            }

            if (apkFile.exists() && !apkFile.delete()) throw IOException("Could not replace a previous update download.")
            if (!temporaryFile.renameTo(apkFile)) throw IOException("Could not finalize the downloaded APK.")

            val archiveInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            ) ?: throw SecurityException("The downloaded file is not a valid Android APK.")

            if (archiveInfo.packageName != STABLE_PACKAGE) {
                throw SecurityException("The APK belongs to a different Android application.")
            }
            if (archiveInfo.versionName != offer.versionName) {
                throw SecurityException("The APK version does not match the announced release.")
            }

            val currentInfo = getInstalledPackageInfo(context.packageManager, context.packageName)
            val currentVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                currentInfo.versionCode.toLong()
            }
            val newVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.versionCode.toLong()
            }
            if (newVersionCode <= currentVersionCode) {
                throw SecurityException("The downloaded APK is not newer than the installed version.")
            }

            val installedCerts = packageSignatures(currentInfo).map(::certificateSha256)
            val apkCerts = packageSignatures(archiveInfo).map(::certificateSha256)
            if (EXPECTED_CERT_SHA256 !in installedCerts || EXPECTED_CERT_SHA256 !in apkCerts) {
                throw SecurityException("The APK signing certificate does not match the permanent Postilla certificate.")
            }

            return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apkFile)
        } catch (error: Exception) {
            temporaryFile.delete()
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageSignatures(packageInfo: PackageInfo): List<android.content.pm.Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            packageInfo.signatures?.toList().orEmpty()
        }
    }

    private fun certificateSha256(signature: android.content.pm.Signature): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun publishVisibleOffer(context: Context) {
        _availableUpdate.value = visibleOffer(context)
    }

    private fun visibleOffer(context: Context): PostillaUpdateOffer? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong(KEY_SNOOZE_UNTIL, 0L)) return null
        val offer = readOffer(prefs) ?: return null
        if (!offer.previewOnly && !isNewerVersion(offer.versionName, BuildConfig.VERSION_NAME)) {
            clearOffer(prefs)
            return null
        }
        return offer
    }

    private fun readOffer(prefs: android.content.SharedPreferences): PostillaUpdateOffer? {
        val raw = prefs.getString(KEY_OFFER, null) ?: return null
        return try {
            val json = JSONObject(raw)
            PostillaUpdateOffer(
                versionName = json.getString("version"),
                apkUrl = json.optString("url"),
                sha256 = json.optString("sha256"),
                previewOnly = json.optBoolean("preview", false)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeOffer(prefs: android.content.SharedPreferences, offer: PostillaUpdateOffer) {
        val json = JSONObject()
            .put("version", offer.versionName)
            .put("url", offer.apkUrl)
            .put("sha256", offer.sha256)
            .put("preview", offer.previewOnly)
        prefs.edit().putString(KEY_OFFER, json.toString()).apply()
    }

    private fun clearOffer(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(KEY_OFFER).putLong(KEY_SNOOZE_UNTIL, 0L).apply()
    }
}

internal fun isNewerVersion(candidate: String, installed: String): Boolean {
    fun parts(value: String): List<Int>? {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
    val candidateParts = parts(candidate) ?: return false
    val installedParts = parts(installed) ?: return false
    for (index in 0..2) {
        if (candidateParts[index] != installedParts[index]) {
            return candidateParts[index] > installedParts[index]
        }
    }
    return false
}
