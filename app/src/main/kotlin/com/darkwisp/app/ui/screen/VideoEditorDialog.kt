package com.darkwisp.app.ui.screen

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.darkwisp.app.R
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Nip71
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.EventRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VideoEditorDialog(
    event: NostrEvent,
    signer: NostrSigner,
    relayPool: RelayPool,
    eventRepository: EventRepository,
    blossomRepository: BlossomRepository,
    onDismiss: () -> Unit,
    onSaved: (NostrEvent) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val originalDTag = remember(event.id) { Nip71.getDTag(event).orEmpty() }
    var title by remember(event.id) { mutableStateOf(Nip71.getTitle(event).orEmpty()) }
    var description by remember(event.id) { mutableStateOf(event.content) }
    var tagsText by remember(event.id) {
        mutableStateOf(event.tags.filter { it.firstOrNull() == "t" }.mapNotNull { it.getOrNull(1) }.distinct().joinToString(", "))
    }
    var language by remember(event.id) {
        mutableStateOf(event.tags.firstOrNull { it.firstOrNull() == "l" && it.getOrNull(2) == "ISO-639-1" }?.getOrNull(1).orEmpty())
    }
    val existingWarning = remember(event.id) {
        event.tags.firstOrNull { it.firstOrNull() == "content-warning" }?.getOrNull(1)
    }
    var contentWarning by remember(event.id) { mutableStateOf(existingWarning != null) }
    var warningLabel by remember(event.id) { mutableStateOf(existingWarning.orEmpty()) }
    var videoFiles by remember(event.id) { mutableStateOf(Nip71.parseVideoMeta(event)) }
    var uploading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !uploading && !saving) {
            scope.launch {
                uploading = true
                error = null
                try {
                    val resolver = context.contentResolver
                    val mime = resolver.getType(uri)?.substringBefore(';')?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                    val bytes = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Unable to read selected video")
                    }
                    require(bytes.isNotEmpty()) { "Selected video is empty" }
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.isNotBlank() } ?: "mp4"
                    val uploadedUrl = blossomRepository.uploadMedia(bytes, mime, extension, signer)
                    videoFiles = videoFiles + Nip71.VideoMeta(url = uploadedUrl, mimeType = mime)
                } catch (failure: Exception) {
                    error = failure.localizedMessage ?: failure.message ?: "Video upload failed"
                } finally {
                    uploading = false
                }
            }
        }
    }

    val isOwner = signer.pubkeyHex == event.pubkey
    val canSave = isOwner && Nip71.isAddressableVideo(event) && originalDTag.isNotBlank() &&
        title.isNotBlank() && videoFiles.isNotEmpty() && !uploading && !saving

    Dialog(
        onDismissRequest = { if (!saving && !uploading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.video_edit_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.video_edit_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss, enabled = !saving && !uploading) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.video_edit_cancel))
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.video_edit_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.video_edit_variant_count, videoFiles.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (videoFiles.isEmpty()) Text(stringResource(R.string.video_edit_empty_files), color = MaterialTheme.colorScheme.error)
                    videoFiles.forEachIndexed { index, video ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                                    Text("\${index + 1}", style = MaterialTheme.typography.titleMedium)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(video.dim ?: video.mimeType ?: "Video", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                    Text(video.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(video.url), video.mimeType ?: "video/*"))
                                    }.onFailure { error = it.localizedMessage ?: "Unable to open video" }
                                }) { Icon(Icons.Filled.PlayArrow, contentDescription = "Preview video") }
                                if (videoFiles.size > 1) {
                                    IconButton(onClick = { videoFiles = videoFiles.filterIndexed { itemIndex, _ -> itemIndex != index } }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Remove variant")
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        enabled = !uploading && !saving,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(stringResource(R.string.video_edit_add_variant)) }
                    if (uploading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.video_edit_uploading), style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Divider()
                    Text(stringResource(R.string.video_edit_title_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = title, onValueChange = { title = it.take(200) },
                        label = { Text(stringResource(R.string.video_edit_title_label)) },
                        enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description, onValueChange = { description = it.take(10_000) },
                        label = { Text(stringResource(R.string.video_edit_description_label)) },
                        enabled = !saving, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = tagsText, onValueChange = { tagsText = it.take(500) },
                        label = { Text(stringResource(R.string.video_edit_tags_label)) },
                        supportingText = { Text(stringResource(R.string.video_edit_tags_hint)) },
                        enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = language, onValueChange = { language = it.filter { c -> c.isLetter() }.take(2).lowercase() },
                        label = { Text(stringResource(R.string.video_edit_language_label)) },
                        supportingText = { Text(stringResource(R.string.video_edit_language_hint)) },
                        enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = contentWarning, onCheckedChange = { contentWarning = it }, enabled = !saving)
                        Text(stringResource(R.string.video_edit_nsfw), modifier = Modifier.weight(1f))
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }

                Divider()
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, enabled = !saving && !uploading, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.video_edit_cancel))
                    }
                    Button(
                        onClick = {
                            if (!isOwner) {
                                error = "Only the author can edit this video."
                                return@Button
                            }
                            if (language.isNotBlank() && !language.matches(Regex("[a-z]{2}"))) {
                                error = "Enter a valid two-letter language code."
                                return@Button
                            }
                            scope.launch {
                                saving = true
                                error = null
                                try {
                                    val cleanedTags = tagsText.split(',', '#', ' ', '\n', '\t')
                                        .map { it.trim().lowercase() }
                                        .filter { it.matches(Regex("[\\p{L}\\p{N}_-]{1,64}")) }
                                        .distinct().take(30)
                                    val managedTags = setOf("d", "title", "published_at", "imeta", "duration", "t", "content-warning", "l", "L")
                                    val preservedTags = event.tags.filter { it.firstOrNull() !in managedTags }
                                    val videoTags = Nip71.buildVideoTags(
                                        title = title.trim(), media = videoFiles, hashtags = cleanedTags,
                                        contentWarning = if (contentWarning) warningLabel.ifBlank { "sensitive" } else null,
                                        dTag = originalDTag, publishedAt = System.currentTimeMillis() / 1000
                                    )
                                    val languageTags = if (language.isBlank()) emptyList() else listOf(
                                        listOf("L", "ISO-639-1"), listOf("l", language, "ISO-639-1")
                                    )
                                    val revised = signer.signEvent(kind = event.kind, content = description, tags = videoTags + languageTags + preservedTags)
                                    val send = { relayPool.sendToWriteRelays(ClientMessage.event(revised)) }
                                    var outcome = relayPool.publishWithConfirmation(revised.id, send = send)
                                    if (outcome.sentCount == 0 && relayPool.ensureWriteRelaysConnected() > 0) {
                                        outcome = relayPool.publishWithConfirmation(revised.id, send = send)
                                    }
                                    if (!outcome.confirmed && relayPool.cycleWriteRelays() > 0) {
                                        outcome = relayPool.publishWithConfirmation(revised.id, send = send)
                                    }
                                    if (!outcome.confirmed) {
                                        error = context.getString(R.string.video_edit_unconfirmed)
                                        saving = false
                                        return@launch
                                    }
                                    eventRepository.addEvent(revised)
                                    onSaved(revised)
                                } catch (failure: Exception) {
                                    error = failure.localizedMessage ?: failure.message ?: "Unable to save video changes"
                                    saving = false
                                }
                            }
                        },
                        enabled = canSave, modifier = Modifier.weight(1f)
                    ) {
                        if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.video_edit_save))
                    }
                }
            }
        }
    }
}
