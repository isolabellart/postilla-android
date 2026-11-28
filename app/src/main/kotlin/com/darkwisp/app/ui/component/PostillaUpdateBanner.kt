package com.darkwisp.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R

@Composable
fun PostillaUpdateBanner(
    versionName: String,
    releaseNotes: String = "",
    isDownloading: Boolean,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit
) {
    var notesExpanded by remember(versionName) { mutableStateOf(false) }
    val visibleNotes = remember(releaseNotes) { formatPostillaReleaseNotesForDisplay(releaseNotes) }
    Card(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = stringResource(R.string.postilla_update_available, versionName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.postilla_update_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (visibleNotes.isNotBlank()) {
                Text(
                    text = stringResource(R.string.postilla_update_whats_new),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = visibleNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (notesExpanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                )
                val lineCount = visibleNotes.lineSequence().count()
                if (lineCount > 5) {
                    TextButton(onClick = { notesExpanded = !notesExpanded }) {
                        Text(
                            stringResource(
                                if (notesExpanded) R.string.postilla_update_show_less
                                else R.string.postilla_update_show_more,
                            ),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onLater, enabled = !isDownloading) {
                    Text(stringResource(R.string.postilla_update_later))
                }
                Button(onClick = onUpdateNow, enabled = !isDownloading) {
                    Text(
                        if (isDownloading) stringResource(R.string.postilla_update_downloading)
                        else stringResource(R.string.postilla_update_now)
                    )
                }
            }
        }
    }
}


/** Render release Markdown as compact readable text without treating it as executable markup. */
internal fun formatPostillaReleaseNotesForDisplay(value: String): String =
    value.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            when {
                line.isBlank() || line == "---" -> null
                line.startsWith("#") -> line.trimStart('#').trim()
                line.startsWith("- ") -> "• " + line.removePrefix("- ")
                else -> line.replace("**", "").replace("`", "")
            }
        }
        .joinToString("\n")
