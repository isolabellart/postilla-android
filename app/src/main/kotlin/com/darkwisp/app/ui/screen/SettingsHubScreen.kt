package com.darkwisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R
import com.darkwisp.app.ui.component.PostillaSettingsTitle

@OptIn(ExperimentalMaterial3Api::class)
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
                title = { PostillaSettingsTitle(stringResource(R.string.drawer_settings), Icons.Outlined.Settings) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val entries = listOf(
                Triple(R.string.drawer_interface, Icons.Outlined.Tune, onInterface),
                Triple(R.string.drawer_relays, Icons.Outlined.WifiTethering, onRelays),
                Triple(R.string.drawer_media_servers, Icons.Outlined.Cloud, onMediaServers),
                Triple(R.string.drawer_keys, Icons.Outlined.Key, onKeys),
                Triple(R.string.drawer_safety, Icons.Outlined.Block, onSafety),
                Triple(R.string.drawer_proof_of_work, Icons.Outlined.Shield, onPow),
                Triple(R.string.drawer_social_graph, Icons.Outlined.Hub, onSocialGraph),
                Triple(R.string.drawer_custom_emojis, Icons.Outlined.EmojiEmotions, onCustomEmojis),
                Triple(R.string.drawer_payment_targets, Icons.Outlined.TrackChanges, onPaymentTargets),
                Triple(R.string.drawer_relay_health, Icons.Outlined.Router, onRelayHealth),
                Triple(R.string.drawer_console, Icons.Outlined.BugReport, onConsole)
            )
            entries.forEach { (labelRes, icon, action) ->
                ListItem(
                    leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(stringResource(labelRes)) },
                    modifier = Modifier.clickable(onClick = action)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }
    }
}
