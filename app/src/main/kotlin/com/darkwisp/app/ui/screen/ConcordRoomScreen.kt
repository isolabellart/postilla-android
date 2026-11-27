package com.darkwisp.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.darkwisp.app.viewmodel.ConcordCommunityViewModel
import com.darkwisp.app.nostr.Blossom
import com.vitorpamplona.crypto.aes.AESGCM
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.concord.cord03Channels.ChannelChat
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.TranslationRepository
import com.darkwisp.app.repo.TranslationState
import com.darkwisp.app.repo.TranslationStatus
import com.darkwisp.app.ui.component.ProfilePicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.darkwisp.app.concord.ConcordChatMessage
import com.darkwisp.app.nostr.NostrSigner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcordRoomScreen(
    communityId: String,
    viewModel: ConcordCommunityViewModel,
    signer: NostrSigner?,
    eventRepository: EventRepository,
    translationRepository: TranslationRepository,
    blossomRepository: BlossomRepository,
    onBack: () -> Unit,
) {
    val communities by viewModel.communities.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val membersState by viewModel.members.collectAsState()
    val inviteState by viewModel.invite.collectAsState()
    val communityIcons by viewModel.communityIcons.collectAsState()
    val communityIcon = communityIcons[communityId]
    val community = communities.firstOrNull { it.id == communityId }
    var selectedChannelId by rememberSaveable(communityId) { mutableStateOf("") }
    val selectedChannel = channels.firstOrNull { it.id == selectedChannelId }
    val selectedChannelAccessible = selectedChannel?.let {
        !it.isWriterRestricted && (!it.isPrivate || !it.privateKeyHex.isNullOrBlank())
    } ?: (selectedChannelId.isNotBlank() && selectedChannelId == community?.generalChannelId)
    val restoringCommunityId by viewModel.restoringGeneralChannel.collectAsState()
    val generalChannelError by viewModel.generalChannelError.collectAsState()
    val messages = remember(communityId) { mutableStateListOf<ConcordChatMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by rememberSaveable(communityId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var showMembersDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showDirectInviteDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showCreateChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showManageChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showRenameChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showDeleteChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var directInviteRecipient by rememberSaveable(communityId) { mutableStateOf("") }
    var newChannelName by rememberSaveable(communityId) { mutableStateOf("") }
    var renamedChannelName by rememberSaveable(communityId) { mutableStateOf("") }
    var managementError by remember { mutableStateOf<String?>(null) }
    var managementBusy by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !sending && signer != null) {
            scope.launch {
                sending = true
                sendError = null
                try {
                    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not read the selected image")
                    }
                    require(bytes.isNotEmpty() && bytes.size <= 12 * 1024 * 1024) {
                        "Image must be smaller than 12 MB"
                    }
                    val key = RandomInstance.bytes(32)
                    val nonce = RandomInstance.bytes(16)
                    val cipher = AESGCM(key, nonce)
                    val encryptedBytes = cipher.encrypt(bytes)
                    val url = blossomRepository.uploadMedia(encryptedBytes, "application/octet-stream", "bin", signer)
                    val imeta = ChannelChat.encryptedImageImeta(
                        url = url,
                        mimeType = mimeType,
                        dim = null,
                        blurhash = null,
                        cipher = cipher,
                        originalHash = Blossom.sha256Hex(bytes),
                    )
                    val sent = viewModel.sendChannelMessage(
                        communityId,
                        selectedChannelId,
                        text.trim(),
                        signer,
                        imetas = listOf(imeta),
                    )
                    if (sent == null) throw IllegalStateException("Image message was not confirmed by a relay")
                    if (messages.none { it.id == sent.id }) messages.add(sent)
                    text = ""
                } catch (error: Exception) {
                    sendError = error.message ?: "Image upload failed"
                } finally {
                    sending = false
                }
            }
        }
    }

    LaunchedEffect(communityId, community) {
        if (community != null) {
            viewModel.refreshChannels(communityId)
            viewModel.loadCommunityIcon(communityId)
        }
    }

    LaunchedEffect(communityId, channels, community?.generalChannelId) {
        if (selectedChannelId.isBlank() || channels.none { it.id == selectedChannelId }) {
            selectedChannelId = channels.firstOrNull { it.name.equals("general", ignoreCase = true) }?.id
                ?: community?.generalChannelId.orEmpty()
        }
    }

    LaunchedEffect(communityId, selectedChannelId) {
        messages.clear()
        if (selectedChannelId.isNotBlank()) {
            viewModel.observeChannel(communityId, selectedChannelId).collect { message ->
                if (messages.none { it.id == message.id }) {
                    messages.add(message)
                    messages.sortBy { it.createdAt }
                }
            }
        }
    }

    LaunchedEffect(inviteState.url) {
        inviteState.url?.let { url ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Concord invite link"))
            viewModel.clearInviteResult()
        }
    }

    LaunchedEffect(communityId, community?.generalChannelId) {
        if (community != null && community.generalChannelId.isNullOrBlank()) {
            viewModel.restoreGeneralChannel(communityId)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (communityIcon != null) {
                            AsyncImage(
                                model = communityIcon,
                                contentDescription = "Community icon",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        Column {
                            Text(community?.name ?: communityId.take(12), style = MaterialTheme.typography.titleMedium)
                            Text("#" + (selectedChannel?.name ?: "general") + " · Concord", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                    .background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                sendError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; sendError = null },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message #" + (selectedChannel?.name ?: "general")) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (text.isNotBlank() && !sending && signer != null && selectedChannelId.isNotBlank() && selectedChannelAccessible) {
                                    val outgoing = text
                                    scope.launch {
                                        sending = true
                                        val sent = runCatching {
                                            viewModel.sendChannelMessage(communityId, selectedChannelId, outgoing, signer)
                                        }.getOrNull()
                                        if (sent != null) {
                                            if (messages.none { it.id == sent.id }) messages.add(sent)
                                            text = ""
                                        } else {
                                            sendError = "Message not confirmed by a relay"
                                        }
                                        sending = false
                                    }
                                }
                            },
                        ),
                    )
                    IconButton(
                        enabled = !sending && signer != null && selectedChannelId.isNotBlank() && selectedChannelAccessible,
                        onClick = { imagePicker.launch("image/*") },
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Attach image")
                    }
                    IconButton(
                        enabled = text.isNotBlank() && !sending && signer != null && selectedChannelId.isNotBlank() && selectedChannelAccessible,
                        onClick = {
                            val outgoing = text
                            scope.launch {
                                sending = true
                                val sent = runCatching {
                                    viewModel.sendChannelMessage(communityId, selectedChannelId, outgoing, signer)
                                }.getOrNull()
                                if (sent != null) {
                                    if (messages.none { it.id == sent.id }) messages.add(sent)
                                    text = ""
                                } else {
                                    sendError = "Message not confirmed by a relay"
                                }
                                sending = false
                            }
                        },
                    ) {
                        if (sending) CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        },
    ) { padding ->
        if (community == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "This community is not in the current membership list. Refresh the Concord list and reopen it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (community.generalChannelId.isNullOrBlank()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (restoringCommunityId == communityId) {
                    CircularProgressIndicator()
                    Text(
                        "Restoring the #general channel from the Concord Control Plane…",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        generalChannelError
                            ?: "The #general channel key is not cached on this device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = {
                            viewModel.clearGeneralChannelError()
                            viewModel.restoreGeneralChannel(communityId)
                        },
                    ) { Text("Retry channel recovery") }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(onClick = {
                        showMembersDialog = true
                        viewModel.loadMembers(communityId, signer)
                    }) { Text("Members") }
                    TextButton(onClick = { showDirectInviteDialog = true }) { Text("Invite by npub") }
                    TextButton(
                        enabled = signer != null && !inviteState.isCreating,
                        onClick = { viewModel.createInviteLink(communityId, signer) },
                    ) { Text(if (inviteState.isCreating) "Preparing…" else "Share link") }
                    TextButton(
                        enabled = signer != null,
                        onClick = { showCreateChannelDialog = true },
                    ) { Text("New channel") }
                }
                inviteState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                managementError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (channels.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            TextButton(
                                enabled = !channel.isPrivate || !channel.privateKeyHex.isNullOrBlank(),
                                onClick = { selectedChannelId = channel.id },
                            ) {
                                Text(
                                    (if (selectedChannelId == channel.id) "• " else "") +
                                        (if (channel.isPrivate) "🔒 " else "#") + channel.name,
                                )
                            }
                        }
                    }
                }
                if (selectedChannel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            renamedChannelName = selectedChannel.name
                            showManageChannelDialog = true
                            managementError = null
                        }) { Text("Manage channel") }
                    }
                }
                if (selectedChannel?.isWriterRestricted == true) {
                    Text(
                        "Restricted CORD-03 channel: Postilla can read messages, but sending is disabled until writer certificates are supported.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                if (selectedChannel?.isPrivate == true && selectedChannel.privateKeyHex.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "This private channel is listed, but its access key is not present in this membership.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No messages yet. Start the conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                items(messages, key = { it.id }) { message ->
                    val ownMessage = message.authorPubkey == signer?.pubkeyHex
                    var profile by remember(message.authorPubkey) {
                        mutableStateOf(eventRepository.getProfileData(message.authorPubkey))
                    }
                    var showTranslated by remember(message.id) { mutableStateOf(false) }
                    val translation by translationRepository.stateFor(message.id)
                        .collectAsState(initial = TranslationState())
                    var decryptedImage by remember(message.id) { mutableStateOf<ByteArray?>(null) }
                    var imageLoadFailed by remember(message.id) { mutableStateOf(false) }
                    LaunchedEffect(message.id, message.imageAttachments) {
                        val attachment = message.imageAttachments.firstOrNull()
                        if (attachment != null && decryptedImage == null && !imageLoadFailed) {
                            runCatching {
                                require(attachment.algo.equals("aes-gcm", ignoreCase = true)) {
                                    "Unsupported Concord image encryption algorithm"
                                }
                                require(attachment.url.startsWith("https://", ignoreCase = true)) {
                                    "Concord image URL must use HTTPS"
                                }
                                val ciphertext = withContext(Dispatchers.IO) {
                                    val connection = java.net.URL(attachment.url).openConnection()
                                    connection.connectTimeout = 8_000
                                    connection.readTimeout = 15_000
                                    require(connection.contentLengthLong <= 16L * 1024 * 1024 || connection.contentLengthLong < 0) {
                                        "Concord image exceeds the 16 MB download limit"
                                    }
                                    connection.getInputStream().use { it.readBytes() }.also {
                                        require(it.size <= 16 * 1024 * 1024) { "Concord image exceeds the 16 MB download limit" }
                                    }
                                }
                                val plain = AESGCM(attachment.key, attachment.nonce).decryptOrNull(ciphertext)
                                    ?: throw IllegalStateException("Image decryption failed")
                                val expectedHash = attachment.originalHash
                                if (!expectedHash.isNullOrBlank() &&
                                    !Blossom.sha256Hex(plain).equals(expectedHash, ignoreCase = true)
                                ) throw IllegalStateException("Image integrity check failed")
                                decryptedImage = plain
                            }.onFailure { imageLoadFailed = true }
                        }
                    }
                    LaunchedEffect(message.authorPubkey) {
                        if (profile == null) {
                            eventRepository.requestProfileIfMissing(message.authorPubkey)
                            repeat(12) {
                                if (profile == null) {
                                    kotlinx.coroutines.delay(750)
                                    profile = eventRepository.getProfileData(message.authorPubkey)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = if (ownMessage) Arrangement.End else Arrangement.Start,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (ownMessage) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(0.88f),
                        ) {
                            Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ProfilePicture(url = profile?.picture, size = 28)
                                    Column {
                                        Text(
                                            profile?.displayString ?: message.authorPubkey.take(12),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            message.authorPubkey.take(12) + "…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val visibleText = message.imageAttachments.fold(message.content) { content, attachment ->
                                    content.replace(attachment.url, "").trim()
                                }
                                if (visibleText.isNotBlank()) {
                                    Text(
                                        if (showTranslated && translation.status == TranslationStatus.DONE) {
                                            translation.translatedText
                                        } else visibleText,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (message.imageAttachments.isNotEmpty()) {
                                    when {
                                        decryptedImage != null -> AsyncImage(
                                            model = decryptedImage,
                                            contentDescription = "Encrypted Concord image",
                                            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp),
                                        )
                                        imageLoadFailed -> Text(
                                            "Image unavailable or failed its integrity check",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                            Text("Decrypting image…", modifier = Modifier.padding(start = 8.dp),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                if (visibleText.isNotBlank()) {
                                    TextButton(
                                        enabled = translation.status != TranslationStatus.IDENTIFYING_LANGUAGE &&
                                            translation.status != TranslationStatus.DOWNLOADING_MODEL &&
                                            translation.status != TranslationStatus.TRANSLATING &&
                                            translation.status != TranslationStatus.SAME_LANGUAGE,
                                        onClick = {
                                            if (translation.status == TranslationStatus.DONE) {
                                                showTranslated = !showTranslated
                                            } else {
                                                translationRepository.translateWithDeepL(message.id, visibleText)
                                            }
                                        },
                                    ) {
                                        Text(
                                            when {
                                                translation.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
                                                    translation.status == TranslationStatus.DOWNLOADING_MODEL ||
                                                    translation.status == TranslationStatus.TRANSLATING -> "Translating…"
                                                translation.status == TranslationStatus.ERROR -> "Retry translation"
                                                translation.status == TranslationStatus.SAME_LANGUAGE -> "Already in this language"
                                                showTranslated -> "Show original"
                                                translation.status == TranslationStatus.DONE -> "Show translation"
                                                else -> "Translate with DeepL"
                                            }
                                        )
                                    }
                                }
                                if (translation.status == TranslationStatus.ERROR) {
                                    Text(
                                        translation.errorMessage,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    if (showMembersDialog) {
        AlertDialog(
            onDismissRequest = { if (!membersState.isLoading) showMembersDialog = false },
            title = { Text("Community members") },
            text = {
                Column(modifier = Modifier.heightIn(max = 440.dp)) {
                    when {
                        membersState.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Loading members…", modifier = Modifier.padding(start = 10.dp))
                        }
                        membersState.error != null -> Text(
                            membersState.error!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                        membersState.members.isEmpty() -> Text("No Guestbook members were returned by the responding relays.")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(membersState.members, key = { it.pubkey }) { member ->
                                var profile by remember(member.pubkey) {
                                    mutableStateOf(eventRepository.getProfileData(member.pubkey))
                                }
                                LaunchedEffect(member.pubkey) {
                                    if (profile == null) {
                                        eventRepository.requestProfileIfMissing(member.pubkey)
                                        repeat(8) {
                                            if (profile == null) {
                                                kotlinx.coroutines.delay(750)
                                                profile = eventRepository.getProfileData(member.pubkey)
                                            }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ProfilePicture(url = profile?.picture, size = 34)
                                    Column(modifier = Modifier.padding(start = 10.dp)) {
                                        Text(
                                            profile?.displayString ?: member.pubkey.take(16) + "…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            member.pubkey,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMembersDialog = false }) { Text("Close") }
            },
        )
    }

    if (showDirectInviteDialog) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showDirectInviteDialog = false },
            title = { Text("Invite by npub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send a private CORD-05 Direct Invite. The recipient receives the community keys in a NIP-59 giftwrap.")
                    OutlinedTextField(
                        value = directInviteRecipient,
                        onValueChange = { directInviteRecipient = it; managementError = null },
                        label = { Text("Recipient npub") },
                        singleLine = true,
                    )
                    Text(
                        "A Direct Invite cannot be revoked after delivery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null && directInviteRecipient.isNotBlank(),
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                viewModel.sendDirectInvite(communityId, directInviteRecipient, signer)
                                directInviteRecipient = ""
                                showDirectInviteDialog = false
                                managementError = null
                            } catch (error: Exception) {
                                managementError = error.message ?: "Direct Invite failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Sending…" else "Send invite") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showDirectInviteDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showCreateChannelDialog) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showCreateChannelDialog = false },
            title = { Text("Create public channel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newChannelName,
                        onValueChange = { newChannelName = it; managementError = null },
                        label = { Text("Channel name") },
                        singleLine = true,
                    )
                    Text(
                        "This creates a public CORD-03 channel. Private-channel creation needs a separate key-distribution flow and is not exposed until it can be handled safely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null && newChannelName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                viewModel.createPublicChannel(communityId, newChannelName, signer)
                                newChannelName = ""
                                showCreateChannelDialog = false
                            } catch (error: Exception) {
                                managementError = error.message ?: "Channel creation failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Creating…" else "Create") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showCreateChannelDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showManageChannelDialog && selectedChannel != null) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showManageChannelDialog = false },
            title = { Text("Manage #${selectedChannel.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renamedChannelName,
                        onValueChange = { renamedChannelName = it; managementError = null },
                        label = { Text("Channel name") },
                        singleLine = true,
                    )
                    if (selectedChannel.name.equals("general", ignoreCase = true)) {
                        Text(
                            "The general channel is protected from deletion in Postilla.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null && renamedChannelName.isNotBlank() && renamedChannelName != selectedChannel.name,
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                viewModel.renameChannel(communityId, selectedChannel.id, renamedChannelName, signer)
                                showManageChannelDialog = false
                            } catch (error: Exception) {
                                managementError = error.message ?: "Channel rename failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Saving…" else "Rename") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !managementBusy && signer != null && !selectedChannel.name.equals("general", ignoreCase = true),
                        onClick = { showManageChannelDialog = false; showDeleteChannelDialog = true },
                    ) { Text("Delete") }
                    TextButton(enabled = !managementBusy, onClick = { showManageChannelDialog = false }) { Text("Close") }
                }
            },
        )
    }

    if (showDeleteChannelDialog && selectedChannel != null) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showDeleteChannelDialog = false },
            title = { Text("Delete channel?") },
            text = { Text("This is a terminal Concord channel deletion. The channel id cannot be reused. Continue only if you are certain.") },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null,
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                viewModel.deleteChannel(communityId, selectedChannel.id, signer)
                                showDeleteChannelDialog = false
                            } catch (error: Exception) {
                                managementError = error.message ?: "Channel deletion failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Deleting…" else "Delete permanently") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showDeleteChannelDialog = false }) { Text("Cancel") }
            },
        )
    }
    }
}
}
