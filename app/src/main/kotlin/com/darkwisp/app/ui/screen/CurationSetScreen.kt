package com.darkwisp.app.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkwisp.app.R
import com.darkwisp.app.nostr.CustomNip
import com.darkwisp.app.nostr.CurationSetEntry
import com.darkwisp.app.nostr.Nip23
import com.darkwisp.app.nostr.Nip51
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.repo.EventRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private fun curationEntryKey(entry: CurationSetEntry): String =
    entry.eventId?.let { "e:$it" }
        ?: "a:${entry.kind}:${entry.author.orEmpty()}:${entry.dTag.orEmpty()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurationSetScreen(
    eventRepo: EventRepository,
    author: String,
    dTag: String,
    onBack: () -> Unit,
    onOpenArticle: (kind: Int, author: String, dTag: String) -> Unit,
    onOpenNote: (eventId: String) -> Unit
) {
    val cachedSet = remember(author, dTag) {
        eventRepo.findAddressableEvent(Nip51.KIND_CURATION_SET, author, dTag)
    }
    val setEvent by eventRepo.observeAddressableEvent(
        Nip51.KIND_CURATION_SET, author, dTag
    ).collectAsState(initial = cachedSet)
    var retryNonce by remember(author, dTag) { mutableLongStateOf(0L) }
    val curation = remember(setEvent) { setEvent?.let(Nip51::parseCurationSet) }
    val resolvedEntries = remember(author, dTag) { mutableStateMapOf<String, NostrEvent>() }

    LaunchedEffect(eventRepo, author, dTag, retryNonce) {
        if (eventRepo.findAddressableEvent(Nip51.KIND_CURATION_SET, author, dTag) == null) {
            eventRepo.requestAddressableEvent(Nip51.KIND_CURATION_SET, author, dTag)
        }
    }

    LaunchedEffect(curation) {
        resolvedEntries.clear()
        val current = curation ?: return@LaunchedEffect
        coroutineScope {
            current.entries.forEach { entry ->
                launch {
                    val key = curationEntryKey(entry)
                    val relayHints = entry.relayHint?.let(::listOf) ?: emptyList()
                    if (entry.eventId != null) {
                        eventRepo.requestQuotedEvent(entry.eventId, relayHints)
                        eventRepo.observeEvent(entry.eventId).collect { event ->
                            if (event != null) resolvedEntries[key] = event
                        }
                    } else {
                        val entryAuthor = entry.author ?: return@launch
                        val entryDTag = entry.dTag ?: return@launch
                        eventRepo.requestAddressableEvent(
                            entry.kind, entryAuthor, entryDTag, relayHints
                        )
                        eventRepo.observeAddressableEvent(
                            entry.kind, entryAuthor, entryDTag
                        ).collect { event ->
                            if (event != null) resolvedEntries[key] = event
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        curation?.title ?: stringResource(R.string.curation_set_label),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        when {
            setEvent == null -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(stringResource(R.string.curation_set_not_found))
                    TextButton(onClick = { retryNonce++ }) {
                        Text(stringResource(R.string.curation_set_retry))
                    }
                }
            }
            curation == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    item(key = "curation-header") {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            if (curation.image != null) {
                                AsyncImage(
                                    model = curation.image,
                                    contentDescription = curation.title,
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                                )
                            }
                            Text(
                                text = curation.title,
                                style = MaterialTheme.typography.headlineSmall
                            )
                            if (curation.description.isNotBlank()) {
                                Text(
                                    text = curation.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.curation_set_items, curation.entries.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    if (curation.entries.isEmpty()) {
                        item(key = "curation-empty") {
                            Text(
                                stringResource(R.string.curation_set_empty),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(curation.entries, key = ::curationEntryKey) { entry ->
                            CurationEntryCard(
                                entry = entry,
                                event = resolvedEntries[curationEntryKey(entry)],
                                onOpenArticle = onOpenArticle,
                                onOpenNote = onOpenNote
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CurationEntryCard(
    entry: CurationSetEntry,
    event: NostrEvent?,
    onOpenArticle: (kind: Int, author: String, dTag: String) -> Unit,
    onOpenNote: (eventId: String) -> Unit
) {
    val isArticle = event?.kind == Nip23.KIND_LONG_FORM || event?.kind == CustomNip.KIND
    val isNote = event?.kind == 1
    val articleDTag = event?.tags
        ?.firstOrNull { it.size >= 2 && it[0] == "d" }
        ?.get(1)
        .orEmpty()
    val canOpen = event != null && ((isArticle && articleDTag.isNotBlank()) || isNote)

    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(
                if (canOpen) Modifier.clickable {
                    val loaded = event ?: return@clickable
                    if (isArticle && articleDTag.isNotBlank()) {
                        onOpenArticle(loaded.kind, loaded.pubkey, articleDTag)
                    } else if (isNote) {
                        onOpenNote(loaded.id)
                    }
                } else Modifier
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val title = event?.tags
                ?.firstOrNull { it.size >= 2 && (it[0] == "title" || it[0] == "name") }
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
            Text(
                text = title ?: when {
                    isArticle -> stringResource(R.string.curation_set_article)
                    isNote -> stringResource(R.string.curation_set_note)
                    else -> "Kind ${entry.kind}"
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val summary = event?.tags
                ?.firstOrNull { it.size >= 2 && (it[0] == "summary" || it[0] == "description") }
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
            val preview = summary ?: event?.content?.takeIf { it.isNotBlank() }
            if (preview != null) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else if (event == null) {
                Text(
                    stringResource(R.string.curation_set_entry_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            event?.let {
                Text(
                    text = it.pubkey.take(12) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
