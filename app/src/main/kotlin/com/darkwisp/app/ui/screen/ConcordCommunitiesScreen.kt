package com.darkwisp.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkwisp.app.nostr.Blossom
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.ui.component.ProfilePicture
import com.vitorpamplona.crypto.aes.AESGCM
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.concord.cord02Community.ImagePointer
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.viewmodel.ConcordCommunityViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcordCommunitiesScreen(
    viewModel: ConcordCommunityViewModel,
    relayPool: RelayPool,
    signer: NostrSigner?,
    blossomRepository: BlossomRepository,
    eventRepository: EventRepository,
    onOpenCommunity: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    val communities by viewModel.communities.collectAsState()
    val creation by viewModel.creation.collectAsState()
    val join by viewModel.join.collectAsState()
    val invite by viewModel.invite.collectAsState()
    val directInvites by viewModel.directInvites.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLoading by viewModel.isLoading.collectAsState()
    val communityListWarning by viewModel.communityListWarning.collectAsState()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var showJoinDialog by rememberSaveable { mutableStateOf(false) }
    var showDirectInvitesDialog by rememberSaveable { mutableStateOf(false) }
    var inviteUrl by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var iconUri by remember { mutableStateOf<Uri?>(null) }
    var uploadingIcon by remember { mutableStateOf(false) }
    var iconUploadError by remember { mutableStateOf<String?>(null) }
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        iconUri = uri
        iconUploadError = null
    }

    LaunchedEffect(signer?.pubkeyHex) {
        viewModel.init(relayPool, signer)
        viewModel.refreshDirectInvites(signer)
    }
    LaunchedEffect(join.result?.communityId) {
        if (join.result != null) {
            showJoinDialog = false
            showDirectInvitesDialog = false
            inviteUrl = ""
        }
    }
    LaunchedEffect(creation.result?.communityId) {
        if (creation.result != null) {
            showCreateDialog = false
            name = ""
            description = ""
            iconUri = null
            iconUploadError = null
        }
    }
    LaunchedEffect(invite.url) {
        invite.url?.let { url ->
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Concord invite"))
            } catch (_: Exception) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Concord invite link", url))
                Toast.makeText(
                    context,
                    if (clipboard != null) "Invite link copied to clipboard" else "Could not open the share sheet",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                viewModel.clearInviteResult()
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = { Text("Communities") },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create community")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { signer?.let(viewModel::refreshLocalCommunities) },
                    enabled = signer != null && !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Refresh list")
                }
                Row {
                    TextButton(
                        onClick = {
                            viewModel.refreshDirectInvites(signer)
                            showDirectInvitesDialog = true
                        },
                        enabled = signer != null && !directInvites.isLoading,
                    ) {
                        Text(if (directInvites.isLoading) "Invites…" else "Invites (${directInvites.invites.size})")
                    }
                    TextButton(onClick = { showJoinDialog = true }) { Text("Join by link") }
                    TextButton(onClick = { showCreateDialog = true }) { Text("Create") }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Discover public Concord communities",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Browse public community listings in Vector Hub. Open a listing to get its invite link, then join it here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://vectorapp.io/hub/"))
                            )
                        },
                    ) { Text("Explore Vector Community Hub") }
                }
            }

            communityListWarning?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warning.startsWith("Community list")) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            creation.result?.let { result ->
                val status = if (result.isFullyPublished) {
                    "Community created and acknowledged by relays"
                } else {
                    "${result.pendingEventIds.size} event(s) still need relay confirmation"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            join.result?.let { result ->
                val status = if (result.isFullyPublished) "Joined ${result.name}" else "Joined locally; ${result.pendingEventIds.size} list event(s) still need confirmation"
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            join.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            invite.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            creation.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                communities.isEmpty() && isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                communities.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Your communities", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Create a Concord community to start a private, end-to-end encrypted space.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(communities, key = { it.id }) { community ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                                .clickable { onOpenCommunity(community.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(46.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            community.name.take(1).uppercase(),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(community.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "general · ${community.relays.size} relay(s)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        community.id,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    enabled = !invite.isCreating && signer != null,
                                    onClick = { viewModel.createInviteLink(community.id, signer) },
                                ) {
                                    if (invite.isCreating) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    else Icon(Icons.Outlined.Share, contentDescription = "Create and share invite")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinDialog) {
        AlertDialog(
            onDismissRequest = { if (!join.isJoining) showJoinDialog = false },
            title = { Text("Join a community") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Paste a Concord invite link from Vector or another member.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inviteUrl,
                        onValueChange = { inviteUrl = it },
                        label = { Text("Invite link") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!pasted.isNullOrBlank()) inviteUrl = pasted.trim()
                        }) { Text("Paste from clipboard") }
                    }
                    if (join.error != null) {
                        Text(join.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = inviteUrl.isNotBlank() && !join.isJoining && signer != null,
                    onClick = {
                        viewModel.clearJoinResult()
                        viewModel.joinCommunityFromInvite(inviteUrl.trim(), signer)
                    },
                ) {
                    if (join.isJoining) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text("Join")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!join.isJoining) showJoinDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { if (!creation.isCreating) showCreateDialog = false },
            title = { Text("Create a community") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Community name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            if (iconUri != null) {
                                AsyncImage(model = iconUri, contentDescription = "Community icon preview",
                                    modifier = Modifier.fillMaxSize())
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Image, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Community icon", style = MaterialTheme.typography.labelLarge)
                            Text("Encrypted for community members", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { iconPicker.launch("image/*") }) {
                                Text(if (iconUri == null) "Choose image" else "Change image")
                            }
                        }
                    }
                    iconUploadError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (creation.error != null) {
                        Text(creation.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !creation.isCreating && !uploadingIcon && signer != null,
                    onClick = {
                        viewModel.clearResult()
                        scope.launch {
                            uploadingIcon = true
                            iconUploadError = null
                            try {
                                val pointer = iconUri?.let { uri ->
                                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                                    val bytes = withContext(Dispatchers.IO) {
                                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            ?: throw IllegalStateException("Could not read the selected icon")
                                    }
                                    require(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) {
                                        "Community icon must be smaller than 8 MB"
                                    }
                                    val key = RandomInstance.bytes(32)
                                    val nonce = RandomInstance.bytes(16)
                                    val cipher = AESGCM(key, nonce)
                                    val encrypted = cipher.encrypt(bytes)
                                    val url = blossomRepository.uploadMedia(encrypted, "application/octet-stream", "bin", signer)
                                    ImagePointer(
                                        url = url,
                                        key = key.toHexKey(),
                                        nonce = nonce.toHexKey(),
                                        hash = Blossom.sha256Hex(bytes),
                                    )
                                }
                                viewModel.createCommunity(
                                    signer,
                                    name.trim(),
                                    description.trim().ifBlank { null },
                                    icon = pointer,
                                )
                            } catch (error: Exception) {
                                iconUploadError = error.message ?: "Community icon upload failed"
                            } finally {
                                uploadingIcon = false
                            }
                        }
                    },
                ) {
                    if (creation.isCreating || uploadingIcon) CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    else Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!creation.isCreating) showCreateDialog = false }) { Text("Cancel") }
            },
        )
    if (showDirectInvitesDialog) {
        AlertDialog(
            onDismissRequest = { if (!join.isJoining) showDirectInvitesDialog = false },
            title = { Text("Direct community invites") },
            text = {
                Column(modifier = Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    directInvites.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (directInvites.isLoading && directInvites.invites.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Checking your DM inbox relays…", modifier = Modifier.padding(start = 10.dp))
                        }
                    } else if (directInvites.invites.isEmpty()) {
                        Text("No pending Direct Invites were found on your configured inbox relays.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(directInvites.invites, key = { it.wrapId }) { opened ->
                                var inviterProfile by remember(opened.sender) {
                                    mutableStateOf(eventRepository.getProfileData(opened.sender))
                                }
                                LaunchedEffect(opened.sender) {
                                    if (inviterProfile == null) {
                                        eventRepository.requestProfileIfMissing(opened.sender)
                                        repeat(8) {
                                            if (inviterProfile == null) {
                                                kotlinx.coroutines.delay(750)
                                                inviterProfile = eventRepository.getProfileData(opened.sender)
                                            }
                                        }
                                    }
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        opened.invite.name.ifBlank { opened.invite.communityId.take(12) },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProfilePicture(url = inviterProfile?.picture, size = 38)
                                        Column(modifier = Modifier.padding(start = 10.dp)) {
                                            Text(
                                                inviterProfile?.displayString ?: "Concord member",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                "Invited by ${opened.sender.take(16)}…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Text(
                                        "Community ID: ${opened.invite.communityId.take(16)}…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    val channelNames = opened.invite.channels.map { it.name }.filter { it.isNotBlank() }
                                    if (channelNames.isNotEmpty()) {
                                        Text(
                                            "Channels included: ${channelNames.joinToString(", ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Text(
                                        "This is a private CORD-05 invitation. Accepting adds the community keys to this account.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (opened.isExpired()) {
                                        Text(
                                            "Expired — this invite can no longer be accepted.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        TextButton(
                                            enabled = !join.isJoining,
                                            onClick = { viewModel.declineDirectInvite(opened.wrapId) },
                                        ) { Text("Decline") }
                                        if (!opened.isExpired()) {
                                            TextButton(
                                                enabled = signer != null && !join.isJoining,
                                                onClick = { viewModel.acceptDirectInvite(opened, signer) },
                                            ) { Text(if (join.isJoining) "Joining…" else "Accept invite") }
                                        }
                                    }
                                    if (join.error != null) {
                                        Text(join.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDirectInvitesDialog = false }) { Text("Close") }
            },
        )
    }
    }
}
