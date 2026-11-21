package com.darkwisp.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.darkwisp.app.repo.InterfacePreferences
import com.darkwisp.app.repo.PostillaUpdateManager
import com.darkwisp.app.repo.LocaleRepository
import com.darkwisp.app.ui.component.LocalMediaSettings
import com.darkwisp.app.ui.component.MediaSettings
import com.darkwisp.app.ui.component.PipController
import com.darkwisp.app.ui.component.PostillaUpdateBanner
import com.darkwisp.app.ui.theme.PostillaTheme
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    var deepLinkUri = mutableStateOf<String?>(null)
        private set

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "system") ?: "system"
        super.attachBaseContext(LocaleRepository.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        com.darkwisp.app.ui.component.NsecPasteGuard.setActivity(this)
        enableEdgeToEdge()
        deepLinkUri.value = intent?.data?.toString()
        setContent {
            val updateOffer by PostillaUpdateManager.availableUpdate.collectAsState()
            var updateDownloading by remember { mutableStateOf(false) }
            val updateScope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                PostillaUpdateManager.refreshVisibleUpdate(this@MainActivity)
                while (true) {
                    PostillaUpdateManager.checkIfDue(this@MainActivity)
                    delay(60L * 60L * 1000L)
                }
            }

            val prefs = remember { getSharedPreferences("wisp_settings", Context.MODE_PRIVATE) }
            val interfacePrefs = remember { InterfacePreferences(this@MainActivity) }
            var isDarkTheme by rememberSaveable { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
            var isLargeText by remember { mutableStateOf(interfacePrefs.isLargeText()) }
            var mediaSettings by remember {
                mutableStateOf(MediaSettings(
                    autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                    videoAutoPlay = interfacePrefs.isVideoAutoPlay(),
                    mediaLayoutStyle = interfacePrefs.getMediaLayoutStyle()
                ))
            }

            LaunchedEffect(isDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    }
                )
            }

            val pipActive by PipController.pipState.collectAsState()
            val effectiveMediaSettings = if (pipActive != null) {
                mediaSettings.copy(videoAutoPlay = false)
            } else {
                mediaSettings
            }

            PostillaTheme(isDarkTheme = isDarkTheme, isLargeText = isLargeText) {
                CompositionLocalProvider(LocalMediaSettings provides effectiveMediaSettings) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        updateOffer?.let { offer ->
                            PostillaUpdateBanner(
                                versionName = offer.versionName,
                                isDownloading = updateDownloading,
                                onLater = { PostillaUpdateManager.remindLater(this@MainActivity) },
                                onUpdateNow = {
                                    if (offer.previewOnly) {
                                        Toast.makeText(this@MainActivity, R.string.postilla_update_preview_only, Toast.LENGTH_LONG).show()
                                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                        !packageManager.canRequestPackageInstalls()
                                    ) {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                Uri.parse("package:$packageName")
                                            )
                                        )
                                        Toast.makeText(this@MainActivity, R.string.postilla_update_allow_install, Toast.LENGTH_LONG).show()
                                    } else if (!updateDownloading) {
                                        updateScope.launch {
                                            updateDownloading = true
                                            try {
                                                PostillaUpdateManager.downloadAndLaunchInstaller(this@MainActivity, offer)
                                            } catch (error: Exception) {
                                                Toast.makeText(this@MainActivity, R.string.postilla_update_failed, Toast.LENGTH_LONG).show()
                                            } finally {
                                                updateDownloading = false
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            WispNavHost(
                        deepLinkUri = deepLinkUri.value,
                        onDeepLinkConsumed = { deepLinkUri.value = null },
                        isDarkTheme = isDarkTheme,
                        onToggleTheme = {
                            isDarkTheme = !isDarkTheme
                            prefs.edit().putBoolean("dark_theme", isDarkTheme).apply()
                        },
                        isLargeText = isLargeText,
                        onInterfaceChanged = {
                            isLargeText = interfacePrefs.isLargeText()
                            mediaSettings = MediaSettings(
                                autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
                                videoAutoPlay = interfacePrefs.isVideoAutoPlay(),
                                mediaLayoutStyle = interfacePrefs.getMediaLayoutStyle()
                            )
                        }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinkUri.value = intent.data?.toString()
    }
}
