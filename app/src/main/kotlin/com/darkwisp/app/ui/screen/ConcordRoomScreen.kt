package com.darkwisp.app.ui.screen

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import com.darkwisp.app.R
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
import com.darkwisp.app.ui.component.EmojiReactionPopup
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.darkwisp.app.concord.ConcordCommunityRepository
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.ui.theme.WispThemeColors
import com.vitorpamplona.quartz.concord.cord04Roles.ConcordPermissions
import kotlinx.coroutines.CancellationException
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
    onZapAuthor: (String) -> Unit = {},
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
) {
    val communities by viewModel.communities.collectAsState()
    val channelsByCommunity by viewModel.channelsByCommunity.collectAsState()
    val channels = channelsByCommunity[communityId].orEmpty()
    val visibleChannels = channels.filter { it.canViewChannel }
    val channelSyncByCommunity by viewModel.channelSyncByCommunity.collectAsState()
    val channelSync = channelSyncByCommunity[communityId] ?: ConcordCommunityViewModel.ChannelSyncState()
    val membersByCommunity by viewModel.membersByCommunity.collectAsState()
    val membersState = membersByCommunity[communityId] ?: ConcordCommunityViewModel.MembersState(communityId = communityId)
    val rolesByCommunity by viewModel.rolesByCommunity.collectAsState()
    val rolesState = rolesByCommunity[communityId] ?: ConcordCommunityViewModel.RolesState()
    val inviteState by viewModel.invite.collectAsState()
    val inviteLinksByCommunity by viewModel.inviteLinksByCommunity.collectAsState()
    val inviteLinksState = inviteLinksByCommunity[communityId] ?: ConcordCommunityViewModel.InviteLinksState(communityId = communityId)
    val communityIcons by viewModel.communityIcons.collectAsState()
    val communityIcon = communityIcons[communityId]
    val community = communities.firstOrNull { it.id == communityId }
    var selectedChannelId by rememberSaveable(communityId) { mutableStateOf("") }
    val selectedChannel = visibleChannels.firstOrNull { it.id == selectedChannelId }
    val selectedChannelAccessible = selectedChannel?.let {
        it.canViewChannel && (!it.isPrivate || !it.privateKeyHex.isNullOrBlank())
    } ?: false
    // A partial authenticated snapshot may render readable group data, but it does
    // not authorize outbound actions. Sending and reactions require a verified relay
    // quorum plus the channel permission, private key and any restricted writer credential.
    val selectedChannelCanSend = selectedChannel?.let {
        !channelSync.isLoading &&
            channelSync.controlReady &&
            channelSync.controlComplete &&
            it.canSendMessages &&
            (!it.writeRestricted || it.writerCredential?.canSendMessages == true) &&
            (!it.isPrivate || !it.privateKeyHex.isNullOrBlank())
    } ?: false
    val selectedChannelCanAttach = selectedChannelCanSend && selectedChannel?.canAttachFiles == true
    val selectedChannelCanReact = selectedChannel?.let {
        !channelSync.isLoading &&
            channelSync.controlReady &&
            channelSync.controlComplete &&
            it.canViewChannel &&
            it.canAddReactions &&
            (!it.isPrivate || !it.privateKeyHex.isNullOrBlank()) &&
            (!it.isLocked || it.writerCredential?.canAddReactions == true)
    } ?: false
    val restoringCommunityId by viewModel.restoringGeneralChannel.collectAsState()
    val generalChannelError by viewModel.generalChannelError.collectAsState()
    val messages = remember(communityId) { mutableStateListOf<ConcordChatMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val context = LocalContext.current
    var text by rememberSaveable(communityId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var replyToMessage by remember(communityId) { mutableStateOf<ConcordChatMessage?>(null) }
    var inlineReplyMode by remember(communityId) { mutableStateOf(false) }
    var selectedThreadRoot by remember(communityId) { mutableStateOf<ConcordChatMessage?>(null) }
    var showThreadDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    var suppressAutoScroll by remember { mutableStateOf(false) }
    val timelineMessages = messages.filter { it.kind == 9 }
    val selectedThreadReplies = messages.filter {
        it.kind == 1111 && selectedThreadRoot != null &&
            (it.rootId == selectedThreadRoot?.id || it.replyToId == selectedThreadRoot?.id)
    }.sortedBy { it.orderingAtMs }
    var showChannelMenu by rememberSaveable(communityId) { mutableStateOf(false) }
    var showMembersDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var memberSearchQuery by rememberSaveable(communityId) { mutableStateOf("") }
    var memberRoleFilter by rememberSaveable(communityId) { mutableStateOf("") }
    var showRolesDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showRoleEditorDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showMemberRolesDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var editingRoleId by rememberSaveable(communityId) { mutableStateOf<String?>(null) }
    var roleDraftName by rememberSaveable(communityId) { mutableStateOf("") }
    var roleDraftPosition by rememberSaveable(communityId) { mutableStateOf("1") }
    var roleDraftPermissions by remember(communityId) { mutableStateOf(ConcordPermissions.NONE) }
    var roleDialogError by remember { mutableStateOf<String?>(null) }
    var memberRolesTarget by remember(communityId) { mutableStateOf<ConcordCommunityRepository.CommunityMember?>(null) }
    var memberRoleSelection by remember(communityId) { mutableStateOf<Set<String>>(emptySet()) }
    var showDirectInviteDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showCreateChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showManageChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showRenameChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showDeleteChannelDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var directInviteRecipient by rememberSaveable(communityId) { mutableStateOf("") }
    var newChannelName by rememberSaveable(communityId) { mutableStateOf("") }
    var newChannelPrivate by rememberSaveable(communityId) { mutableStateOf(false) }
    var renamedChannelName by rememberSaveable(communityId) { mutableStateOf("") }
    var managementError by remember { mutableStateOf<String?>(null) }
    var managementBusy by remember { mutableStateOf(false) }
    var showEditMetadataDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var showInviteLinksDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var pendingInviteRevocationToken by rememberSaveable(communityId) { mutableStateOf<String?>(null) }
    var metadataDraftName by rememberSaveable(communityId) { mutableStateOf("") }
    var metadataDraftDescription by rememberSaveable(communityId) { mutableStateOf("") }
    var metadataDraftRelays by rememberSaveable(communityId) { mutableStateOf("") }
    val memberRoleOptions = remember(membersState.members) {
        buildList {
            if (membersState.members.any { it.isOwner }) add("Owner")
            addAll(membersState.members.flatMap { it.roleNames }.filter(String::isNotBlank))
        }.distinct().sorted()
    }
    val filteredMembers = remember(membersState.members, memberSearchQuery, memberRoleFilter) {
        membersState.members.filter { member ->
            val query = memberSearchQuery.trim()
            val profileName = eventRepository.getProfileData(member.pubkey)?.displayString.orEmpty()
            val matchesQuery = query.isBlank() ||
                member.pubkey.contains(query, ignoreCase = true) ||
                profileName.contains(query, ignoreCase = true)
            val matchesRole = memberRoleFilter.isBlank() ||
                (memberRoleFilter.equals("Owner", ignoreCase = true) && member.isOwner) ||
                member.roleNames.any { it.equals(memberRoleFilter, ignoreCase = true) }
            matchesQuery && matchesRole
        }
    }
    fun submitTextMessage() {
        val outgoing = text.trim()
        if (
            outgoing.isBlank() || sending || signer == null ||
            selectedChannelId.isBlank() || !selectedChannelAccessible || !selectedChannelCanSend
        ) return

        sending = true
        sendError = null
        scope.launch {
            try {
                val sent = viewModel.sendChannelMessage(
                    communityId = communityId,
                    channelId = selectedChannelId,
                    text = outgoing,
                    signer = signer,
                    replyTo = replyToMessage,
                    inlineReply = inlineReplyMode,
                )
                if (sent == null) {
                    sendError = "The relay did not confirm this message. Your draft has been kept."
                } else {
                    if (messages.none { it.id == sent.id }) messages.add(sent)
                    messages.sortBy { it.orderingAtMs }
                    text = ""
                    replyToMessage = null
                    inlineReplyMode = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                sendError = error.message ?: "Message could not be sent. Your draft has been kept."
            } finally {
                sending = false
            }
        }
    }

    var showModerationDialog by rememberSaveable(communityId) { mutableStateOf(false) }
    var moderationTargetPubkey by rememberSaveable(communityId) { mutableStateOf<String?>(null) }
    var moderationAction by rememberSaveable(communityId) { mutableStateOf("") }
    var moderationRequiresRefounding by rememberSaveable(communityId) { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (
            uri != null && !sending && signer != null &&
            selectedChannelAccessible && selectedChannelCanAttach
        ) {
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
                        replyTo = replyToMessage,
                        inlineReply = inlineReplyMode,
                    )
                    if (sent == null) throw IllegalStateException("Image message was not confirmed by a relay")
                    if (messages.none { it.id == sent.id }) messages.add(sent)
                    messages.sortBy { it.orderingAtMs }
                    text = ""
                    replyToMessage = null
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

    LaunchedEffect(communityId, visibleChannels, community?.generalChannelId) {
        if (selectedChannelId.isBlank() || visibleChannels.none { it.id == selectedChannelId }) {
            selectedChannelId = visibleChannels.firstOrNull { it.name.equals("general", ignoreCase = true) }?.id
                ?: visibleChannels.firstOrNull()?.id.orEmpty()
        }
    }

    LaunchedEffect(communityId, selectedChannelId) {
        messages.clear()
        if (selectedChannelId.isNotBlank()) {
            viewModel.observeChannel(communityId, selectedChannelId).collect { message ->
                if (messages.none { it.id == message.id }) {
                    messages.add(message)
                    messages.sortBy { it.orderingAtMs }
                }
            }
        }
    }

    LaunchedEffect(inviteState.url) {
        inviteState.url?.let { url ->
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Concord invite link"))
            } catch (_: Exception) {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Concord invite link", url))
                android.widget.Toast.makeText(
                    context,
                    if (clipboard != null) "Invite link copied to clipboard" else "Could not open the share sheet",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } finally {
                viewModel.clearInviteResult()
            }
        }
    }

    LaunchedEffect(communityId, community?.generalChannelId) {
        if (community != null && community.generalChannelId.isNullOrBlank()) {
            viewModel.restoreGeneralChannel(communityId)
        }
    }

    LaunchedEffect(selectedChannelId) {
        replyToMessage = null
        inlineReplyMode = false
        selectedThreadRoot = null
        showThreadDialog = false
    }

    // Keep the roster converged with the Guestbook and observed channel activity
    // while the management panel is open. The effect is cancelled on close, room
    // change or account switch; the ViewModel prevents overlapping reads.
    LaunchedEffect(showMembersDialog, communityId, signer?.pubkeyHex) {
        val activeSigner = signer
        if (showMembersDialog && activeSigner != null) {
            viewModel.refreshRoles(communityId, activeSigner)
            viewModel.loadMembers(communityId, activeSigner)
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                viewModel.refreshRoles(communityId, activeSigner)
                viewModel.loadMembers(communityId, activeSigner)
            }
        }
    }

    LaunchedEffect(timelineMessages.size) {
        if (timelineMessages.isNotEmpty() && !suppressAutoScroll) {
            listState.animateScrollToItem((timelineMessages.size - 1).coerceAtLeast(0))
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
                            Text(
                                community?.name ?: "Concord community",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${visibleChannels.size} channels · encrypted community",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    item {
                        Text(
                            "CHANNELS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    if (visibleChannels.isEmpty()) {
                        item {
                            Text(
                                "No channels loaded yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                            )
                        }
                    }
                    items(visibleChannels, key = { "drawer-${it.id}" }) { channel ->
                        NavigationDrawerItem(
                            label = {
                                Text(channel.name, maxLines = 1)
                            },
                            icon = {
                                Icon(
                                    if (channel.isPrivate) Icons.Default.Lock else Icons.Default.Tag,
                                    contentDescription = if (channel.isPrivate) "Private channel" else "Public channel",
                                )
                            },
                            selected = selectedChannelId == channel.id,
                            onClick = {
                                if (!channel.isPrivate || !channel.privateKeyHex.isNullOrBlank()) {
                                    selectedChannelId = channel.id
                                    scope.launch { drawerState.close() }
                                } else {
                                    android.widget.Toast.makeText(context, "This private channel key is not available on this account", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                    item {
                        Text(
                            "COMMUNITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 22.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Edit group details") },
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            selected = false,
                            enabled = signer != null,
                            onClick = {
                                scope.launch { drawerState.close() }
                                metadataDraftName = community?.name.orEmpty()
                                metadataDraftDescription = community?.description.orEmpty()
                                metadataDraftRelays = community?.relays.orEmpty().joinToString("\n")
                                managementError = null
                                showEditMetadataDialog = true
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Manage roles") },
                            icon = { Icon(Icons.Default.Security, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showRolesDialog = true
                                roleDialogError = null
                                viewModel.refreshRoles(communityId, signer)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Manage public invite links") },
                            icon = { Icon(Icons.Default.Link, contentDescription = null) },
                            selected = false,
                            enabled = signer != null,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showInviteLinksDialog = true
                                viewModel.refreshInviteLinks(communityId, signer)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Members") },
                            icon = { Icon(Icons.Default.Group, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showMembersDialog = true
                                viewModel.loadMembers(communityId, signer)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Invite by npub") },
                            icon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showDirectInviteDialog = true
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text(if (inviteState.isCreating) "Preparing invite link…" else "Share public invite link") },
                            icon = { Icon(Icons.Default.Link, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (signer != null && !inviteState.isCreating) {
                                    viewModel.createInviteLink(communityId, signer)
                                } else if (signer == null) {
                                    android.widget.Toast.makeText(context, "A writable Nostr signer is required", android.widget.Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Create channel") },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                if (signer != null) {
                                    newChannelPrivate = false
                                    newChannelName = ""
                                    managementError = null
                                    showCreateChannelDialog = true
                                }
                                else android.widget.Toast.makeText(context, "A writable Nostr signer is required", android.widget.Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Manage selected channel") },
                            icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                selectedChannel?.let {
                                    if (signer != null) {
                                        renamedChannelName = it.name
                                        showManageChannelDialog = true
                                        managementError = null
                                    } else {
                                        android.widget.Toast.makeText(context, "A writable Nostr signer is required", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    item {
                        NavigationDrawerItem(
                            label = { Text("Refresh channels") },
                            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                viewModel.refreshChannels(communityId)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
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
                actions = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "Open community navigation")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (selectedChannel != null && !selectedChannelCanSend) {
                        val accessMessage = when {
                            !selectedChannel.canSendMessages ->
                                "This account does not have SEND_MESSAGES permission in this channel."
                            selectedChannel.writeRestricted &&
                                selectedChannel.writerCredential?.canSendMessages != true ->
                                "Writer access is not ready for this account yet."
                            selectedChannel.isPrivate && selectedChannel.privateKeyHex.isNullOrBlank() ->
                                "The private channel key is not available on this account."
                            channelSync.isLoading ->
                                "Concord is still synchronizing this channel."
                            !channelSync.controlReady ->
                                "A verified Control Plane snapshot is not available yet."
                            !channelSync.controlComplete ->
                                "The Control Plane relay quorum is not verified. Sending is paused."
                            else -> "Channel access is not ready for this account."
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    accessMessage,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    enabled = !channelSync.isLoading,
                                    onClick = { viewModel.refreshChannels(communityId) },
                                ) { Text("Retry") }
                            }
                        }
                    }
                    sendError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "MESSAGE TO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "#" + (selectedChannel?.name ?: "general"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "· Concord",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            replyToMessage?.let { parent ->
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                if (inlineReplyMode) "Quoting a message" else "Replying to a message",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                            Text(
                                                parent.authorPubkey.take(16) + "…",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                        }
                                        TextButton(onClick = {
                                            replyToMessage = null
                                            inlineReplyMode = false
                                        }) { Text("Cancel") }
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    enabled = !sending && signer != null &&
                                        selectedChannelAccessible && selectedChannelCanAttach,
                                    onClick = { imagePicker.launch("image/*") },
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = "Attach image")
                                }
                                Box(
                                    modifier = Modifier.weight(1f)
                                        .heightIn(min = 42.dp, max = 112.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surface,
                                            RoundedCornerShape(18.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    BasicTextField(
                                        value = text,
                                        onValueChange = { text = it; sendError = null },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = signer != null && selectedChannelAccessible,
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                            MaterialTheme.colorScheme.primary,
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                                        maxLines = 6,
                                        decorationBox = { innerTextField ->
                                            Box {
                                                if (text.isBlank()) {
                                                    Text(
                                                        "Write a message to #" + (selectedChannel?.name ?: "general"),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        },
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = if (
                                        text.isNotBlank() && !sending && signer != null &&
                                        selectedChannelAccessible && selectedChannelCanSend
                                    ) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                ) {
                                    IconButton(
                                        enabled = text.isNotBlank() && !sending && signer != null &&
                                            selectedChannelAccessible && selectedChannelCanSend,
                                        onClick = { submitTextMessage() },
                                    ) {
                                        if (sending) {
                                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Send,
                                                contentDescription = "Send message",
                                                tint = if (
                                                    text.isNotBlank() && !sending && signer != null &&
                                                    selectedChannelAccessible && selectedChannelCanSend
                                                ) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                val syncMessage = when {
                    channelSync.isLoading -> "Synchronizing Concord channels…"
                    channelSync.error != null -> "Channel synchronization warning: ${channelSync.error}"
                    channelSync.controlRequested == 0 ->
                        "The channel catalogue has not yet been synchronized for this account."
                    !channelSync.controlReady ->
                        "No authenticated Control Plane snapshot is available yet. Refresh the channel list and check the community relays."
                    !channelSync.controlComplete ->
                        "The Control Plane snapshot is readable, but its relay quorum is not verified (${channelSync.controlResponded}/${channelSync.controlRequested}). Channels remain read-only until permissions are confirmed."
                    selectedChannel?.writeRestricted == true &&
                        selectedChannel.writerCredential?.canSendMessages != true ->
                        "This channel requires a current CORD-03 writer delivery for this account. Refresh channel access."
                    else -> null
                }
                syncMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                message,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                enabled = !channelSync.isLoading,
                                onClick = { viewModel.refreshChannels(communityId) },
                            ) { Text("Retry") }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            memberSearchQuery = ""
                            memberRoleFilter = ""
                            showMembersDialog = true
                            viewModel.loadMembers(communityId, signer)
                        },
                        label = { Text("Members") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            showRolesDialog = true
                            roleDialogError = null
                            viewModel.refreshRoles(communityId, signer)
                        },
                        label = { Text("Roles") },
                    )
                    FilterChip(
                        selected = false,
                        onClick = { showDirectInviteDialog = true },
                        label = { Text("Invite") },
                    )
                    FilterChip(
                        selected = false,
                        enabled = signer != null,
                        onClick = {
                            newChannelPrivate = false
                            newChannelName = ""
                            managementError = null
                            showCreateChannelDialog = true
                        },
                        label = { Text("Create channel") },
                    )
                }
                if (channels.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        visibleChannels.forEach { channel ->
                            FilterChip(
                                selected = selectedChannelId == channel.id,
                                enabled = channel.canViewChannel &&
                                    (!channel.isPrivate || !channel.privateKeyHex.isNullOrBlank()),
                                onClick = { selectedChannelId = channel.id },
                                label = {
                                    Text(
                                        (if (channel.isPrivate) "🔒 " else "# ") + channel.name,
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                inviteState.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                managementError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                if (selectedChannel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            (if (selectedChannel.isPrivate) "🔒 " else "# ") + selectedChannel.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            IconButton(onClick = { showChannelMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Channel actions")
                            }
                            DropdownMenu(
                                expanded = showChannelMenu,
                                onDismissRequest = { showChannelMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename channel") },
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                    enabled = signer != null,
                                    onClick = {
                                        showChannelMenu = false
                                        renamedChannelName = selectedChannel.name
                                        showManageChannelDialog = true
                                        managementError = null
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete channel") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    enabled = signer != null && !selectedChannel.name.equals("general", ignoreCase = true),
                                    onClick = {
                                        showChannelMenu = false
                                        showDeleteChannelDialog = true
                                        managementError = null
                                    },
                                )
                            }
                        }
                    }
                }
                if (selectedChannel?.isPrivate == true && selectedChannel.privateKeyHex.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "This private channel is listed, but its access key is not present in this membership.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (timelineMessages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            if (loadingOlder) "Loading Concord history…" else "No messages in this channel yet. Waiting for relay history…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                enabled = !loadingOlder,
                                onClick = {
                                    scope.launch {
                                        loadingOlder = true
                                        suppressAutoScroll = true
                                        try {
                                            val older = viewModel.loadOlderChannelMessages(communityId, selectedChannelId)
                                            older.forEach { if (messages.none { existing -> existing.id == it.id }) messages.add(it) }
                                            messages.sortBy { it.orderingAtMs }
                                        } catch (error: Exception) {
                                            sendError = error.message ?: "Could not load older messages"
                                        } finally {
                                            loadingOlder = false
                                            suppressAutoScroll = false
                                        }
                                    }
                                },
                            ) {
                                if (loadingOlder) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                else Text("Load older messages")
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                items(timelineMessages, key = { it.id }) { message ->
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
                                val visibleText = message.imageAttachments.fold(message.content) { content, attachment ->
                                    content.replace(attachment.url, "").trim()
                                }
                                val isTranslating = translation.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
                                    translation.status == TranslationStatus.DOWNLOADING_MODEL ||
                                    translation.status == TranslationStatus.TRANSLATING
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ProfilePicture(url = profile?.picture, size = 28)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            profile?.displayString ?: message.authorPubkey.take(12),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                        )
                                        Text(
                                            message.authorPubkey.take(12) + "…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    if (visibleText.isNotBlank()) {
                                        IconButton(
                                            enabled = !isTranslating && translation.status != TranslationStatus.SAME_LANGUAGE,
                                            modifier = Modifier.size(28.dp),
                                            onClick = {
                                                if (translation.status == TranslationStatus.DONE) {
                                                    showTranslated = !showTranslated
                                                } else {
                                                    translationRepository.translateWithDeepL(message.id, visibleText)
                                                }
                                            },
                                        ) {
                                            if (isTranslating) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(15.dp),
                                                    strokeWidth = 1.5.dp,
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Outlined.Translate,
                                                    contentDescription = when {
                                                        translation.status == TranslationStatus.ERROR -> "Retry DeepL translation"
                                                        translation.status == TranslationStatus.DONE && showTranslated -> "Show original message"
                                                        translation.status == TranslationStatus.DONE -> "Show translated message"
                                                        translation.status == TranslationStatus.SAME_LANGUAGE -> "Message already in this language"
                                                        else -> "Translate with DeepL"
                                                    },
                                                    tint = when {
                                                        translation.status == TranslationStatus.ERROR -> MaterialTheme.colorScheme.error
                                                        showTranslated -> MaterialTheme.colorScheme.primary
                                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    modifier = Modifier.size(17.dp),
                                                )
                                            }
                                        }
                                    }
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
                                if (translation.status == TranslationStatus.ERROR) {
                                    Text(
                                        translation.errorMessage,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (message.kind == 1111) {
                                    Text(
                                        "↳ Thread reply",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val reactions = messages.filter { it.kind == 7 && it.replyToId == message.id }
                                    .groupingBy { it.content }.eachCount()
                                val threadReplyCount = messages.count {
                                    it.kind == 1111 && (it.rootId == message.id || it.replyToId == message.id)
                                }
                                var reactionPickerExpanded by remember(message.id) { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(
                                        onClick = {
                                            replyToMessage = message
                                            inlineReplyMode = false
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Reply,
                                            contentDescription = "Reply",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { reactionPickerExpanded = true },
                                            enabled = selectedChannelCanReact && signer != null,
                                            modifier = Modifier.size(30.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.FavoriteBorder,
                                                contentDescription = "Choose reaction",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(17.dp),
                                            )
                                        }
                                        if (reactionPickerExpanded) {
                                            EmojiReactionPopup(
                                                onSelect = { emoji ->
                                                    scope.launch {
                                                        val ok = viewModel.sendChannelReaction(
                                                            communityId, selectedChannelId, message, emoji, signer,
                                                        )
                                                        if (!ok) sendError = "Reaction was not confirmed by a relay"
                                                    }
                                                },
                                                onDismiss = { reactionPickerExpanded = false },
                                                resolvedEmojis = resolvedEmojis,
                                                unicodeEmojis = unicodeEmojis,
                                                onOpenEmojiLibrary = onOpenEmojiLibrary,
                                            )
                                        }
                                    }
                                    if (reactions.isNotEmpty()) {
                                        Text(
                                            reactions.values.sum().toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            selectedThreadRoot = message
                                            showThreadDialog = true
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.ModeComment,
                                            contentDescription = "Open comments and thread",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                    if (threadReplyCount > 0) {
                                        Text(
                                            threadReplyCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            replyToMessage = message
                                            inlineReplyMode = true
                                        },
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.FormatQuote,
                                            contentDescription = "Quote",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = { onZapAuthor(message.authorPubkey) },
                                        enabled = signer != null && !ownMessage,
                                        modifier = Modifier.size(30.dp),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_bolt),
                                            contentDescription = "Zap author without exposing this group message",
                                            // A zap action being available is not proof that a zap was received.
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(17.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }
                    }
                }
            }
        }
    }
    if (showThreadDialog && selectedThreadRoot != null) {
        AlertDialog(
            onDismissRequest = { showThreadDialog = false },
            title = { Text("Thread") },
            text = {
                Column(modifier = Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            selectedThreadRoot!!.authorPubkey.take(16) + "…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(selectedThreadRoot!!.content, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${selectedThreadReplies.size} replies",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                replyToMessage = selectedThreadRoot
                                inlineReplyMode = false
                                showThreadDialog = false
                            },
                        ) { Text("Reply to thread") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (selectedThreadReplies.isEmpty()) {
                        Text(
                            "No replies yet. Start the thread.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(selectedThreadReplies, key = { it.id }) { reply ->
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        reply.authorPubkey.take(16) + "…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(reply.content, style = MaterialTheme.typography.bodyMedium)
                                    TextButton(
                                        onClick = {
                                            replyToMessage = reply
                                            inlineReplyMode = false
                                            showThreadDialog = false
                                        },
                                    ) { Text("Reply to this message") }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThreadDialog = false }) { Text("Close") }
            },
        )
    }

    if (showMembersDialog) {
        AlertDialog(
            onDismissRequest = { if (!membersState.isLoading) showMembersDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Community members", modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = signer != null && !membersState.isLoading,
                        onClick = {
                            viewModel.refreshRoles(communityId, signer)
                            viewModel.loadMembers(communityId, signer)
                        },
                    ) { Text(if (membersState.isLoading) "Refreshing…" else "Refresh") }
                }
            },
            text = {
                Column(modifier = Modifier.heightIn(max = 520.dp)) {
                    Text(
                        "${membersState.members.size} members · Concord Guestbook and verified channel activity",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedTextField(
                        value = memberSearchQuery,
                        onValueChange = { memberSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search members") },
                        placeholder = { Text("Name or npub / public key") },
                    )
                    if (memberRoleOptions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = memberRoleFilter.isBlank(),
                                onClick = { memberRoleFilter = "" },
                                label = { Text("All roles") },
                            )
                            memberRoleOptions.forEach { roleName ->
                                FilterChip(
                                    selected = memberRoleFilter == roleName,
                                    onClick = { memberRoleFilter = if (memberRoleFilter == roleName) "" else roleName },
                                    label = { Text(roleName) },
                                )
                            }
                        }
                    }
                    Text(
                        "Kick and Ban are rechecked against the full Control Plane before any write.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (membersState.isLoading && membersState.members.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp))
                            Text("Refreshing members…", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (membersState.error != null && membersState.members.isNotEmpty()) {
                        Text(
                            "Refresh failed: ${membersState.error}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    when {
                        membersState.isLoading && membersState.members.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Loading members…", modifier = Modifier.padding(start = 10.dp))
                        }
                        membersState.error != null && membersState.members.isEmpty() -> Text(
                            membersState.error!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                        membersState.members.isEmpty() -> Text("No Guestbook members were returned by the responding relays.")
                        filteredMembers.isEmpty() -> Text("No members match this search or role filter.")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(filteredMembers, key = { it.pubkey }) { member ->
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
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ProfilePicture(url = profile?.picture, size = 34)
                                        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
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
                                            Text(
                                                (if (member.isOwner) listOf("Owner") else emptyList())
                                                    .plus(member.roleNames)
                                                    .distinct()
                                                    .let { labels -> if (labels.isEmpty()) "No assigned roles" else labels.joinToString(" · ") },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    if (rolesState.canManageRoles && !member.isOwner) {
                                        Row(
                                            horizontalArrangement = Arrangement.End,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            TextButton(
                                                enabled = !rolesState.isLoading && !managementBusy,
                                                onClick = {
                                                    memberRolesTarget = member
                                                    memberRoleSelection = member.roleIds.toSet()
                                                    roleDialogError = null
                                                    showMemberRolesDialog = true
                                                    viewModel.refreshRoles(communityId, signer)
                                                },
                                            ) { Text("Manage roles") }
                                        }
                                    }
                                    if (member.canKick || member.canBan) {
                                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                            if (member.canKick) {
                                                TextButton(onClick = {
                                                    moderationTargetPubkey = member.pubkey
                                                    moderationAction = "kick"
                                                    moderationRequiresRefounding = member.requiresRefounding
                                                    showMembersDialog = false
                                                    showModerationDialog = true
                                                }) { Text("Kick") }
                                            }
                                            if (member.canBan) {
                                                TextButton(onClick = {
                                                    moderationTargetPubkey = member.pubkey
                                                    moderationAction = "ban"
                                                    moderationRequiresRefounding = true
                                                    showMembersDialog = false
                                                    showModerationDialog = true
                                                }) { Text("Ban") }
                                            }
                                        }
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

    if (showRolesDialog) {
        AlertDialog(
            onDismissRequest = { if (!rolesState.isLoading && !managementBusy) showRolesDialog = false },
            title = { Text("Community roles") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        rolesState.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Verifying the Control Plane relay quorum…", modifier = Modifier.padding(start = 10.dp))
                        }
                        rolesState.error != null -> Text(rolesState.error!!, color = MaterialTheme.colorScheme.error)
                        else -> {
                            Text(
                                if (rolesState.rolesV2) {
                                    "Concord Roles v2 · ranked authority. Changes are written as marked Control Plane editions."
                                } else {
                                    "Roles are ordered by authority. You can edit only roles below your rank and delegate only permissions you hold."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (rolesState.canManageRoles) {
                                TextButton(
                                    enabled = signer != null,
                                    onClick = {
                                        editingRoleId = null
                                        roleDraftName = ""
                                        roleDraftPosition = ((rolesState.roles.maxOfOrNull { it.position } ?: 0L) + 1L).toString()
                                        roleDraftPermissions = ConcordPermissions.NONE
                                        roleDialogError = null
                                        showRoleEditorDialog = true
                                    },
                                ) { Text("Create role") }
                            }
                            if (rolesState.roles.isEmpty()) {
                                Text("No roles are defined yet.", style = MaterialTheme.typography.bodySmall)
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(rolesState.roles, key = { it.id }) { role ->
                                    val permissionSummary = listOf(
                                        ConcordPermissions.MANAGE_ROLES,
                                        ConcordPermissions.MANAGE_CHANNELS,
                                        ConcordPermissions.MANAGE_METADATA,
                                        ConcordPermissions.KICK,
                                        ConcordPermissions.BAN,
                                        ConcordPermissions.MANAGE_MESSAGES,
                                        ConcordPermissions.CREATE_INVITE,
                                        ConcordPermissions.VIEW_AUDIT_LOG,
                                        ConcordPermissions.MENTION_EVERYONE,
                                        ConcordPermissions.PIN_MESSAGES,
                                    ).filter { role.permissions.has(it) }
                                        .map(::concordRolePermissionLabel)
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    role.name.ifBlank { role.id.take(10) },
                                                    modifier = Modifier.weight(1f),
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Text(
                                                    if (rolesState.rolesV2) "Roles v2" else "Legacy",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                )
                                            }
                                            Text(
                                                "Authority position ${role.position}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                if (permissionSummary.isEmpty()) {
                                                    "No explicit permissions"
                                                } else {
                                                    permissionSummary.joinToString(" · ")
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                                TextButton(
                                                    enabled = role.canEdit && signer != null,
                                                    onClick = {
                                                        editingRoleId = role.id
                                                        roleDraftName = role.name
                                                        roleDraftPosition = role.position.toString()
                                                        roleDraftPermissions = role.permissions
                                                        roleDialogError = null
                                                        showRoleEditorDialog = true
                                                    },
                                                ) { Text("Edit") }
                                                TextButton(
                                                    enabled = role.canEdit && signer != null && !managementBusy,
                                                    onClick = {
                                                        scope.launch {
                                                            managementBusy = true
                                                            roleDialogError = null
                                                            try {
                                                                viewModel.deleteRole(communityId, role.id, signer)
                                                            } catch (error: Exception) {
                                                                roleDialogError = error.message ?: "Role deletion failed"
                                                            } finally {
                                                                managementBusy = false
                                                            }
                                                        }
                                                    },
                                                ) { Text("Delete") }
                                            }
                                        }
                                    }
                                }
                            }
                            roleDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !rolesState.isLoading && !managementBusy,
                    onClick = { showRolesDialog = false },
                ) { Text("Close") }
            },
            dismissButton = {
                TextButton(
                    enabled = !rolesState.isLoading && !managementBusy,
                    onClick = { viewModel.refreshRoles(communityId, signer) },
                ) { Text("Refresh") }
            },
        )
    }

    if (showRoleEditorDialog) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showRoleEditorDialog = false },
            title = { Text(if (editingRoleId == null) "Create role" else "Edit role") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = roleDraftName,
                        onValueChange = { roleDraftName = it; roleDialogError = null },
                        label = { Text("Role name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = roleDraftPosition,
                        onValueChange = { value -> roleDraftPosition = value.filter(Char::isDigit); roleDialogError = null },
                        label = { Text("Authority position") },
                        supportingText = { Text("0 belongs to the owner; assignable roles start at 1.") },
                        singleLine = true,
                    )
                    Text("Delegable permissions", style = MaterialTheme.typography.titleSmall)
                    if (rolesState.delegablePermissionBits.isEmpty()) {
                        Text(
                            "This account holds no delegable role permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        rolesState.delegablePermissionBits.forEach { bit ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = roleDraftPermissions.has(bit),
                                    onCheckedChange = { checked ->
                                        roleDraftPermissions = if (checked) {
                                            roleDraftPermissions.with(bit)
                                        } else {
                                            roleDraftPermissions.without(bit)
                                        }
                                    },
                                )
                                Text(concordRolePermissionLabel(bit), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    roleDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null &&
                        roleDraftName.isNotBlank() && (roleDraftPosition.toLongOrNull() ?: 0L) > 0L,
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            roleDialogError = null
                            try {
                                val position = roleDraftPosition.toLong()
                                val id = editingRoleId
                                if (id == null) {
                                    viewModel.createRole(communityId, roleDraftName, position, roleDraftPermissions, signer)
                                } else {
                                    viewModel.updateRole(communityId, id, roleDraftName, position, roleDraftPermissions, signer)
                                }
                                showRoleEditorDialog = false
                            } catch (error: Exception) {
                                roleDialogError = error.message ?: "Role save failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Saving…" else "Save role") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showRoleEditorDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showMemberRolesDialog && memberRolesTarget != null) {
        val target = memberRolesTarget!!
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showMemberRolesDialog = false },
            title = { Text("Roles for ${target.pubkey.take(12)}…") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 450.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        rolesState.isLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Text("Loading roles…", modifier = Modifier.padding(start = 10.dp))
                        }
                        rolesState.error != null -> Text(rolesState.error!!, color = MaterialTheme.colorScheme.error)
                        else -> {
                            Text(
                                "Select the complete set of roles this member should hold. Changes are published as one Grant edition.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn {
                                items(rolesState.roles, key = { it.id }) { role ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = role.id in memberRoleSelection,
                                            enabled = role.canEdit,
                                            onCheckedChange = { checked ->
                                                memberRoleSelection = if (checked) memberRoleSelection + role.id
                                                else memberRoleSelection - role.id
                                            },
                                        )
                                        Column {
                                            Text(role.name.ifBlank { role.id.take(10) })
                                            Text(
                                                "Position ${role.position}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            val permissionSummary = listOf(
                                                ConcordPermissions.MANAGE_ROLES,
                                                ConcordPermissions.MANAGE_CHANNELS,
                                                ConcordPermissions.MANAGE_METADATA,
                                                ConcordPermissions.KICK,
                                                ConcordPermissions.BAN,
                                                ConcordPermissions.MANAGE_MESSAGES,
                                                ConcordPermissions.CREATE_INVITE,
                                                ConcordPermissions.VIEW_AUDIT_LOG,
                                                ConcordPermissions.MENTION_EVERYONE,
                                                ConcordPermissions.PIN_MESSAGES,
                                            ).filter { role.permissions.has(it) }
                                                .map(::concordRolePermissionLabel)
                                            Text(
                                                if (permissionSummary.isEmpty()) "No explicit permissions"
                                                else permissionSummary.joinToString(" · "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    roleDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && !rolesState.isLoading &&
                        rolesState.error == null && signer != null,
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            roleDialogError = null
                            try {
                                viewModel.setMemberRoles(communityId, target.pubkey, memberRoleSelection, signer)
                                showMemberRolesDialog = false
                            } catch (error: Exception) {
                                roleDialogError = error.message ?: "Role assignment failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Saving…" else "Save assignment") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showMemberRolesDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showEditMetadataDialog) {
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showEditMetadataDialog = false },
            title = { Text("Group details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = metadataDraftName,
                        onValueChange = { metadataDraftName = it; managementError = null },
                        label = { Text("Group name") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = metadataDraftDescription,
                        onValueChange = { metadataDraftDescription = it; managementError = null },
                        label = { Text("Description") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    OutlinedTextField(
                        value = metadataDraftRelays,
                        onValueChange = { metadataDraftRelays = it; managementError = null },
                        label = { Text("Community relays") },
                        minLines = 2,
                        maxLines = 5,
                    )
                    Text(
                        "Enter one secure wss:// relay per line. Existing encrypted icon and banner images are preserved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null &&
                        metadataDraftName.isNotBlank() &&
                        metadataDraftRelays.lines().any { it.trim().isNotBlank() },
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                val relays = metadataDraftRelays.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                viewModel.updateCommunityMetadata(
                                    communityId = communityId,
                                    name = metadataDraftName,
                                    description = metadataDraftDescription,
                                    relays = relays,
                                    signer = signer,
                                )
                                showEditMetadataDialog = false
                            } catch (error: Exception) {
                                managementError = error.message ?: "Community details could not be saved"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Saving…" else "Save details") }
            },
            dismissButton = {
                TextButton(
                    enabled = !managementBusy,
                    onClick = { showEditMetadataDialog = false },
                ) { Text("Cancel") }
            },
        )
    }

    if (showInviteLinksDialog) {
        AlertDialog(
            onDismissRequest = {
                if (inviteLinksState.revokingToken == null) showInviteLinksDialog = false
            },
            title = { Text("Public invite links") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "These links were created by this account. Revoking one publishes its Concord tombstone and removes it from the creator registry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (inviteLinksState.isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        }
                    }
                    inviteLinksState.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (!inviteLinksState.isLoading && inviteLinksState.links.isEmpty()) {
                        Text("No public invite links were found for this account.")
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(inviteLinksState.links, key = { it.token }) { link ->
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    link.label?.takeIf { it.isNotBlank() } ?: "Public invite",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    link.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val expired = link.isExpired(System.currentTimeMillis() / 1000)
                                Text(
                                    if (expired) "Expired" else "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            clipboard?.setPrimaryClip(
                                                android.content.ClipData.newPlainText("Concord invite link", link.url),
                                            )
                                            android.widget.Toast.makeText(context, "Invite link copied", android.widget.Toast.LENGTH_SHORT).show()
                                        },
                                    ) { Text("Copy") }
                                    TextButton(
                                        enabled = signer != null && inviteLinksState.revokingToken == null,
                                        onClick = { pendingInviteRevocationToken = link.token },
                                    ) {
                                        Text(if (inviteLinksState.revokingToken == link.token) "Revoking…" else "Revoke")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = inviteLinksState.revokingToken == null,
                    onClick = {
                        showInviteLinksDialog = false
                        viewModel.refreshInviteLinks(communityId, signer)
                    },
                ) { Text("Close") }
            },
        )
    }

    pendingInviteRevocationToken?.let { token ->
        AlertDialog(
            onDismissRequest = { if (inviteLinksState.revokingToken == null) pendingInviteRevocationToken = null },
            title = { Text("Revoke invite link?") },
            text = {
                Text("Anyone who has this link will no longer be able to use it after the revocation reaches the community relays.")
            },
            confirmButton = {
                TextButton(
                    enabled = signer != null && inviteLinksState.revokingToken == null,
                    onClick = {
                        scope.launch {
                            viewModel.revokeInviteLink(communityId, token, signer)
                            pendingInviteRevocationToken = null
                            showInviteLinksDialog = true
                        }
                    },
                ) { Text(if (inviteLinksState.revokingToken == token) "Revoking…" else "Revoke link") }
            },
            dismissButton = {
                TextButton(
                    enabled = inviteLinksState.revokingToken == null,
                    onClick = { pendingInviteRevocationToken = null },
                ) { Text("Cancel") }
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
            title = { Text(if (newChannelPrivate) "Create private channel" else "Create public channel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newChannelName,
                        onValueChange = { newChannelName = it; managementError = null },
                        label = { Text("Channel name") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = newChannelPrivate,
                            onCheckedChange = { newChannelPrivate = it; managementError = null },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Private channel", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Only members holding its access role receive the channel key. Creating one also requires role-management authority.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        if (newChannelPrivate) {
                            "Postilla stores and confirms a separate private key before publishing the private channel and its access role."
                        } else {
                            "This creates a public CORD-03 channel visible to community members."
                        },
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
                                if (newChannelPrivate) {
                                    viewModel.createPrivateChannel(communityId, newChannelName, signer)
                                } else {
                                    viewModel.createPublicChannel(communityId, newChannelName, signer)
                                }
                                newChannelName = ""
                                newChannelPrivate = false
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
    if (showModerationDialog && moderationTargetPubkey != null) {
        val isBan = moderationAction == "ban"
        AlertDialog(
            onDismissRequest = { if (!managementBusy) showModerationDialog = false },
            title = { Text(if (isBan) "Ban member?" else "Kick member?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(moderationTargetPubkey!!)
                    Text(
                        if (moderationRequiresRefounding) {
                            "This operation refounds the community and rotates its root keys. It is irreversible for the removed member and requires the remaining members to refresh."
                        } else {
                            "The member will be removed from the public community Guestbook."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    managementError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !managementBusy && signer != null,
                    onClick = {
                        scope.launch {
                            managementBusy = true
                            managementError = null
                            try {
                                if (isBan) {
                                    viewModel.banMember(communityId, moderationTargetPubkey!!, signer)
                                } else {
                                    viewModel.kickMember(communityId, moderationTargetPubkey!!, signer)
                                }
                                showModerationDialog = false
                                moderationTargetPubkey = null
                                moderationAction = ""
                            } catch (error: Exception) {
                                managementError = error.message ?: "Member moderation failed"
                            } finally {
                                managementBusy = false
                            }
                        }
                    },
                ) { Text(if (managementBusy) "Applying…" else if (isBan) "Ban permanently" else "Kick member") }
            },
            dismissButton = {
                TextButton(enabled = !managementBusy, onClick = { showModerationDialog = false }) { Text("Cancel") }
            },
        )
    }
    }
}
}


private fun concordRolePermissionLabel(bit: Int): String = when (bit) {
    ConcordPermissions.MANAGE_ROLES -> "Manage roles"
    ConcordPermissions.MANAGE_CHANNELS -> "Manage channels"
    ConcordPermissions.MANAGE_METADATA -> "Manage community details"
    ConcordPermissions.KICK -> "Kick members"
    ConcordPermissions.BAN -> "Ban members"
    ConcordPermissions.MANAGE_MESSAGES -> "Manage messages"
    ConcordPermissions.CREATE_INVITE -> "Create invite links"
    ConcordPermissions.VIEW_AUDIT_LOG -> "View audit log"
    ConcordPermissions.MENTION_EVERYONE -> "Mention everyone"
    ConcordPermissions.PIN_MESSAGES -> "Pin messages"
    else -> "Permission $bit"
}
