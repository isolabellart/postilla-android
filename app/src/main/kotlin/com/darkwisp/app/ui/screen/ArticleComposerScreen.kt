package com.darkwisp.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.darkwisp.app.R
import com.darkwisp.app.nostr.NostrEvent
import com.darkwisp.app.nostr.NostrSigner
import com.darkwisp.app.relay.RelayPool
import com.darkwisp.app.repo.EventRepository
import com.darkwisp.app.viewmodel.ArticleComposerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ArticleComposerScreen(
    viewModel: ArticleComposerViewModel,
    relayPool: RelayPool,
    signer: NostrSigner?,
    eventRepository: EventRepository,
    includeClientTag: Boolean,
    onBack: () -> Unit,
    onPublished: (NostrEvent) -> Unit
) {
    val title by viewModel.title.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val coverImage by viewModel.coverImage.collectAsState()
    val body by viewModel.body.collectAsState()
    val hashtags by viewModel.hashtags.collectAsState()
    val publishing by viewModel.publishing.collectAsState()
    val uploadingCover by viewModel.uploadingCover.collectAsState()
    val error by viewModel.error.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val context = LocalContext.current
    var bodyFieldValue by remember { mutableStateOf(TextFieldValue(text = body)) }
    LaunchedEffect(body) {
        if (bodyFieldValue.text != body) {
            bodyFieldValue = TextFieldValue(text = body, selection = TextRange(body.length))
        }
    }
    val onMarkdownFormat: (ArticleMarkdownFormat) -> Unit = { format ->
        val edit = formatArticleMarkdownSelection(
            text = bodyFieldValue.text,
            selectionStart = bodyFieldValue.selection.start,
            selectionEnd = bodyFieldValue.selection.end,
            format = format
        )
        bodyFieldValue = TextFieldValue(
            text = edit.text,
            selection = TextRange(edit.selectionStart, edit.selectionEnd)
        )
        viewModel.updateBody(edit.text)
    }
    val bodyBringIntoViewRequester = remember { BringIntoViewRequester() }
    var bodyEditorFocused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(bodyEditorFocused, imeBottom, preview) {
        if (bodyEditorFocused && imeBottom > 0 && !preview) {
            bodyBringIntoViewRequester.bringIntoView()
        }
    }

    val coverPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.uploadCover(uri, context.contentResolver, signer)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.article_compose_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.togglePreview() }) {
                        Icon(
                            if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                            contentDescription = stringResource(if (preview) R.string.article_edit else R.string.article_preview)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.saveDraft(relayPool, signer, includeClientTag)
                        onBack()
                    },
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.article_save_draft)) }
                Button(
                    onClick = {
                        viewModel.publish(relayPool, signer, eventRepository, includeClientTag, onPublished)
                    },
                    enabled = !publishing && !uploadingCover && title.isNotBlank() && body.isNotBlank() && signer != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (publishing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.article_publishing))
                    } else {
                        Text(stringResource(R.string.article_publish))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(2.dp))
            if (!preview) {
                OutlinedTextField(
                    value = title,
                    onValueChange = viewModel::updateTitle,
                    label = { Text(stringResource(R.string.article_title_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !publishing
                )
                OutlinedTextField(
                    value = summary,
                    onValueChange = viewModel::updateSummary,
                    label = { Text(stringResource(R.string.article_summary_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    enabled = !publishing
                )
                Text(stringResource(R.string.article_cover), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (coverImage.isNotBlank()) {
                    Box {
                        AsyncImage(
                            model = coverImage,
                            contentDescription = stringResource(R.string.article_cover),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
                        )
                        IconButton(onClick = viewModel::removeCover, modifier = Modifier.align(Alignment.TopEnd)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.article_remove_cover))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = !uploadingCover,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                    ) {
                        if (uploadingCover) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.article_uploading_cover))
                        } else {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.article_choose_cover))
                        }
                    }
                }
                OutlinedTextField(
                    value = hashtags,
                    onValueChange = viewModel::updateHashtags,
                    label = { Text(stringResource(R.string.article_hashtags_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !publishing
                )
                OutlinedTextField(
                    value = bodyFieldValue,
                    onValueChange = { value ->
                        bodyFieldValue = value
                        if (value.text != body) viewModel.updateBody(value.text)
                    },
                    label = { Text(stringResource(R.string.article_body_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(bodyBringIntoViewRequester)
                        .onFocusChanged { bodyEditorFocused = it.isFocused },
                    minLines = 4,
                    maxLines = 12,
                    enabled = !publishing
                )
                // Keep formatting actions below the editor so Android's selection popup
                // does not cover the Markdown controls on the first line.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        ArticleMarkdownFormat.HEADING_2 to "H2",
                        ArticleMarkdownFormat.BOLD to "B",
                        ArticleMarkdownFormat.ITALIC to "I",
                        ArticleMarkdownFormat.QUOTE to "Quote",
                        ArticleMarkdownFormat.LIST to "List"
                    ).forEach { (format, label) ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable(enabled = !publishing) { onMarkdownFormat(format) }
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            } else {
                Text(title.ifBlank { stringResource(R.string.article_title_hint) }, style = MaterialTheme.typography.headlineMedium)
                if (coverImage.isNotBlank()) {
                    AsyncImage(
                        model = coverImage,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    )
                }
                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleMarkdownPreview(markdown = body, modifier = Modifier.fillMaxWidth())
                if (hashtags.isNotBlank()) {
                    Text(hashtags.split(',', ' ').filter { it.isNotBlank() }.joinToString(" ") {
                        if (it.startsWith("#")) it else "#$it"
                    }, color = MaterialTheme.colorScheme.primary)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
