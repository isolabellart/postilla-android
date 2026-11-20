package com.darkwisp.app.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.darkwisp.app.R
import com.darkwisp.app.nostr.Nip99

@Composable
fun MarketplaceAvailabilityBadge(
    availability: Nip99.Availability,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val (label, containerColor, contentColor) = when (availability.state) {
        Nip99.AvailabilityState.AVAILABLE -> Triple(
            stringResource(R.string.marketplace_availability_available_count, availability.stock ?: 0),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        Nip99.AvailabilityState.SOLD_OUT -> Triple(
            stringResource(R.string.marketplace_availability_sold_out),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        Nip99.AvailabilityState.UNKNOWN -> Triple(
            stringResource(R.string.marketplace_availability_unknown),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(modifier = modifier, color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 9.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}


/** Visibility marker shared by seller previews and the editor preview. */
@Composable
fun MarketplaceVisibilityBadge(
    visibility: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val (label, containerColor, contentColor) = when (visibility) {
        "hidden" -> Triple(stringResource(R.string.marketplace_hidden),
            MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        "pre-order" -> Triple(stringResource(R.string.marketplace_pre_order),
            MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else -> Triple(stringResource(R.string.marketplace_on_sale),
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
    Surface(modifier = modifier, color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 9.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}

/** Digital / physical item marker. */
@Composable
fun MarketplaceFormatBadge(
    format: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val label = if (format == "physical") stringResource(R.string.marketplace_physical)
        else stringResource(R.string.marketplace_digital)
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 9.dp,
                vertical = if (compact) 3.dp else 4.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}
