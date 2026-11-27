package com.darkwisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R
import com.darkwisp.app.nostr.DmConversation
import com.darkwisp.app.nostr.DmProtocol
import com.darkwisp.app.repo.InterfacePreferences
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.GroupRoom
import com.darkwisp.app.ui.component.GroupCard
import com.darkwisp.app.ui.component.ProfilePicture
import com.darkwisp.app.viewmodel.DmListViewModel
import com.darkwisp.app.ui.theme.wispSwitchColors
import com.darkwisp.app.viewmodel.GroupListViewModel
import com.darkwisp.app.viewmodel.ConcordCommunityViewModel
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.relay.RelayPool
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmListScreen(
    viewModel: DmListViewModel,
    groupListViewModel: GroupListViewModel,
    concordCommunityViewModel: ConcordCommunityViewModel,
    relayPool: RelayPool,
    eventRepo: EventRepository,
    blossomRepository: BlossomRepository,
    userPubkey: String? = null,
    signer: NostrSigner? = null,
    onBack: (() -> Unit)? = null,
    onConversation: (DmConversation) -> Unit,
    onNewGroupDm: () -> Unit = {},
    onGroupRoom: (relayUrl: String, groupId: String) -> Unit = { _, _ -> },
    onConcordCommunity: (communityId: String) -> Unit = {},
    legacyDmEnabled: Boolean = true,
    onNewLegacyDm: () -> Unit = {}
) {
    val conversations by viewModel.conversationList.collectAsState()

    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val hiddenPrefs = remember(context) { context.getSharedPreferences("wisp_settings", android.content.Context.MODE_PRIVATE) }
    val hiddenPrefKey = "hidden_dm_conversations_${userPubkey ?: "default"}"
    var hiddenConversationKeys by remember(userPubkey) { mutableStateOf(emptySet<String>()) }
    var showHiddenChats by rememberSaveable { mutableStateOf(false) }
    var selectedConversationKeys by remember { mutableStateOf(emptySet<String>()) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showChatMenu by remember { mutableStateOf(false) }
    LaunchedEffect(userPubkey) {
        hiddenConversationKeys = hiddenPrefs.getStringSet(hiddenPrefKey, emptySet())?.toSet() ?: emptySet()
        selectedConversationKeys = emptySet()
        showHiddenChats = false
    }
    fun persistHidden(next: Set<String>) {
        hiddenConversationKeys = next
        hiddenPrefs.edit().putStringSet(hiddenPrefKey, next).apply()
        selectedConversationKeys = emptySet()
    }

    var selectedTab by rememberSaveable { mutableStateOf("private") }
    LaunchedEffect(legacyDmEnabled) {
        if (!legacyDmEnabled && selectedTab == "classic") selectedTab = "private"
    }
    // Concord membership is discovered from the user's encrypted community list;
    // do not create or restore a parallel NIP-29 default room.
    LaunchedEffect(userPubkey, signer) {
        concordCommunityViewModel.init(relayPool, signer)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searchVisible) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.dm_search_hint)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else Text(stringResource(R.string.title_chat))
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                        }
                    }
                },
                actions = {
                    if (selectedConversationKeys.isNotEmpty()) {
                        TextButton(onClick = {
                            val next = if (showHiddenChats) hiddenConversationKeys - selectedConversationKeys
                                else hiddenConversationKeys + selectedConversationKeys
                            persistHidden(next)
                        }) {
                            Text(stringResource(if (showHiddenChats) R.string.dm_restore_selected else R.string.dm_hide_selected))
                        }
                    }
                    IconButton(onClick = {
                        searchVisible = !searchVisible
                        if (!searchVisible) searchQuery = ""
                    }) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.dm_search_hint))
                    }
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.dm_chat_menu))
                        }
                        DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.dm_mark_all_read)) },
                                onClick = { showChatMenu = false; viewModel.markDmsRead() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (showHiddenChats) R.string.dm_hide_hidden else R.string.dm_show_hidden)) },
                                onClick = {
                                    showChatMenu = false
                                    showHiddenChats = !showHiddenChats
                                    selectedConversationKeys = emptySet()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            when (selectedTab) {
                "private" -> FloatingActionButton(
                    onClick = onNewGroupDm,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Outlined.GroupAdd, contentDescription = stringResource(R.string.cd_new_group_dm))
                }
                "classic" -> FloatingActionButton(
                    onClick = onNewLegacyDm,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Outlined.MailOutline, contentDescription = stringResource(R.string.action_new_legacy_dm))
                }
                // Concord creation/join/invites are managed inside its own workspace.
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .pointerInput(selectedTab, legacyDmEnabled) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, amount -> drag += amount },
                        onDragEnd = {
                            val tabs = if (legacyDmEnabled) listOf("private", "classic", "concord") else listOf("private", "concord")
                            val index = tabs.indexOf(selectedTab).coerceAtLeast(0)
                            if (drag < -70f && index < tabs.lastIndex) selectedTab = tabs[index + 1]
                            if (drag > 70f && index > 0) selectedTab = tabs[index - 1]
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    )
                }
        ) {
            val selectedTabIndex = when (selectedTab) {
                "private" -> 0
                "classic" -> 1
                else -> if (legacyDmEnabled) 2 else 1
            }
            TabRow(selectedTabIndex = selectedTabIndex) {
                Tab(
                    selected = selectedTab == "private",
                    onClick = { selectedTab = "private" },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(17.dp))
                            Text(stringResource(R.string.tab_dm_private), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
                if (legacyDmEnabled) {
                    Tab(
                        selected = selectedTab == "classic",
                        onClick = { selectedTab = "classic" },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Outlined.MailOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                                Text(stringResource(R.string.tab_dm_classic), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    )
                }
                Tab(
                    selected = selectedTab == "concord",
                    onClick = { selectedTab = "concord" },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Outlined.Groups, contentDescription = null, modifier = Modifier.size(17.dp))
                            Text(stringResource(R.string.tab_concord_communities), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                )
            }

            when (selectedTab) {
                "private" -> DmListContent(
                    conversations.filter { it.protocol == DmProtocol.NIP17 }, eventRepo, onConversation,
                    hiddenConversationKeys, showHiddenChats, searchQuery, selectedConversationKeys,
                    onToggleSelection = { key ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedConversationKeys = if (key in selectedConversationKeys) selectedConversationKeys - key else selectedConversationKeys + key
                    }
                )
                "classic" -> DmListContent(
                    conversations.filter { it.protocol == DmProtocol.NIP04 }, eventRepo, onConversation,
                    hiddenConversationKeys, showHiddenChats, searchQuery, selectedConversationKeys,
                    onToggleSelection = { key ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedConversationKeys = if (key in selectedConversationKeys) selectedConversationKeys - key else selectedConversationKeys + key
                    }
                )
                "concord" -> ConcordCommunitiesScreen(
                    viewModel = concordCommunityViewModel,
                    relayPool = relayPool,
                    signer = signer,
                    blossomRepository = blossomRepository,
                    onOpenCommunity = onConcordCommunity,
                    embedded = true,
                )
            }
        }
    }


}

