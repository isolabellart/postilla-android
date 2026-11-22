package com.darkwisp.app.ui.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import android.widget.Toast
import android.content.Intent
import com.darkwisp.app.R
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.repo.TranslationRepository
import com.darkwisp.app.repo.TranslationState
import com.darkwisp.app.repo.TranslationStatus
import com.darkwisp.app.ui.component.ProfilePicture
import com.darkwisp.app.nostr.CustomNip
import com.darkwisp.app.nostr.Nip30
import com.darkwisp.app.nostr.Nip51
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.Nip19
import com.darkwisp.app.nostr.hexToByteArray
import com.darkwisp.app.nostr.Nip23
import com.darkwisp.app.ui.component.ActionBar
import com.darkwisp.app.ui.component.NoteActions
import com.darkwisp.app.ui.component.PostCard
import com.darkwisp.app.ui.component.RichContent
import com.darkwisp.app.ui.component.CollapsedRepliesRow
import com.darkwisp.app.ui.component.threadConnector
import com.darkwisp.app.ui.component.threadIndentDp
import com.darkwisp.app.viewmodel.ArticleViewModel
import com.darkwisp.app.viewmodel.thread.ThreadItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    eventRepo: EventRepository,
    onBack: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    onHashtagClick: ((String) -> Unit)? = null,
    onReply: (NostrEvent) -> Unit = {},
    onReact: (NostrEvent, String) -> Unit = { _, _ -> },
    onRepost: (NostrEvent) -> Unit = {},
    onQuote: (NostrEvent) -> Unit = {},
    onZap: (NostrEvent) -> Unit = {},
    onZapInstant: (NostrEvent) -> Unit = onZap,
    onAddToList: (String) -> Unit = {},
    noteActions: NoteActions? = null,
    zapAnimatingIds: Set<String> = emptySet(),
    zapInProgressIds: Set<String> = emptySet(),
    listedIds: Set<String> = emptySet(),
    pinnedIds: Set<String> = emptySet(),
    userPubkey: String? = null,
    resolvedEmojis: Map<String, String> = emptyMap(),
    unicodeEmojis: List<String> = emptyList(),
    onOpenEmojiLibrary: (() -> Unit)? = null,
    translationRepository: TranslationRepository? = null,
    onEditArticle: (NostrEvent) -> Unit = {}
) {
    val article by viewModel.article.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val title by viewModel.title.collectAsState()
    val coverImage by viewModel.coverImage.collectAsState()
    val publishedAt by viewModel.publishedAt.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()
    val definedKinds by viewModel.definedKinds.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val isCommentsLoading by viewModel.isCommentsLoading.collectAsState()

    val reactionVersion by eventRepo.reactionVersion.collectAsState()
    val replyCountVersion by eventRepo.replyCountVersion.collectAsState()
    val zapVersion by eventRepo.zapVersion.collectAsState()
    val repostVersion by eventRepo.repostVersion.collectAsState()
    val profileVersion by eventRepo.profileVersion.collectAsState()
    val translationVersion by translationRepository?.version?.collectAsState()
        ?: remember { androidx.compose.runtime.mutableIntStateOf(0) }

    val authorPubkey = article?.pubkey
    val profile = remember(authorPubkey, profileVersion) { authorPubkey?.let { eventRepo.getProfileData(it) } }
    val summary = article?.tags?.firstOrNull { it.size >= 2 && it[0] == "summary" }?.get(1).orEmpty()
    val translationNamespace = translationRepository?.getCacheNamespace() ?: "unavailable"
    val titleTranslationKey = "article:$translationNamespace:${article?.id.orEmpty()}:title"
    val summaryTranslationKey = "article:$translationNamespace:${article?.id.orEmpty()}:summary"
    val bodyTranslationKey = "article:$translationNamespace:${article?.id.orEmpty()}:body"
    val titleTranslationState = remember(translationVersion, titleTranslationKey) {
        translationRepository?.getState(titleTranslationKey) ?: TranslationState()
    }
    val summaryTranslationState = remember(translationVersion, summaryTranslationKey) {
        translationRepository?.getState(summaryTranslationKey) ?: TranslationState()
    }
    val bodyTranslationState = remember(translationVersion, bodyTranslationKey) {
        translationRepository?.getState(bodyTranslationKey) ?: TranslationState()
    }
    val translationStates = listOfNotNull(
        title?.takeIf(String::isNotBlank)?.let { titleTranslationState },
        summary.takeIf(String::isNotBlank)?.let { summaryTranslationState },
        article?.content?.takeIf(String::isNotBlank)?.let { bodyTranslationState }
    )
    val translationLoading = translationStates.any {
        it.status == TranslationStatus.IDENTIFYING_LANGUAGE ||
            it.status == TranslationStatus.DOWNLOADING_MODEL ||
            it.status == TranslationStatus.TRANSLATING
    }
    val translationComplete = translationStates.isNotEmpty() && translationStates.all {
        it.status == TranslationStatus.DONE || it.status == TranslationStatus.SAME_LANGUAGE
    }
    val hasTranslation = translationStates.any {
        it.status == TranslationStatus.DONE && it.translatedText.isNotBlank()
    }
    val translationFailed = translationStates.any { it.status == TranslationStatus.ERROR }
    var showOriginalArticle by remember(article?.id) { mutableStateOf(false) }
    val displayTitle = if (!showOriginalArticle && titleTranslationState.status == TranslationStatus.DONE &&
        titleTranslationState.translatedText.isNotBlank()) titleTranslationState.translatedText else title
    val displaySummary = if (!showOriginalArticle && summaryTranslationState.status == TranslationStatus.DONE &&
        summaryTranslationState.translatedText.isNotBlank()) summaryTranslationState.translatedText else summary
    val displayContent = if (!showOriginalArticle && bodyTranslationState.status == TranslationStatus.DONE &&
        bodyTranslationState.translatedText.isNotBlank()) bodyTranslationState.translatedText else article?.content.orEmpty()
    val blocks = remember(displayContent) { parseMarkdownBlocks(displayContent) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var articleShareMenuExpanded by remember(article?.id) { mutableStateOf(false) }

    val articleEmojiMap = remember(article) {
        article?.let { Nip30.parseEmojiTags(it) } ?: emptyMap()
    }
    val emojiMap = remember(articleEmojiMap, resolvedEmojis) {
        articleEmojiMap + resolvedEmojis
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayTitle?.takeIf(String::isNotBlank) ?: if (article?.kind == CustomNip.KIND) "NIP" else "Article",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    article?.takeIf { it.kind == Nip23.KIND_LONG_FORM && it.pubkey == userPubkey }?.let { ownArticle ->
                        IconButton(onClick = { onEditArticle(ownArticle) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.article_edit))
                        }
                    }
                    article?.takeIf { it.kind == Nip23.KIND_LONG_FORM }?.let { shareableArticle ->
                        Box {
                            IconButton(onClick = { articleShareMenuExpanded = true }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.article_share))
                            }
                            DropdownMenu(expanded = articleShareMenuExpanded, onDismissRequest = { articleShareMenuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.article_copy_link)) },
                                    onClick = {
                                        articleShareMenuExpanded = false
                                        val address = Nip19.naddrEncode(
                                            dTag = Nip23.dTag(shareableArticle),
                                            authorHex = shareableArticle.pubkey,
                                            kind = shareableArticle.kind
                                        )
                                        clipboardManager.setText(AnnotatedString("nostr:" + address))
                                        Toast.makeText(context, context.getString(R.string.article_address_copied), Toast.LENGTH_SHORT).show()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.article_share)) },
                                    onClick = {
                                        articleShareMenuExpanded = false
                                        val address = Nip19.naddrEncode(
                                            dTag = Nip23.dTag(shareableArticle),
                                            authorHex = shareableArticle.pubkey,
                                            kind = shareableArticle.kind
                                        )
                                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "https://njump.me/" + address)
                                        }
                                        context.startActivity(Intent.createChooser(sendIntent, context.getString(R.string.article_share)))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.article_copy_text)) },
                                    onClick = {
                                        articleShareMenuExpanded = false
                                        val rawTitle = shareableArticle.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1).orEmpty()
                                        val rawDescription = shareableArticle.tags.firstOrNull { it.size >= 2 && (it[0] == "summary" || it[0] == "description") }?.get(1).orEmpty()
                                        val fullText = buildString {
                                            if (rawTitle.isNotBlank()) append(rawTitle).append("\n\n")
                                            if (rawDescription.isNotBlank()) append(rawDescription).append("\n\n")
                                            append(shareableArticle.content)
                                        }
                                        clipboardManager.setText(AnnotatedString(fullText))
                                        Toast.makeText(context, context.getString(R.string.article_copy_text), Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            article == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Article not found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                    // Header
                    item(key = "header") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            if (coverImage != null) {
                                AsyncImage(
                                    model = coverImage,
                                    contentDescription = title,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .padding(top = 8.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                            }

                            Text(
                                text = displayTitle?.takeIf(String::isNotBlank) ?: "Untitled",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (displaySummary.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = displaySummary,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            if (authorPubkey != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { onProfileClick(authorPubkey) }
                                ) {
                                    ProfilePicture(url = profile?.picture, size = 32)
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        val displayName = profile?.displayString
                                            ?: "${authorPubkey.take(8)}...${authorPubkey.takeLast(4)}"
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (publishedAt != null) {
                                            Text(
                                                text = formatArticleDate(publishedAt!!),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            if (definedKinds.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                FlowRow {
                                    definedKinds.forEach { dk ->
                                        val label = if (dk.name.isNotBlank()) "kind ${dk.num} · ${dk.name}"
                                                    else "kind ${dk.num}"
                                        AssistChip(
                                            onClick = {},
                                            enabled = false,
                                            label = { Text(label) },
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                    }
                                }
                            }

                            if (hashtags.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                FlowRow {
                                    hashtags.forEach { tag ->
                                        SuggestionChip(
                                            onClick = { onHashtagClick?.invoke(tag) },
                                            label = { Text(tag) },
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                    }
                                }
                            }

                            if (translationRepository != null && translationStates.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (translationFailed) {
                                        Text(
                                            stringResource(R.string.marketplace_translation_failed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    } else if (translationComplete && !hasTranslation) {
                                        Text(
                                            stringResource(R.string.marketplace_translation_same_language),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!translationComplete || hasTranslation) {
                                        IconButton(
                                            onClick = {
                                                if (translationComplete && hasTranslation) {
                                                    showOriginalArticle = !showOriginalArticle
                                                } else {
                                                    if (!title.isNullOrBlank() && titleTranslationState.status !in setOf(TranslationStatus.DONE, TranslationStatus.SAME_LANGUAGE))
                                                        translationRepository.translate(titleTranslationKey, title.orEmpty())
                                                    if (summary.isNotBlank() && summaryTranslationState.status !in setOf(TranslationStatus.DONE, TranslationStatus.SAME_LANGUAGE))
                                                        translationRepository.translate(summaryTranslationKey, summary)
                                                    if (!article?.content.isNullOrBlank() && bodyTranslationState.status !in setOf(TranslationStatus.DONE, TranslationStatus.SAME_LANGUAGE))
                                                        translationRepository.translate(bodyTranslationKey, article?.content.orEmpty())
                                                }
                                            },
                                            enabled = !translationLoading
                                        ) {
                                            if (translationLoading) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            } else {
                                                Icon(
                                                    Icons.Outlined.Translate,
                                                    contentDescription = stringResource(
                                                        if (translationComplete && hasTranslation) {
                                                            if (showOriginalArticle) R.string.marketplace_show_translation
                                                            else R.string.marketplace_show_original
                                                        } else R.string.marketplace_translate
                                                    ),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                    if (article?.kind == Nip51.KIND_CURATION_SET) {
                        val curation = article?.let(Nip51::parseCurationSet)
                        if (curation != null) {
                            item(key = "curation-set-summary") {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    curation.image?.let { image ->
                                        AsyncImage(
                                            model = image,
                                            contentDescription = curation.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
                                        )
                                    }
                                    Text(curation.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    if (curation.description.isNotBlank()) {
                                        Text(curation.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            if (curation.entries.isEmpty()) {
                                item(key = "curation-empty") {
                                    Text(
                                        stringResource(R.string.curation_empty_entries),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                            }
                            items(curation.entries, key = { entry ->
                                entry.eventId ?: (entry.kind.toString() + ":" + entry.author + ":" + entry.dTag)
                            }) { entry ->
                                val nostrAddress = entry.eventId?.let { eventId ->
                                    Nip19.neventEncode(
                                        eventId = eventId.hexToByteArray(),
                                        relays = listOfNotNull(entry.relayHint)
                                    )
                                } ?: Nip19.naddrEncode(
                                    dTag = entry.dTag.orEmpty(),
                                    authorHex = entry.author.orEmpty(),
                                    kind = entry.kind,
                                    relays = listOfNotNull(entry.relayHint)
                                )
                                RichContent(
                                    content = "nostr:" + nostrAddress,
                                    eventRepo = eventRepo,
                                    noteActions = noteActions,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    val emojiPack = article?.takeIf { it.kind == 30030 }?.let { Nip30.parseEmojiSet(it) }
                    if (emojiPack != null) {
                        item(key = "emoji-pack-content") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(emojiPack.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.emoji_pack_items_count, emojiPack.emojis.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 10.dp)
                                ) {
                                    emojiPack.emojis.forEach { emoji ->
                                        AsyncImage(
                                            model = emoji.url,
                                            contentDescription = emoji.shortcode,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val textlessAddressable = article?.takeIf { event ->
                        event.kind !in setOf(30023, CustomNip.KIND, Nip51.KIND_CURATION_SET, 30030, 30311) &&
                            event.content.isBlank()
                    }
                    if (textlessAddressable != null) {
                        item(key = "addressable-event-details") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(stringResource(R.string.postilla_addressable_event_details), style = MaterialTheme.typography.titleSmall)
                                textlessAddressable.tags.filter { it.size >= 2 }.forEach { tag ->
                                    Text(
                                        tag[0] + ": " + tag.drop(1).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Markdown blocks
                    items(blocks.size, key = { "block-$it" }) { index ->
                        val block = blocks[index]
                        when (block) {
                            is MdBlock.Heading -> ArticleHeading(block, emojiMap)
                            is MdBlock.Paragraph -> ArticleParagraph(block, emojiMap)
                            is MdBlock.Image -> ArticleImage(block)
                            is MdBlock.CodeBlock -> ArticleCodeBlock(block)
                            is MdBlock.BlockQuote -> ArticleBlockQuote(block, emojiMap)
                            is MdBlock.NostrEmbed -> ArticleNostrEmbed(
                                block = block,
                                eventRepo = eventRepo,
                                noteActions = noteActions,
                                onProfileClick = onProfileClick
                            )
                            is MdBlock.ListItem -> ArticleMarkdownListItem(ArticleMarkdownBlock.ListItem(block.ordered, block.number, block.text))
                            is MdBlock.TaskItem -> ArticleMarkdownTaskItem(ArticleMarkdownBlock.TaskItem(block.checked, block.text))
                            is MdBlock.Table -> ArticleMarkdownTable(ArticleMarkdownBlock.Table(block.headers, block.rows))
                            is MdBlock.Footnote -> ArticleMarkdownPreview(markdown = "[^" + block.id + "]: " + block.text, modifier = Modifier.padding(horizontal = 16.dp))
                            is MdBlock.Math -> ArticleMarkdownPreview(markdown = "\$\$\n" + block.text + "\n\$\$", modifier = Modifier.padding(horizontal = 16.dp))
                            is MdBlock.HorizontalRule -> HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }

                    // Article action bar
                    if (article != null) {
                        item(key = "action-bar") {
                            val articleEvent = article!!
                            val likeCount = remember(reactionVersion, articleEvent.id) { eventRepo.getReactionCount(articleEvent.id) }
                            // Use comment list size for consistency with the comments section below
                            val replyCount = comments.size
                            val zapSats = remember(zapVersion, articleEvent.id) { eventRepo.getZapSats(articleEvent.id) }
                            val userEmojis = remember(reactionVersion, articleEvent.id, userPubkey) {
                                userPubkey?.let { eventRepo.getUserReactionEmojis(articleEvent.id, it) } ?: emptySet()
                            }
                            val repostCount = remember(repostVersion, articleEvent.id) { eventRepo.getRepostCount(articleEvent.id) }
                            val hasUserReposted = remember(repostVersion, articleEvent.id) { eventRepo.hasUserReposted(articleEvent.id) }
                            val hasUserZapped = remember(zapVersion, articleEvent.id) { eventRepo.hasUserZapped(articleEvent.id) }
                            val eventReactionEmojiUrls = remember(reactionVersion, articleEvent.id) { eventRepo.getReactionEmojiUrls(articleEvent.id) }

                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            ActionBar(
                                onReply = { onReply(articleEvent) },
                                onReact = { emoji -> onReact(articleEvent, emoji) },
                                userReactionEmojis = userEmojis,
                                onRepost = { onRepost(articleEvent) },
                                onQuote = { onQuote(articleEvent) },
                                hasUserReposted = hasUserReposted,
                                onZap = { onZap(articleEvent) },
                                hasUserZapped = hasUserZapped,
                                onAddToList = { onAddToList(articleEvent.id) },
                                isInList = articleEvent.id in listedIds,
                                likeCount = likeCount,
                                repostCount = repostCount,
                                replyCount = replyCount,
                                zapSats = zapSats,
                                isZapAnimating = articleEvent.id in zapAnimatingIds,
                                isZapInProgress = articleEvent.id in zapInProgressIds,
                                reactionEmojiUrls = eventReactionEmojiUrls,
                                resolvedEmojis = resolvedEmojis,
                                unicodeEmojis = unicodeEmojis,
                                onOpenEmojiLibrary = onOpenEmojiLibrary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }

                    // Comments header
                    item(key = "comments-header") {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Comments",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (comments.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "(${comments.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (isCommentsLoading && comments.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }

                    // Comment items
                    items(items = comments, key = { "comment_${it.key}" }, contentType = { "comment" }) { item ->
                        if (item !is ThreadItem.Post) {
                            // Folded comment subtree — expand inline.
                            if (item is ThreadItem.CollapsedReplies) {
                                CollapsedRepliesRow(
                                    item = item,
                                    onExpand = { viewModel.expandBranch(item.anchor.id) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        } else {
                            val event = item.event
                            val commentProfile = remember(profileVersion, event.pubkey) { eventRepo.getProfileData(event.pubkey) }
                            val commentLikeCount = remember(reactionVersion, event.id) { eventRepo.getReactionCount(event.id) }
                            val commentReplyCount = remember(replyCountVersion, event.id) { eventRepo.getReplyCount(event.id) }
                            val commentZapSats = remember(zapVersion, event.id) { eventRepo.getZapSats(event.id) }
                            val commentUserEmojis = remember(reactionVersion, event.id, userPubkey) {
                                userPubkey?.let { eventRepo.getUserReactionEmojis(event.id, it) } ?: emptySet()
                            }
                            val commentRepostCount = remember(repostVersion, event.id) { eventRepo.getRepostCount(event.id) }
                            val commentHasUserReposted = remember(repostVersion, event.id) { eventRepo.hasUserReposted(event.id) }
                            val commentHasUserZapped = remember(zapVersion, event.id) { eventRepo.hasUserZapped(event.id) }
                            val commentReactionEmojiUrls = remember(reactionVersion, event.id) { eventRepo.getReactionEmojiUrls(event.id) }
                            val indent = threadIndentDp(item.depth)
                            val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            PostCard(
                                event = event,
                                profile = commentProfile,
                                onReply = { onReply(event) },
                                onProfileClick = { onProfileClick(event.pubkey) },
                                onNavigateToProfile = onProfileClick,
                                onNoteClick = {},
                                onReact = { emoji -> onReact(event, emoji) },
                                userReactionEmojis = commentUserEmojis,
                                onRepost = { onRepost(event) },
                                onQuote = { onQuote(event) },
                                hasUserReposted = commentHasUserReposted,
                                repostCount = commentRepostCount,
                                onZap = { onZap(event) },
                                onZapLongPress = { onZapInstant(event) },
                                hasUserZapped = commentHasUserZapped,
                                likeCount = commentLikeCount,
                                replyCount = commentReplyCount,
                                zapSats = commentZapSats,
                                isZapAnimating = event.id in zapAnimatingIds,
                                isZapInProgress = event.id in zapInProgressIds,
                                eventRepo = eventRepo,
                                reactionEmojiUrls = commentReactionEmojiUrls,
                                resolvedEmojis = resolvedEmojis,
                                unicodeEmojis = unicodeEmojis,
                                onOpenEmojiLibrary = onOpenEmojiLibrary,
                                isOwnEvent = event.pubkey == userPubkey,
                                onAddToList = { onAddToList(event.id) },
                                isInList = event.id in listedIds,
                                noteActions = noteActions,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem()
                                    .threadConnector(
                                        show = item.depth > 0,
                                        indent = indent,
                                        lineColor = lineColor,
                                        dashedTop = item.connectorStartsMidAir
                                    )
                                    .padding(start = indent)
                            )
                        }
                    }

                    item(key = "footer") { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }
}

// -- Block renderer composables --

@Composable
private fun ArticleHeading(block: MdBlock.Heading, emojiMap: Map<String, String>) {
    val style = when (block.level) {
        1 -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
        2 -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
        3 -> MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
        4 -> MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
        5 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        else -> MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    }
    val formatted = formatInlineWithEmoji(block.text, emojiMap)
    Text(
        text = formatted.text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        inlineContent = formatted.inlineContent
    )
}

@Composable
private fun ArticleParagraph(block: MdBlock.Paragraph, emojiMap: Map<String, String>) {
    val formatted = formatInlineWithEmoji(block.text, emojiMap)
    Text(
        text = formatted.text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        inlineContent = formatted.inlineContent
    )
}

@Composable
private fun ArticleImage(block: MdBlock.Image) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        AsyncImage(
            model = block.url,
            contentDescription = block.alt,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )
        if (!block.alt.isNullOrBlank() && block.alt != block.url) {
            Text(
                text = block.alt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ArticleCodeBlock(block: MdBlock.CodeBlock) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

@Composable
private fun ArticleBlockQuote(block: MdBlock.BlockQuote, emojiMap: Map<String, String>) {
    val formatted = formatInlineWithEmoji(block.text, emojiMap)
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    RoundedCornerShape(2.dp)
                )
        )
        Text(
            text = formatted.text,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
            inlineContent = formatted.inlineContent
        )
    }
}

@Composable
private fun ArticleNostrEmbed(
    block: MdBlock.NostrEmbed,
    eventRepo: EventRepository,
    noteActions: NoteActions?,
    onProfileClick: (String) -> Unit
) {
    RichContent(
        content = block.content,
        eventRepo = eventRepo,
        onProfileClick = { onProfileClick(it) },
        noteActions = noteActions,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

// -- Inline formatting --

private data class FormattedInline(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>
)

@Composable
private fun formatInline(
    text: String,
    emojiMap: Map<String, String> = emptyMap()
): AnnotatedString {
    val formatted = formatInlineWithEmoji(text, emojiMap)
    return formatted.text
}

@Composable
private fun formatInlineWithEmoji(
    text: String,
    emojiMap: Map<String, String> = emptyMap()
): FormattedInline {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize

    val segments = if (emojiMap.isNotEmpty()) {
        splitTextForEmojis(text, emojiMap)
    } else {
        listOf<InlineSegment>(InlineSegment.TextSegment(text))
    }

    val usedEmojis = segments.filterIsInstance<InlineSegment.EmojiSegment>().distinctBy { it.shortcode }
    val inlineContent: Map<String, InlineTextContent> = if (usedEmojis.isNotEmpty()) {
        usedEmojis.associate { emoji: InlineSegment.EmojiSegment ->
            emoji.shortcode to InlineTextContent(
                placeholder = Placeholder(
                    width = fontSize * 1.5f,
                    height = fontSize * 1.3f,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                )
            ) {
                AsyncImage(
                    model = emoji.url,
                    contentDescription = emoji.shortcode,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else emptyMap()

    val annotated = remember(text, linkColor, emojiMap) {
        buildAnnotatedString {
            for (segment in segments) {
                when (segment) {
                    is InlineSegment.TextSegment -> {
                        var i = 0
                        while (i < segment.text.length) {
                            when {
                                text.startsWith("![", i) -> {
                                    val closeBracket = text.indexOf(']', i + 2)
                                    val closeParen = if (closeBracket >= 0) text.indexOf(')', closeBracket) else -1
                                    if (closeBracket >= 0 && closeParen >= 0 && text.getOrNull(closeBracket + 1) == '(') {
                                        i = closeParen + 1
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                text[i] == '[' -> {
                                    val closeBracket = text.indexOf(']', i + 1)
                                    val openParen = closeBracket + 1
                                    if (closeBracket > i && openParen < text.length && text[openParen] == '(') {
                                        val closeParen = text.indexOf(')', openParen)
                                        if (closeParen > openParen) {
                                            val linkText = text.substring(i + 1, closeBracket)
                                            val linkUrl = text.substring(openParen + 1, closeParen)
                                            withLink(LinkAnnotation.Url(linkUrl)) {
                                                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                                                    append(linkText)
                                                }
                                            }
                                            i = closeParen + 1
                                        } else {
                                            append(text[i])
                                            i++
                                        }
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                text.startsWith("***", i) || text.startsWith("___", i) -> {
                                    val delim = text.substring(i, i + 3)
                                    val end = text.indexOf(delim, i + 3)
                                    if (end > i) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                                            append(text.substring(i + 3, end))
                                        }
                                        i = end + 3
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                text.startsWith("**", i) || text.startsWith("__", i) -> {
                                    val delim = text.substring(i, i + 2)
                                    val end = text.indexOf(delim, i + 2)
                                    if (end > i) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                            append(text.substring(i + 2, end))
                                        }
                                        i = end + 2
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                                    val delim = text[i]
                                    val end = text.indexOf(delim, i + 1)
                                    if (end > i && end > i + 1) {
                                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                            append(text.substring(i + 1, end))
                                        }
                                        i = end + 1
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                text[i] == '`' -> {
                                    val end = text.indexOf('`', i + 1)
                                    if (end > i) {
                                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                                            append(text.substring(i + 1, end))
                                        }
                                        i = end + 1
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                text.regionMatches(i, "nostr:", 0, 6, ignoreCase = true) ||
                                text.regionMatches(i, "nevent1", 0, 7, ignoreCase = true) ||
                                text.regionMatches(i, "nprofile1", 0, 9, ignoreCase = true) ||
                                text.regionMatches(i, "naddr1", 0, 6, ignoreCase = true) ||
                                text.regionMatches(i, "npub1", 0, 5, ignoreCase = true) ||
                                text.regionMatches(i, "note1", 0, 5, ignoreCase = true) -> {
                                    val match = nostrInlineRegex.find(text, i)
                                    if (match != null && match.range.first == i) {
                                        withStyle(SpanStyle(color = linkColor)) {
                                            append(shortenNostrEntity(match.value))
                                        }
                                        i = match.range.last + 1
                                    } else {
                                        append(text[i])
                                        i++
                                    }
                                }
                                else -> {
                                    append(text[i])
                                    i++
                                }
                            }
                        }
                    }
                    is InlineSegment.EmojiSegment -> {
                        pushStringAnnotation(tag = "emoji", annotation = segment.shortcode)
                        append(":${segment.shortcode}:")
                        pop()
                    }
                }
            }
        }
    }

    return FormattedInline(annotated, inlineContent)
}

private sealed interface InlineSegment {
    data class TextSegment(val text: String) : InlineSegment
    data class EmojiSegment(val shortcode: String, val url: String) : InlineSegment
}

private fun splitTextForEmojis(text: String, emojiMap: Map<String, String>): List<InlineSegment> {
    val result = mutableListOf<InlineSegment>()
    var lastEnd = 0
    for (match in emojiShortcodeRegex.findAll(text)) {
        val shortcode = match.groupValues[1]
        val url = emojiMap[shortcode] ?: continue
        if (match.range.first > lastEnd) {
            result.add(InlineSegment.TextSegment(text.substring(lastEnd, match.range.first)))
        }
        result.add(InlineSegment.EmojiSegment(shortcode, url))
        lastEnd = match.range.last + 1
    }
    if (lastEnd < text.length) {
        result.add(InlineSegment.TextSegment(text.substring(lastEnd)))
    } else if (lastEnd == 0 && result.isEmpty()) {
        result.add(InlineSegment.TextSegment(text))
    }
    return result
}

// -- Markdown block parser --

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Image(val url: String, val alt: String?) : MdBlock
    data class CodeBlock(val code: String, val language: String?) : MdBlock
    data class BlockQuote(val text: String) : MdBlock
    data class ListItem(val ordered: Boolean, val number: Int?, val text: String) : MdBlock
    data class TaskItem(val checked: Boolean, val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class Footnote(val id: String, val text: String) : MdBlock
    data class Math(val text: String) : MdBlock
    data object HorizontalRule : MdBlock
    data class NostrEmbed(val content: String) : MdBlock
}

private val imageLineRegex = Regex("""^!\[([^\]]*)]\((\S+?)(?:\s+"[^"]*")?\)\s*$""")
private val nostrEntityLineRegex = Regex("""^(?:nostr:)?(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]+$""", RegexOption.IGNORE_CASE)
private val nostrInlineRegex = Regex("""(?:nostr:)?(?:note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]+""", RegexOption.IGNORE_CASE)
private val emojiShortcodeRegex = Regex(""":([a-zA-Z0-9_-]+):""")

private fun shortenNostrEntity(entity: String): String {
    val bare = entity.removePrefix("nostr:").removePrefix("NOSTR:")
    return when {
        bare.startsWith("npub1", ignoreCase = true) || bare.startsWith("nprofile1", ignoreCase = true) ->
            "@${bare.take(10)}..."
        else -> "${bare.take(12)}..."
    }
}

private fun parseMarkdownBlocks(content: String): List<MdBlock> =
    parseArticleMarkdown(content).map { block ->
        when (block) {
            is ArticleMarkdownBlock.Heading -> MdBlock.Heading(block.level, block.text)
            is ArticleMarkdownBlock.Paragraph -> MdBlock.Paragraph(block.text)
            is ArticleMarkdownBlock.Image -> MdBlock.Image(block.url, block.alt)
            is ArticleMarkdownBlock.Code -> MdBlock.CodeBlock(block.text, block.language)
            is ArticleMarkdownBlock.Quote -> MdBlock.BlockQuote(block.text)
            is ArticleMarkdownBlock.ListItem -> MdBlock.ListItem(block.ordered, block.number, block.text)
            is ArticleMarkdownBlock.TaskItem -> MdBlock.TaskItem(block.checked, block.text)
            is ArticleMarkdownBlock.Table -> MdBlock.Table(block.headers, block.rows)
            is ArticleMarkdownBlock.NostrEmbed -> MdBlock.NostrEmbed(block.address)
            is ArticleMarkdownBlock.Footnote -> MdBlock.Footnote(block.id, block.text)
            is ArticleMarkdownBlock.Math -> MdBlock.Math(block.text)
            ArticleMarkdownBlock.Divider -> MdBlock.HorizontalRule
        }
    }

private fun formatArticleDate(epoch: Long): String {
    return java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
        .format(java.util.Date(epoch * 1000))
}
