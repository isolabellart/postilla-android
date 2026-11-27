package com.darkwisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.darkwisp.app.viewmodel.ConcordCommunityViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConcordRoomScreen(
    communityId: String,
    viewModel: ConcordCommunityViewModel,
    signer: NostrSigner?,
    onBack: () -> Unit,
) {
    val communities by viewModel.communities.collectAsState()
    val community = communities.firstOrNull { it.id == communityId }
    val restoringCommunityId by viewModel.restoringGeneralChannel.collectAsState()
    val generalChannelError by viewModel.generalChannelError.collectAsState()
    val messages = remember(communityId) { mutableStateListOf<ConcordChatMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(communityId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(communityId, community?.generalChannelId) {
        messages.clear()
        viewModel.observeGeneralChannel(communityId).collect { message ->
            if (messages.none { it.id == message.id }) {
                messages.add(message)
                messages.sortBy { it.createdAt }
            }
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
                    Column {
                        Text(community?.name ?: communityId.take(12), style = MaterialTheme.typography.titleMedium)
                        Text("general · Concord", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        placeholder = { Text("Message #general") },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (text.isNotBlank() && !sending && signer != null) {
                                    val outgoing = text
                                    scope.launch {
                                        sending = true
                                        val sent = runCatching {
                                            viewModel.sendGeneralMessage(communityId, outgoing, signer)
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
                        enabled = text.isNotBlank() && !sending && signer != null,
                        onClick = {
                            val outgoing = text
                            scope.launch {
                                sending = true
                                val sent = runCatching {
                                    viewModel.sendGeneralMessage(communityId, outgoing, signer)
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
        } else if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No messages yet. Start the conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    val ownMessage = message.authorPubkey == signer?.pubkeyHex
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
                                if (!ownMessage) {
                                    Text(
                                        message.authorPubkey.take(12),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
