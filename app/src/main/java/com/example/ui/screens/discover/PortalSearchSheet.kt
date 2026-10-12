package com.example.ui.screens.discover

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.domain.intelligence.scrape.ContactAction
import com.example.domain.intelligence.scrape.ContactChannel
import com.example.domain.intelligence.scrape.ListingIntent
import com.example.domain.intelligence.scrape.ListingScrapeFailure
import com.example.domain.intelligence.scrape.ScrapedListing
import com.example.ui.theme.*

/**
 * Live portal search sheet.
 *
 * The user types a market, the app fetches the portal's own search page, and every listing that came
 * back is shown with the contact routes the portal published. Two things are stated openly in the UI
 * rather than hidden, because both are the difference between a tool people trust and one they
 * uninstall:
 *
 *  * retrieval is opt-in, and the switch says what it does (portal terms, robots.txt),
 *  * when the portal refuses — bot wall, rate limit, layout change — the user sees the reason and the
 *    fix, not an empty list that reads as "nothing for sale here".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalSearchSheet(
    onDismiss: () -> Unit,
    viewModel: DiscoverViewModel
) {
    val state by viewModel.portalSearchState.collectAsStateWithLifecycle()
    val settings by viewModel.portalScrapeSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Live portal search",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Slate50
            )
            Text(
                "Fetches public listing pages from Zillow for the market you type.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate400
            )

            Spacer(modifier = Modifier.height(14.dp))
            ConsentCard(
                consented = settings.userConsented,
                robotsAdvisory = settings.robotsCompliance == com.example.domain.intelligence.scrape.RobotsCompliance.ADVISORY,
                onConsentChange = viewModel::setPortalConsent,
                onRobotsAdvisoryChange = viewModel::setPortalRobotsAdvisory
            )

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = state.location,
                onValueChange = viewModel::setPortalLocation,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("portal_search_location"),
                placeholder = { Text("City, neighbourhood or ZIP — e.g. Austin, TX") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Slate400) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListingIntent.entries.forEach { intent ->
                    FilterChip(
                        selected = state.intent == intent,
                        onClick = { viewModel.setPortalIntent(intent) },
                        label = { Text(intent.label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.minPrice,
                    onValueChange = viewModel::setPortalMinPrice,
                    placeholder = { Text("Min price") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.maxPrice,
                    onValueChange = viewModel::setPortalMaxPrice,
                    placeholder = { Text("Max price") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Minimum beds", style = MaterialTheme.typography.labelMedium, color = Slate400)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 3, 4, 5).forEach { beds ->
                    FilterChip(
                        selected = state.minBeds == beds,
                        onClick = { viewModel.setPortalMinBeds(beds) },
                        label = { Text(if (beds == 0) "Any" else "$beds+", fontSize = 11.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = viewModel::runPortalSearch,
                enabled = state.canSearch && settings.userConsented,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("portal_search_run"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary, contentColor = Slate950)
            ) {
                if (state.isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Slate950
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (state.progressTotalPages > 0) {
                            "Reading page ${state.progressPage}/${state.progressTotalPages}…"
                        } else {
                            "Reading portal…"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search Zillow", fontWeight = FontWeight.SemiBold)
                }
            }

            val result = state.result
            if (result != null) {
                Spacer(modifier = Modifier.height(16.dp))

                result.failure?.let { failure ->
                    PortalFailureCard(failure = failure, searchUrl = result.searchUrl, context = context)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                result.warnings.forEach { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberAccent,
                        fontSize = 11.sp
                    )
                }

                if (result.listings.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${result.listings.size} listings · ${result.parseStrategy ?: "unknown"} reader",
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate400
                        )
                        TextButton(onClick = viewModel::clearPortalResult) { Text("Clear") }
                    }

                    result.listings.forEach { listing ->
                        PortalListingCard(
                            listing = listing,
                            contact = result.contactFor(listing),
                            isSaved = state.savedListingIds.contains(savedIdFor(listing)),
                            onContact = { action -> launchContactAction(context, action) },
                            onSave = { viewModel.savePortalListing(listing) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentCard(
    consented: Boolean,
    robotsAdvisory: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onRobotsAdvisoryChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Direct portal retrieval", fontWeight = FontWeight.SemiBold, color = Slate50)
                    Text(
                        "Sends search requests straight to Zillow. Restricted by Zillow's terms of service " +
                            "and by robots.txt; a licensed feed or partner API is the compliant route for " +
                            "production use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = consented,
                    onCheckedChange = onConsentChange,
                    modifier = Modifier.testTag("portal_consent_switch")
                )
            }
            if (consented) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Ignore robots.txt (advisory mode)", fontWeight = FontWeight.SemiBold, color = Slate200)
                        Text(
                            if (robotsAdvisory) {
                                "robots.txt is read and reported, then ignored. That is your decision to make."
                            } else {
                                "Off: a Disallow rule stops the search before any request is sent."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Slate400,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = robotsAdvisory,
                        onCheckedChange = onRobotsAdvisoryChange,
                        modifier = Modifier.testTag("portal_robots_switch")
                    )
                }
            }
        }
    }
}

@Composable
private fun PortalFailureCard(failure: ListingScrapeFailure, searchUrl: String, context: Context) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("portal_failure_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    failure.kind.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.SemiBold,
                    color = Slate50
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(failure.message, style = MaterialTheme.typography.bodyMedium, color = Slate200)
            failure.remediation?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Slate400, fontSize = 11.sp)
            }
            failure.detail?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Slate600, fontSize = 10.sp)
            }
            if (searchUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                SuggestionChip(
                    onClick = {
                        launchContactAction(
                            context,
                            ContactAction(ContactChannel.LISTING_PAGE, searchUrl, null)
                        )
                    },
                    label = { Text("Open this search in a browser", fontSize = 11.sp) },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }
    }
}

@Composable
private fun PortalListingCard(
    listing: ScrapedListing,
    contact: com.example.domain.intelligence.scrape.ListingContact?,
    isSaved: Boolean,
    onContact: (ContactAction) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("portal_listing_${listing.externalId ?: listing.detailUrl.hashCode()}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate800)
            ) {
                listing.photos.firstOrNull()?.let { photo ->
                    AsyncImage(
                        model = photo,
                        contentDescription = listing.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        listing.priceLabel ?: listing.price?.let { formatUsd(it) } ?: "Price on request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanPrimary
                    )
                    Text(
                        listing.statusText ?: listing.status.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate400
                    )
                }
                Text(
                    listOfNotNull(
                        listing.bedrooms?.let { "$it bd" },
                        listing.bathrooms?.let { "${trimZero(it)} ba" },
                        listing.livingAreaSqFt?.let { "${it} sqft" }
                    ).joinToString(" · ").ifBlank { listing.propertyType.orEmpty() },
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate200
                )
                Text(
                    listing.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate300,
                    maxLines = 2,
                    fontSize = 12.sp
                )
                if (listing.isBrokerPaidPlacement) {
                    Text("Broker-paid placement", style = MaterialTheme.typography.labelSmall, color = AmberAccent, fontSize = 10.sp)
                }
            }
        }

        Column(modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
            HorizontalDivider(color = Slate800)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                buildString {
                    append(contact?.personName ?: "No agent name published")
                    contact?.brokerage?.let { append(" · $it") }
                    contact?.phone?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = Slate200
            )
            contact?.missing?.forEach { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = Slate400, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            // Contact routes wrap: on a narrow screen five chips do not fit one line.
            ContactChipRow(items = contact?.actions ?: emptyList(), onAction = onContact)

            Spacer(modifier = Modifier.height(6.dp))
            // SuggestionChip has no icon slot, so the icon lives inside the label.
            SuggestionChip(
                onClick = onSave,
                enabled = !isSaved,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isSaved) Icons.Filled.BookmarkAdded else Icons.Filled.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isSaved) "Saved to deals" else "Save to my deals", fontSize = 11.sp)
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = if (isSaved) Slate800 else EmeraldDark,
                    labelColor = Slate50
                )
            )
        }
    }
}

/** Poor man's flow layout: chunks of three chips per row, which is enough for the routes we emit. */
@Composable
private fun ContactChipRow(items: List<ContactAction>, onAction: (ContactAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { action ->
                    SuggestionChip(
                        onClick = { onAction(action) },
                        enabled = action.isAvailable,
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    iconFor(action.channel),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(action.channel.label, fontSize = 11.sp)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Slate800,
                            labelColor = if (action.isAvailable) Slate100 else Slate600
                        )
                    )
                }
            }
        }
    }
}

