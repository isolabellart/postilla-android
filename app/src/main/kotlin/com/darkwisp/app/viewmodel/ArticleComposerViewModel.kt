package com.darkwisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darkwisp.app.R
import com.darkwisp.app.nostr.ClientMessage
import com.darkwisp.app.nostr.Nip09
import com.darkwisp.app.nostr.Nip23
import com.darkwisp.app.nostr.Nip37
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.repo.BlossomRepository
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.KeyRepository
import com.darkwisp.app.relay.RelayPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ArticleComposerViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepository = BlossomRepository(app, keyRepo.getPubkeyHex())

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title
    private val _summary = MutableStateFlow("")
    val summary: StateFlow<String> = _summary
    private val _coverImage = MutableStateFlow("")
    val coverImage: StateFlow<String> = _coverImage
    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body
    private val _hashtags = MutableStateFlow("")
    val hashtags: StateFlow<String> = _hashtags
    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing
    private val _uploadingCover = MutableStateFlow(false)
    val uploadingCover: StateFlow<Boolean> = _uploadingCover
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _preview = MutableStateFlow(false)
    val preview: StateFlow<Boolean> = _preview
    private val _published = MutableStateFlow(false)
    val published: StateFlow<Boolean> = _published

    private var articleDTag = UUID.randomUUID().toString()
    private var articlePublishedAt = System.currentTimeMillis() / 1000
    private var currentDraftId: String? = null
    private var dirty = false

    fun startNew() {
        _title.value = ""
        _summary.value = ""
        _coverImage.value = ""
        _body.value = ""
        _hashtags.value = ""
        _error.value = null
        _preview.value = false
        _publishing.value = false
        _uploadingCover.value = false
        _published.value = false
        articleDTag = UUID.randomUUID().toString()
        articlePublishedAt = System.currentTimeMillis() / 1000
        currentDraftId = null
        dirty = false
    }

    fun loadDraft(draft: Nip37.Draft) {
        if (draft.kind != Nip23.KIND_LONG_FORM) return
        currentDraftId = draft.dTag
        articleDTag = draft.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)
            ?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        _title.value = draft.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1).orEmpty()
        _summary.value = draft.tags.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1).orEmpty()
        _coverImage.value = draft.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1).orEmpty()
        _hashtags.value = draft.tags.filter { it.size >= 2 && it[0] == "t" }.joinToString(", ") { it[1] }
        articlePublishedAt = draft.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)
            ?.toLongOrNull() ?: draft.createdAt
        _body.value = draft.content
        _error.value = null
        _preview.value = false
        _published.value = false
        dirty = true
    }

    fun loadExisting(event: NostrEvent) {
        require(event.kind == Nip23.KIND_LONG_FORM) { "Only NIP-23 articles can be edited here" }
        articleDTag = Nip23.dTag(event).ifBlank { UUID.randomUUID().toString() }
        articlePublishedAt = event.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)
            ?.toLongOrNull() ?: event.created_at
        _title.value = Nip23.title(event)
        _summary.value = Nip23.summary(event)
        _coverImage.value = Nip23.image(event)
        _hashtags.value = Nip23.hashtags(event).joinToString(", ")
        _body.value = event.content
        currentDraftId = null
        _error.value = null
        _preview.value = false
        _published.value = false
        dirty = true
    }

    fun updateTitle(value: String) { _title.value = value; dirty = true }
    fun updateSummary(value: String) { _summary.value = value; dirty = true }
    fun updateBody(value: String) { _body.value = value; dirty = true }
    fun updateHashtags(value: String) { _hashtags.value = value; dirty = true }
    fun togglePreview() { _preview.value = !_preview.value }

    fun insertMarkdown(marker: String) {
        val addition = when (marker) {
            "## " -> "\n## Titolo"
            "**" -> "**grassetto**"
            "*" -> "*corsivo*"
            "> " -> "\n> citazione"
            "- " -> "\n- elemento"
            else -> marker
        }
        _body.value = if (_body.value.isBlank()) addition else _body.value + addition
        dirty = true
    }

    fun removeCover() {
        _coverImage.value = ""
        dirty = true
    }

    fun uploadCover(uri: Uri, resolver: ContentResolver, signer: NostrSigner?) {
        if (_uploadingCover.value) return
        viewModelScope.launch {
            _uploadingCover.value = true
            _error.value = null
            try {
                val mimeType = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Unable to read selected image")
                }
                require(bytes.isNotEmpty()) { "Selected image is empty" }
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                    ?.takeIf(String::isNotBlank) ?: "jpg"
                _coverImage.value = blossomRepository.uploadMedia(bytes, mimeType, extension, signer)
                dirty = true
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Cover upload failed"
            } finally {
                _uploadingCover.value = false
            }
        }
    }

    private fun cleanHashtags(): List<String> =
        _hashtags.value.split(',', '\n', ' ', '#').map { it.trim() }.filter { it.isNotBlank() }

    private fun draftTags(includeClientTag: Boolean): List<List<String>> = buildList {
        add(listOf("d", articleDTag))
        if (_title.value.isNotBlank()) add(listOf("title", _title.value.trim()))
        if (_summary.value.isNotBlank()) add(listOf("summary", _summary.value.trim()))
        if (_coverImage.value.isNotBlank()) add(listOf("image", _coverImage.value.trim()))
        add(listOf("published_at", articlePublishedAt.toString()))
        cleanHashtags().map { it.lowercase() }.distinct().forEach { add(listOf("t", it)) }
        if (includeClientTag) add(listOf("client", "Postilla"))
    }

    private fun articleTags(includeClientTag: Boolean): List<List<String>> =
        Nip23.buildArticleTags(
            dTag = articleDTag,
            title = _title.value,
            summary = _summary.value,
            image = _coverImage.value,
            publishedAt = articlePublishedAt,
            hashtags = cleanHashtags(),
            includeClientTag = includeClientTag
        )

    fun saveDraft(relayPool: RelayPool, signer: NostrSigner?, includeClientTag: Boolean = true) {
        if (signer == null || _published.value || !dirty) return
        if (_title.value.isBlank() && _body.value.isBlank() && _summary.value.isBlank()) return
        val draftId = currentDraftId ?: UUID.randomUUID().toString().also { currentDraftId = it }
        val tags = draftTags(includeClientTag)
        val bodyText = _body.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val innerJson = Nip37.serializeDraftContent(
                    pubkeyHex = signer.pubkeyHex,
                    innerKind = Nip23.KIND_LONG_FORM,
                    content = bodyText,
                    tags = tags
                )
                val encrypted = signer.nip44Encrypt(innerJson, signer.pubkeyHex)
                val wrapper = signer.signEvent(
                    kind = Nip37.KIND_DRAFT,
                    content = encrypted,
                    tags = Nip37.buildDraftTags(draftId, Nip23.KIND_LONG_FORM)
                )
                relayPool.sendToWriteRelays(ClientMessage.event(wrapper))
                dirty = false
            } catch (_: Exception) {
                // Keep the article in the editor; a later save attempt can retry.
            }
        }
    }

    fun publish(
        relayPool: RelayPool,
        signer: NostrSigner?,
        eventRepository: EventRepository,
        includeClientTag: Boolean = true,
        onPublished: (NostrEvent) -> Unit
    ) {
        if (_publishing.value) return
        if (signer == null) {
            _error.value = getApplication<Application>().getString(R.string.article_not_signed_in)
            return
        }
        if (_title.value.isBlank()) {
            _error.value = getApplication<Application>().getString(R.string.article_title_required)
            return
        }
        if (_body.value.isBlank()) {
            _error.value = getApplication<Application>().getString(R.string.article_body_required)
            return
        }

        _publishing.value = true
        _error.value = null
        val tags = articleTags(includeClientTag)
        val articleBody = _body.value
        viewModelScope.launch {
            try {
                val event = signer.signEvent(
                    kind = Nip23.KIND_LONG_FORM,
                    content = articleBody,
                    tags = tags
                )
                val send = { relayPool.sendToWriteRelays(ClientMessage.event(event)) }
                var outcome = relayPool.publishWithConfirmation(event.id, send = send)
                if (outcome.sentCount == 0 && relayPool.ensureWriteRelaysConnected() > 0) {
                    outcome = relayPool.publishWithConfirmation(event.id, send = send)
                }
                if (!outcome.confirmed && relayPool.cycleWriteRelays() > 0) {
                    outcome = relayPool.publishWithConfirmation(event.id, send = send)
                }
                if (!outcome.confirmed) {
                    _error.value = getApplication<Application>().getString(R.string.article_publish_unconfirmed)
                    _publishing.value = false
                    return@launch
                }
                eventRepository.addEvent(event)
                deleteSavedDraft(relayPool, signer)
                _published.value = true
                _publishing.value = false
                dirty = false
                onPublished(event)
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: e.message ?: "Article publication failed"
                _publishing.value = false
            }
        }
    }

    private suspend fun deleteSavedDraft(relayPool: RelayPool, signer: NostrSigner) {
        val dTag = currentDraftId ?: return
        currentDraftId = null
        try {
            val deletion = signer.signEvent(
                kind = Nip09.KIND_DELETION,
                content = "",
                tags = Nip09.buildAddressableDeletionTags(Nip37.KIND_DRAFT, signer.pubkeyHex, dTag)
            )
            relayPool.sendToWriteRelays(ClientMessage.event(deletion))
            val encrypted = signer.nip44Encrypt("", signer.pubkeyHex)
            val replacement = signer.signEvent(
                kind = Nip37.KIND_DRAFT,
                content = encrypted,
                tags = Nip37.buildDraftTags(dTag, Nip23.KIND_LONG_FORM)
            )
            relayPool.sendToWriteRelays(ClientMessage.event(replacement))
        } catch (_: Exception) {
            // Deletion is best effort after the article has been confirmed.
        }
    }
}
