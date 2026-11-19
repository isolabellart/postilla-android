package com.darkwisp.app.ui.screen

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.repo.ConcordChannelSummary
import com.darkwisp.app.repo.ConcordCommunitySummary
import com.darkwisp.app.viewmodel.ConcordCommunitiesViewModel

@Composable
fun ConcordCommunitiesScreen(
    viewModel: ConcordCommunitiesViewModel,
    signer: NostrSigner?,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showJoinDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCommunity by remember { mutableStateOf<ConcordCommunitySummary?>(null) }

    LaunchedEffect(signer?.pubkeyHex) {
        viewModel.refresh(signer)
    }

    BackHandler(enabled = state.selectedChannel != null) {
        viewModel.closeChannel()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = if (state.selectedChannel != null) viewModel::closeChannel else onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Indietro")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.selectedChannel == null) "Community" else state.selectedChannel?.name.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.selectedChannel == null) "Spazi Concord a cui partecipi" else "Canale Concord cifrato",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.selectedChannel == null) {
                IconButton(onClick = { viewModel.refresh(signer) }, enabled = !state.loading && !state.working) {
                    Icon(Icons.Outlined.Groups, contentDescription = "Aggiorna Community")
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

        if (state.selectedChannel != null) {
            ConcordChannelPane(
                messages = state.messages,
                isLoading = state.working,
                onSend = { viewModel.sendMessage(signer, it) },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showJoinDialog = true },
                            modifier = Modifier.weight(1f),
                            enabled = signer != null && !state.working,
                        ) {
                            Icon(Icons.Outlined.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Unisciti")
                        }
                        OutlinedButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.weight(1f),
                            enabled = signer != null && !state.working,
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Crea")
                        }
                    }
                }

                item {
                    SectionHeading(
                        title = "Le tue Community",
                        subtitle = "I gruppi Concord collegati al tuo account",
                    )
                }

                if (state.loading) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.communities.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Qui compariranno le Community a cui ti unisci.", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "La prima apertura prova automaticamente ad aggiungere la Community pubblica ufficiale di Postilla.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(state.communities, key = { it.id }) { community ->
                        ConcordCommunityCard(
                            community = community,
                            onEdit = { editingCommunity = community },
                            onOpenChannel = { viewModel.openChannel(community.id, it) },
                        )
                    }
                }

                item {
                    SectionHeading(
                        title = "Community suggerite",
                        subtitle = "Concord usa inviti: non esiste un catalogo pubblico universale.",
                    )
                }
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Community pubblica Postilla", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Ingresso tramite l'invito ufficiale configurato nell'app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { viewModel.refresh(signer) },
                                enabled = signer != null && !state.working,
                            ) { Text("Entra") }
                        }
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { showJoinDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = signer != null && !state.working,
                    ) {
                        Icon(Icons.Outlined.VpnKey, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Unisciti con un link d'invito")
                    }
                }

                state.info?.let { message ->
                    item { StatusMessage(message, isError = false) }
                }
                state.error?.let { message ->
                    item { StatusMessage(message, isError = true) }
                }
                state.inviteToShare?.let { invite ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Invito della nuova Community", fontWeight = FontWeight.SemiBold)
                                Text(invite, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(
                                    onClick = { clipboard.setText(AnnotatedString(invite)) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Copia invito")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showJoinDialog) {
        JoinConcordDialog(
            working = state.working,
            onDismiss = { showJoinDialog = false },
            onJoin = {
                showJoinDialog = false
                viewModel.joinInvite(it, signer)
            },
        )
    }

    if (showCreateDialog) {
        CreateConcordDialog(
            working = state.working,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, about, relays ->
                showCreateDialog = false
                viewModel.createCommunity(signer, name, about, relays)
            },
        )
    }

    editingCommunity?.let { community ->
        EditConcordDialog(
            community = community,
            working = state.working,
            context = context,
            onDismiss = { editingCommunity = null },
            onSave = { name, about, relays, timer, icon, iconMime, banner, bannerMime ->
                editingCommunity = null
                viewModel.updateMetadata(
                    signer = signer,
                    communityId = community.id,
                    name = name,
                    description = about,
                    relays = relays,
                    timerSeconds = timer,
                    iconBytes = icon,
                    iconMimeType = iconMime,
                    bannerBytes = banner,
                    bannerMimeType = bannerMime,
                )
            },
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConcordCommunityCard(
    community: ConcordCommunitySummary,
    onEdit: () -> Unit,
    onOpenChannel: (ConcordChannelSummary) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(community.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        community.description?.takeIf { it.isNotBlank() } ?: "Community Concord",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (community.canManageMetadata) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Modifica Community")
                    }
                }
            }
            if (community.channels.isNotEmpty()) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
                Text("Canali", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                community.channels.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = channel.canRead) { onOpenChannel(channel) }
                            .padding(vertical = 9.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (channel.canRead) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(channel.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (!channel.canRead) {
                            Text("Chiave non disponibile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Text(
                    "I metadati e i canali non sono ancora raggiungibili dai relay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConcordChannelPane(
    messages: List<com.darkwisp.app.repo.ConcordChatMessage>,
    isLoading: Boolean,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            message.author.take(12) + "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (messages.isEmpty() && !isLoading) {
                item {
                    Text(
                        "Nessun messaggio disponibile in questo canale.",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Scrivi un messaggio") },
                maxLines = 4,
            )
            IconButton(
                onClick = {
                    val text = draft
                    if (text.isNotBlank()) {
                        draft = ""
                        onSend(text)
                    }
                },
                enabled = draft.isNotBlank() && !isLoading,
            ) {
                Icon(Icons.Outlined.Send, contentDescription = "Invia messaggio")
            }
        }
    }
}

@Composable
private fun JoinConcordDialog(
    working: Boolean,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var invite by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unisciti a una Community") },
        text = {
            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Link d'invito Concord") },
                placeholder = { Text("https://…/invite/naddr1…#…") },
                maxLines = 4,
            )
        },
        confirmButton = {
            Button(onClick = { onJoin(invite.trim()) }, enabled = invite.isNotBlank() && !working) {
                Text("Unisciti")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun CreateConcordDialog(
    working: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var relays by remember { mutableStateOf("wss://relay.damus.io\nwss://nos.lol") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crea una Community Concord") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(about, { about = it }, label = { Text("About") }, minLines = 2)
                OutlinedTextField(
                    relays,
                    { relays = it },
                    label = { Text("Relay (uno per riga)") },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim(), about.trim(), relays.lines().map { it.trim() }.filter { it.isNotBlank() }) },
                enabled = name.isNotBlank() && !working,
            ) { Text(if (working) "Creazione…" else "Crea") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun EditConcordDialog(
    community: ConcordCommunitySummary,
    working: Boolean,
    context: Context,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>, Long?, ByteArray?, String, ByteArray?, String) -> Unit,
) {
    var name by remember(community.id) { mutableStateOf(community.name) }
    var about by remember(community.id) { mutableStateOf(community.description.orEmpty()) }
    var relays by remember(community.id) { mutableStateOf(community.relays.joinToString("\n")) }
    var timer by remember(community.id) { mutableStateOf(community.messageExpirationSecs?.toString().orEmpty()) }
    var iconBytes by remember(community.id) { mutableStateOf<ByteArray?>(null) }
    var iconMime by remember(community.id) { mutableStateOf("image/jpeg") }
    var bannerBytes by remember(community.id) { mutableStateOf<ByteArray?>(null) }
    var bannerMime by remember(community.id) { mutableStateOf("image/jpeg") }
    var pickTarget by remember { mutableStateOf("icon") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Impossibile leggere il file selezionato.")
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                if (pickTarget == "icon") {
                    iconBytes = bytes
                    iconMime = mime
                } else {
                    bannerBytes = bytes
                    bannerMime = mime
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica " + community.name) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(about, { about = it }, modifier = Modifier.fillMaxWidth(), label = { Text("About") }, minLines = 2)
                OutlinedTextField(relays, { relays = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Relay (uno per riga)") }, minLines = 2)
                OutlinedTextField(
                    timer,
                    { timer = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timer messaggi (secondi, vuoto = disattivato)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedButton(
                    onClick = {
                        pickTarget = "icon"
                        imagePicker.launch("image/*")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (iconBytes == null) "Scegli icona" else "Icona selezionata")
                }
                OutlinedButton(
                    onClick = {
                        pickTarget = "banner"
                        imagePicker.launch("image/*")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (bannerBytes == null) "Scegli banner" else "Banner selezionato")
                }
                Text(
                    "Le immagini vengono cifrate prima del caricamento Blossom. La modifica viene pubblicata solo se il tuo ruolo possiede MANAGE_METADATA.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val timerValue = timer.toLongOrNull()?.takeIf { it > 0 }
                    onSave(
                        name.trim(),
                        about.trim(),
                        relays.lines().map { it.trim() }.filter { it.isNotBlank() },
                        timerValue,
                        iconBytes,
                        iconMime,
                        bannerBytes,
                        bannerMime,
                    )
                },
                enabled = name.isNotBlank() && !working,
            ) { Text(if (working) "Salvataggio…" else "Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun StatusMessage(message: String, isError: Boolean) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
