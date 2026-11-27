package com.darkwisp.app.ui.screen

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.darkwisp.app.concord.ConcordCommunityViewModel
import com.darkwisp.app.nostr.Blossom
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
    val community = communities.firstOrNull { it.id == communityId }
    val restoringCommunityId by viewModel.restoringGeneralChannel.collectAsState()
    val generalChannelError by viewModel.generalChannelError.collectAsState()
    val messages = remember(communityId) { mutableStateListOf<ConcordChatMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var text by rememberSaveable(communityId) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
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
                    val extension = when (mimeType.substringAfter('/').lowercase()) {
                        "jpeg" -> "jpg"
                        "png", "webp", "gif" -> mimeType.substringAfter('/').lowercase()
                        else -> "jpg"
                    }
                    val url = blossomRepository.uploadMedia(bytes, mimeType, extension, signer)
                    val hash = Blossom.sha256Hex(bytes)
                    val caption = text.trim()
                    val outgoing = listOf(caption, url).filter { it.isNotBlank() }.joinToString("\\n")
                    val tags = arrayOf(arrayOf("imeta", "url $url", "m $mimeType", "x $hash"))
                    val sent = viewModel.sendGeneralMessage(communityId, outgoing, signer, tags)
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
                                if (text.isNotBlank() && !sending && signer != null && community?.generalChannelId != null) {
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
                        enabled = !sending && signer != null && community?.generalChannelId != null,
                        onClick = { imagePicker.launch("image/*") },
                    ) {
                        Icon(Icons.Default.Image, contentDescription = "Attach image")
                    }
                    IconButton(
                        enabled = text.isNotBlank() && !sending && signer != null && community?.generalChannelId != null,
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
                    var profile by remember(message.authorPubkey) {
                        mutableStateOf(eventRepository.getProfileData(message.authorPubkey))
                    }
                    var showTranslated by remember(message.id) { mutableStateOf(false) }
                    val translation by translationRepository.stateFor(message.id)
                        .collectAsState(initial = TranslationState())
                    val imageUrl = remember(message.content) {
                        Regex("https?://[^\\\\s]+", RegexOption.IGNORE_CASE).findAll(message.content)
                            .map { it.value.trimEnd(')', ']', '}', ',', '.') }
                            .firstOrNull()
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
                                val visibleText = if (imageUrl != null) message.content.replace(imageUrl, "").trim() else message.content
                                if (visibleText.isNotBlank()) {
                                    Text(visibleText, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (imageUrl != null) {
                                    AsyncImage(
                                        model = imageUrl,
                                        contentDescription = "Concord image",
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        if (translation.status == TranslationStatus.DONE) {
                                            showTranslated = !showTranslated
                                        } else {
                                            translationRepository.translateWithDeepL(message.id, message.content)
                                        }
                                    },
                                ) {
                                    Text(
                                        when {
                                            translation.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
                                                translation.status == TranslationStatus.DOWNLOADING_MODEL ||
                                                translation.status == TranslationStatus.TRANSLATING -> "Translating…"
                                            translation.status == TranslationStatus.ERROR -> "Retry translation"
                                            showTranslated -> "Show original"
                                            translation.status == TranslationStatus.DONE -> "Show translation"
                                            else -> "Translate with DeepL"
                                        }
                                    )
                                }
                                if (showTranslated && translation.status == TranslationStatus.DONE) {
                                    Text(translation.translatedText, style = MaterialTheme.typography.bodyMedium)
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
}
