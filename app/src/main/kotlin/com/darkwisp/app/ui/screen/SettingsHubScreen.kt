package com.darkwisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.darkwisp.app.R

/**
 * Settings landing page. Sub-pages are pushed on the navigation stack so Back
 * returns here; leaving this page returns to the feed.
 */
@Composable
fun SettingsHubScreen(
    onBack: () -> Unit,
    onInterface: () -> Unit,
    onRelays: () -> Unit,
    onMediaServers: () -> Unit,
    onKeys: () -> Unit,
    onSafety: () -> Unit,
    onPow: () -> Unit,
    onSocialGraph: () -> Unit,
    onCustomEmojis: () -> Unit,
    onPaymentTargets: () -> Unit,
    onRelayHealth: () -> Unit,
    onConsole: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize()) {
            val entries = listOf(
                Triple(R.string.drawer_interface, R.string.drawer_interface, onInterface),
                Triple(R.string.drawer_relays, R.string.drawer_relays, onRelays),
                Triple(R.string.drawer_media_servers, R.string.drawer_media_servers, onMediaServers),
                Triple(R.string.drawer_keys, R.string.drawer_keys, onKeys),
                Triple(R.string.drawer_safety, R.string.drawer_safety, onSafety),
                Triple(R.string.drawer_proof_of_work, R.string.drawer_proof_of_work, onPow),
                Triple(R.string.drawer_social_graph, R.string.drawer_social_graph, onSocialGraph),
                Triple(R.string.drawer_custom_emojis, R.string.drawer_custom_emojis, onCustomEmojis),
                Triple(R.string.drawer_payment_targets, R.string.drawer_payment_targets, onPaymentTargets),
                Triple(R.string.drawer_relay_health, R.string.drawer_relay_health, onRelayHealth),
                Triple(R.string.drawer_console, R.string.drawer_console, onConsole)
            )
            entries.forEach { (labelRes, _, action) ->
                ListItem(
                    headlineContent = { Text(stringResource(labelRes)) },
                    modifier = Modifier.clickable(onClick = action)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }
    }
}