private fun iconFor(channel: ContactChannel) = when (channel) {
    ContactChannel.CALL -> Icons.Filled.Call
    ContactChannel.SMS -> Icons.Filled.Sms
    ContactChannel.EMAIL -> Icons.Filled.Email
    ContactChannel.WHATSAPP -> Icons.Filled.Chat
    ContactChannel.PORTAL_MESSAGE -> Icons.Filled.Forum
    ContactChannel.LISTING_PAGE -> Icons.Filled.OpenInNew
}

/** Hands the route to the user's own dialler/messaging/browser. Nothing is sent by the app itself. */
private fun launchContactAction(context: Context, action: ContactAction) {
    if (!action.isAvailable) return
    val intent = when (action.channel) {
        // ACTION_DIAL, not ACTION_CALL: it opens the dialler pre-filled and needs no permission.
        ContactChannel.CALL -> Intent(Intent.ACTION_DIAL, Uri.parse(action.uri))
        ContactChannel.SMS, ContactChannel.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse(action.uri))
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(action.uri))
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app available for ${action.channel.label.lowercase()}", Toast.LENGTH_SHORT).show()
    }
}

private fun savedIdFor(listing: ScrapedListing): String =
    listing.externalId?.takeIf { it.isNotBlank() }?.let { "prop-zil-$it" }
        ?: "prop-zil-${kotlin.math.abs(listing.detailUrl.hashCode()).toString(36)}"

private fun formatUsd(value: Double): String =
    "$" + value.toLong().toString().reversed().chunked(3).joinToString(",").reversed()

private fun trimZero(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
