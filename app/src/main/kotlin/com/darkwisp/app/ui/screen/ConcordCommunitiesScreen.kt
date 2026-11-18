package com.darkwisp.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.darkwisp.app.viewmodel.ConcordCommunitiesViewModel

@Composable
fun ConcordCommunitiesScreen(
    viewModel: ConcordCommunitiesViewModel,
    onLegacyRooms: () -> Unit,
) {
    val communities by viewModel.communities.collectAsState()
    val activeCommunityId by viewModel.activeCommunityId.collectAsState()
    val activeChannelId by viewModel.activeChannelId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val status by viewModel.status.collectAsState()
    val error by viewModel.error.collectAsState()
    val inviteLink by viewModel.inviteLink.collectAsState()
    val activeCommunity = communities.firstOrNull { it.id == activeCommunityId }
    val activeChannel = activeCommunity?.channels?.firstOrNull { it.id == activeChannelId }

    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var showBrowse by rememberSaveable { mutableStateOf(false) }
    var communityName by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var inviteInput by rememberSaveable { mutableStateOf("") }
    var messageInput by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            activeChannel != null && activeCommunity != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.openCommunity(activeCommunity.id) }) {
                        Text("‹ Community")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("#" + activeChannel.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (activeCommunity.isOwner) {
                    Text("Community amministrata dal tuo account", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                if (messages.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Nessun messaggio ancora. La conversazione è cifrata tra i membri del canale.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
                            ) {
                                Text(
                                    if (message.isMine) "Tu" else message.author.take(12) + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = if (message.isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Text(
                                        message.content,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Scrivi un messaggio") },
                        maxLines = 4,
                    )
                    Button(
                        onClick = {
                            viewModel.sendMessage(messageInput)
                            messageInput = ""
                        },
                        enabled = messageInput.isNotBlank() && !busy,
                    ) {
                        Text("Invia")
                    }
                }
            }

            activeCommunity != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.openCommunity("") }) { Text("‹ Community") }
                    Spacer(Modifier.weight(1f))
                    Text("Canali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(activeCommunity.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (activeCommunity.description.isNotBlank()) {
                    Text(activeCommunity.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (activeCommunity.channels.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "I metadati dei canali non sono ancora disponibili. Aggiorna la community o controlla i relay.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(activeCommunity.channels, key = { it.id }) { channel ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.openChannel(activeCommunity.id, channel.id)
                                }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("#", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(channel.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (channel.isPrivate) "Canale privato" else "Canale cifrato",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = onLegacyRooms) { Text("Apri Chat Rooms NIP-29 (legacy)") }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Community", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showJoin = true }) { Text("Unisciti") }
                    Button(onClick = { communityName = ""; description = ""; showCreate = true }) { Text("Crea") }
                }

                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(status ?: "Sincronizzazione delle community…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!status.isNullOrBlank()) {
                    Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("Le tue community", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (communities.isEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Qui appariranno le community a cui partecipi.")
                            Text(
                                "Puoi creare una community oppure unirti usando un invito Concord. Le chiavi restano cifrate nella tua lista personale Nostr.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { viewModel.createPostillaCommunity() }, enabled = !busy) {
                                Text("Crea la community Postilla")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(communities, key = { it.id }) { community ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.openCommunity(community.id)
                                }.padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(54.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            community.name.firstOrNull()?.uppercase() ?: "C",
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(community.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    val channelPreview = community.channels.firstOrNull()?.name
                                    Text(
                                        listOfNotNull(
                                            if (community.isOwner) "Amministratore" else "Membro",
                                            channelPreview,
                                            community.description.takeIf { it.isNotBlank() },
                                        ).joinToString(" · "),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Community popolari", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showBrowse = true }) { Text("Sfoglia tutte") }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Directory Concord")
                        Text(
                            "Le community suggerite verranno mostrate solo quando Postilla trova inviti pubblici verificabili nei contenuti Nostr. Non vengono inventati nomi o conteggi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onLegacyRooms) { Text("Chat Rooms NIP-29 (legacy)") }
                TextButton(onClick = { viewModel.refresh() }, enabled = !busy) { Text("Aggiorna") }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Crea una community Concord") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = communityName, onValueChange = { communityName = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Descrizione") }, minLines = 2)
                    Text("Verranno creati i canali generale, supporto e sviluppo.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    viewModel.createCommunity(communityName, description)
                }, enabled = communityName.isNotBlank() && !busy) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Annulla") } },
        )
    }

    if (showJoin) {
        AlertDialog(
            onDismissRequest = { showJoin = false },
            title = { Text("Unisciti a una community") },
            text = {
                OutlinedTextField(
                    value = inviteInput,
                    onValueChange = { inviteInput = it },
                    label = { Text("Link o invito Concord") },
                    minLines = 2,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showJoin = false
                    viewModel.joinFromInvite(inviteInput)
                    inviteInput = ""
                }, enabled = inviteInput.isNotBlank() && !busy) { Text("Unisciti") }
            },
            dismissButton = { TextButton(onClick = { showJoin = false }) { Text("Annulla") } },
        )
    }

    if (showBrowse) {
        AlertDialog(
            onDismissRequest = { showBrowse = false },
            title = { Text("Community popolari") },
            text = { Text("La directory pubblica non ha ancora inviti verificati da mostrare. Potrai comunque entrare tramite un link Concord condiviso su Nostr.") },
            confirmButton = { TextButton(onClick = { showBrowse = false }) { Text("Ok") } },
        )
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Community non disponibile") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("Ok") } },
        )
    }

    inviteLink?.let { link ->
        AlertDialog(
            onDismissRequest = { viewModel.clearInviteLink() },
            title = { Text("Invito Concord pubblicato") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Conserva questo invito: permette agli altri utenti di entrare nella community.")
                    Text(link, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(link)) }) { Text("Copia invito") }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.clearInviteLink() }) { Text("Chiudi") } },
        )
    }
}