@Composable
private fun DmListContent(
    conversations: List<DmConversation>,
    eventRepo: EventRepository,
    onConversation: (DmConversation) -> Unit,
    hiddenKeys: Set<String>,
    showHidden: Boolean,
    searchQuery: String,
    selectedKeys: Set<String>,
    onToggleSelection: (String) -> Unit
) {
    val visibleConversations = conversations.filter { convo ->
        val key = "${convo.protocol.key}:${convo.conversationKey}"
        (if (showHidden) key in hiddenKeys else key !in hiddenKeys) &&
            (searchQuery.isBlank() ||
                convo.conversationKey.contains(searchQuery, ignoreCase = true) ||
                convo.participants.any { pk ->
                    pk.contains(searchQuery, ignoreCase = true) ||
                        (eventRepo.getProfileData(pk)?.displayString?.contains(searchQuery, ignoreCase = true) == true)
                } ||
                convo.messages.lastOrNull()?.content?.contains(searchQuery, ignoreCase = true) == true)
    }
    if (visibleConversations.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                stringResource(R.string.error_no_messages),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.error_send_message_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = visibleConversations, key = { "${it.protocol.key}:${it.conversationKey}" }, contentType = { "conversation" }) { convo ->
                val key = "${convo.protocol.key}:${convo.conversationKey}"
                ConversationRow(
                    convo = convo,
                    eventRepo = eventRepo,
                    selected = key in selectedKeys,
                    onLongClick = { onToggleSelection(key) },
                    onClick = { if (selectedKeys.isNotEmpty()) onToggleSelection(key) else onConversation(convo) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupListContent(
    groups: List<GroupRoom>,
    eventRepo: EventRepository,
    onGroupRoom: (relayUrl: String, groupId: String) -> Unit,
    unreadGroups: Set<String> = emptySet(),
    notifiedGroups: Set<String> = emptySet(),
    onToggleNotify: (relayUrl: String, groupId: String, enabled: Boolean) -> Unit = { _, _, _ -> }
) {
    if (groups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                stringResource(R.string.error_no_chat_rooms),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.error_join_room_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = groups, key = { "${it.relayUrl}|${it.groupId}" }) { room ->
                val key = "${room.relayUrl}|${room.groupId}"
                GroupRoomRow(
                    room = room,
                    hasUnread = key in unreadGroups,
                    isNotified = key in notifiedGroups,
                    onToggleNotify = { enabled -> onToggleNotify(room.relayUrl, room.groupId, enabled) },
                    onClick = { onGroupRoom(room.relayUrl, room.groupId) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupRoomRow(
    room: GroupRoom,
    hasUnread: Boolean = false,
    isNotified: Boolean = false,
    onToggleNotify: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val lastMsg = room.messages.lastOrNull()
    val displayName = room.metadata?.name ?: room.groupId.ifEmpty { room.relayUrl }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        IconButton(
            onClick = { onToggleNotify(!isNotified) },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                if (isNotified) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                contentDescription = stringResource(
                    if (isNotified) R.string.cd_unmute_notifications else R.string.cd_mute_notifications
                ),
                tint = if (isNotified) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(4.dp))
        Box {
            ProfilePicture(url = room.metadata?.picture, size = 40)
            if (hasUnread) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = lastMsg?.content ?: room.metadata?.about ?: room.relayUrl
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (room.lastMessageAt > 0L) {
            Text(
                text = formatTimestamp(room.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoin: (relayUrl: String, groupId: String, inviteCode: String?) -> Unit
) {
    var inviteLink by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_join_chat_room)) },
        text = {
            Column {
                OutlinedTextField(
                    value = inviteLink,
                    onValueChange = { inviteLink = it; showError = false },
                    label = { Text(stringResource(R.string.label_invite_link)) },
                    placeholder = { Text("chat.wisp.talk'roomid") },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.error_invalid_invite_link)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsed = com.darkwisp.app.nostr.Nip29.parseInviteLink(inviteLink.trim())
                    if (parsed != null) {
                        onJoin(parsed.first, parsed.second, parsed.third)
                    } else {
                        showError = true
                    }
                }
            ) {
                Text(stringResource(R.string.action_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (
        relayUrl: String,
        name: String,
        isPrivate: Boolean,
        isClosed: Boolean,
        isRestricted: Boolean,
        isHidden: Boolean
    ) -> Unit
) {
    var groupName by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(com.darkwisp.app.nostr.Nip29.DEFAULT_GROUP_RELAYS.first()) }
    var isPrivate by remember { mutableStateOf(false) }
    var isClosed by remember { mutableStateOf(false) }
    var isRestricted by remember { mutableStateOf(false) }
    var isHidden by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_create_group)) },
        text = {
            Column {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.label_group_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text(stringResource(R.string.label_relay_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                GroupFlagSwitch(
                    label = stringResource(R.string.group_flag_private_label),
                    description = stringResource(R.string.group_flag_private_desc),
                    checked = isPrivate,
                    onCheckedChange = { isPrivate = it }
                )
                GroupFlagSwitch(
                    label = stringResource(R.string.group_flag_closed_label),
                    description = stringResource(R.string.group_flag_closed_desc),
                    checked = isClosed,
                    onCheckedChange = { isClosed = it }
                )
                GroupFlagSwitch(
                    label = stringResource(R.string.group_flag_restricted_label),
                    description = stringResource(R.string.group_flag_restricted_desc),
                    checked = isRestricted,
                    onCheckedChange = { isRestricted = it }
                )
                GroupFlagSwitch(
                    label = stringResource(R.string.group_flag_hidden_label),
                    description = stringResource(R.string.group_flag_hidden_desc),
                    checked = isHidden,
                    onCheckedChange = { isHidden = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val url = relayUrl.trim()
                    if (url.isNotEmpty()) onCreate(
                        url, groupName.trim(),
                        isPrivate, isClosed, isRestricted, isHidden
                    )
                }
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

@Composable
private fun GroupFlagSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = wispSwitchColors())
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ConversationRow(
    convo: DmConversation,
    eventRepo: EventRepository,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit
) {
    val lastMsg = convo.messages.lastOrNull()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (convo.isGroup) {
            GroupAvatarCluster(participants = convo.participants, eventRepo = eventRepo)
        } else {
            val profile = remember(convo.peerPubkey) { eventRepo.getProfileData(convo.peerPubkey) }
            ProfilePicture(url = profile?.picture)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val displayName = if (convo.isGroup) {
                convo.participants.take(3).joinToString(", ") { pk ->
                    eventRepo.getProfileData(pk)?.displayString ?: pk.take(8) + "…"
                }.let { if (convo.participants.size > 3) "$it +${convo.participants.size - 3}" else it }
            } else {
                eventRepo.getProfileData(convo.peerPubkey)?.displayString
                    ?: convo.peerPubkey.take(8) + "..." + convo.peerPubkey.takeLast(4)
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            lastMsg?.let {
                Text(
                    text = it.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = formatTimestamp(convo.lastMessageAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GroupAvatarCluster(participants: List<String>, eventRepo: EventRepository) {
    Box(modifier = Modifier.size(48.dp)) {
        participants.take(3).forEachIndexed { i, pubkey ->
            val profile = remember(pubkey) { eventRepo.getProfileData(pubkey) }
            ProfilePicture(
                url = profile?.picture,
                size = 28,
                modifier = Modifier
                    .align(
                        when (i) {
                            0 -> Alignment.TopStart
                            1 -> Alignment.TopEnd
                            else -> Alignment.BottomCenter
                        }
                    )
                    .offset(
                        x = when (i) { 1 -> 4.dp; 2 -> 2.dp; else -> 0.dp },
                        y = when (i) { 2 -> 4.dp; else -> 0.dp }
                    )
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("MMM d", Locale.US)
private val timeYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

private fun formatTimestamp(epoch: Long): String {
    if (epoch == 0L) return ""
    val date = Date(epoch * 1000)
    val cal = java.util.Calendar.getInstance()
    val currentYear = cal.get(java.util.Calendar.YEAR)
    cal.time = date
    return if (cal.get(java.util.Calendar.YEAR) != currentYear) {
        timeYearFormat.format(date)
    } else {
        timeFormat.format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverGroupsSheet(
    groupListViewModel: GroupListViewModel,
    eventRepo: EventRepository,
    onDismiss: () -> Unit,
    onJoin: (relayUrl: String, groupId: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val discoveredGroups by groupListViewModel.discoveredGroups.collectAsState()
    val isLoading by groupListViewModel.discoveryLoading.collectAsState()

    LaunchedEffect(Unit) {
        groupListViewModel.discoverGroups()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.title_discover_groups),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            if (isLoading && discoveredGroups.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp)
                ) {
                    CircularProgressIndicator()
                }
            } else if (discoveredGroups.isEmpty() && !isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.profile_no_groups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(
                        items = discoveredGroups,
                        key = { "${it.relayUrl}|${it.metadata.groupId}" }
                    ) { group ->
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            GroupCard(
                                relayUrl = group.relayUrl,
                                groupId = group.metadata.groupId,
                                initialMetadata = group.metadata,
                                initialMembers = group.members,
                                onClick = { onJoin(group.relayUrl, group.metadata.groupId) },
                                eventRepo = eventRepo
                            )
                        }
                    }
                    if (isLoading) {
                        item {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
