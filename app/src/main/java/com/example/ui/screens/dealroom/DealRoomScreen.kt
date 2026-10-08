package com.example.ui.screens.dealroom

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.*
import com.example.domain.intelligence.model.FinancingType
import com.example.domain.intelligence.model.InvestmentStrategy
import com.example.domain.intelligence.model.StrategyFinancialMetrics
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Deal Room rendering rules (enforced in this file)
 *  - Every value comes from stored state handed over by [DealRoomViewModel]. The screen never
 *    derives a number: no price * x heuristics, no `?: 78` style fallbacks.
 *  - A field without a stored, source-backed value is rendered as "UNKNOWN" or "Not available".
 *  - Values produced by the deterministic engine under its own assumptions are labelled as modelled
 *    outputs (see [ModelInputBasis]); they are never presented as sourced market facts.
 */

private const val NOT_AVAILABLE = "Not available"
private const val NOT_APPLICABLE = "Not applicable"
private const val UNKNOWN = "UNKNOWN"
private const val MODELLED_CAPTION = "modelled"

private val UNAVAILABLE_VALUES = setOf(NOT_AVAILABLE, NOT_APPLICABLE, UNKNOWN)

private fun isUnavailable(value: String): Boolean = UNAVAILABLE_VALUES.contains(value)

private fun money(value: Double?): String =
    if (value == null || !value.isFinite()) NOT_AVAILABLE else "$${String.format("%,.0f", value)}"

private fun monthlyMoney(value: Double?): String {
    val text = money(value)
    return if (isUnavailable(text)) text else "$text/mo"
}

private fun negativeMonthly(value: Double?): String {
    val text = monthlyMoney(value)
    return if (isUnavailable(text)) text else "-$text"
}

/** Only shows a value when a stored record actually carries one (0 / negative = not recorded). */
private fun storedMoney(value: Double?): String =
    if (value == null || value <= 0.0 || !value.isFinite()) NOT_AVAILABLE else "$${String.format("%,.0f", value)}"

private fun storedMonthlyMoney(value: Double?): String {
    val text = storedMoney(value)
    return if (isUnavailable(text)) text else "$text/mo"
}

private fun perSqFt(value: Double?): String =
    if (value == null || value <= 0.0 || !value.isFinite()) NOT_AVAILABLE else "$${String.format("%.2f", value)}/sq ft"

private fun pct(value: Double?, decimals: Int = 2): String =
    if (value == null || !value.isFinite()) NOT_AVAILABLE else "${String.format("%.${decimals}f", value)}%"

private fun storedPct(value: Double?, decimals: Int = 2): String =
    if (value == null || value <= 0.0 || !value.isFinite()) NOT_AVAILABLE else "${String.format("%.${decimals}f", value)}%"

private fun storedCount(value: Int): String = if (value > 0) "$value" else NOT_AVAILABLE

private fun storedDays(value: Int?): String {
    val text = if (value == null || value <= 0) NOT_AVAILABLE else "$value"
    return if (isUnavailable(text)) text else "$text days"
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.1f", value)

private fun rangeText(low: Double?, high: Double?): String =
    if (low == null || high == null || low <= 0.0 || high <= 0.0) NOT_AVAILABLE
    else "${money(low)} – ${money(high)}"

/** Ratios such as the gross rent multiplier are stored as plain multiples, not percentages. */
private fun storedRatio(value: Double): String =
    if (value <= 0.0 || !value.isFinite()) NOT_AVAILABLE else "${String.format("%.2f", value)}x"

private fun dateText(millis: Long?): String {
    if (millis == null || millis <= 0L) return NOT_AVAILABLE
    return SimpleDateFormat("MMM dd, yyyy", Locale.US).format(Date(millis))
}

private fun confidenceText(confidence: Double): String =
    if (confidence > 0.0 && confidence.isFinite()) "${String.format("%.0f", confidence)}% confidence"
    else "confidence not reported"

private fun trustText(confidence: Double): String = when {
    confidence > 1.0 -> "${String.format("%.0f", confidence)}% source trust"
    confidence > 0.0 -> "${String.format("%.0f", confidence * 100)}% source trust"
    else -> "source trust not reported"
}

/** Analyst confidence is stored on a 0.0 .. 1.0 scale; 0 means it was not reported. */
private fun analysisConfidenceText(confidence: Double): String {
    if (confidence <= 0.0 || !confidence.isFinite()) return NOT_AVAILABLE
    val percent = if (confidence <= 1.0) confidence * 100.0 else confidence
    return "${String.format("%.0f", percent)}%"
}

private fun fileSizeText(bytes: Long): String = when {
    bytes <= 0L -> NOT_AVAILABLE
    bytes >= 1_048_576L -> "${String.format("%.1f", bytes / 1_048_576.0)} MB"
    bytes >= 1024L -> "${String.format("%.0f", bytes / 1024.0)} KB"
    else -> "$bytes B"
}

/** Compact banner cells use UNKNOWN instead of the longer "Not available" string. */
private fun bannerText(value: String): String = if (isUnavailable(value)) UNKNOWN else value

private fun bannerDscr(fin: StrategyFinancialMetrics?): String = when {
    fin == null -> UNKNOWN
    fin.monthlyDebtService <= 0.0 -> "n/a"
    fin.dscr >= 99.0 -> UNKNOWN
    else -> "${String.format("%.2f", fin.dscr)}x"
}

private fun dscrText(fin: StrategyFinancialMetrics?): String = when {
    fin == null -> NOT_AVAILABLE
    fin.monthlyDebtService <= 0.0 -> NOT_APPLICABLE
    fin.dscr >= 99.0 -> NOT_AVAILABLE
    else -> "${String.format("%.2f", fin.dscr)}x"
}

private fun dealScoreText(property: PropertyEntity): String =
    if (property.dealScore > 0) "${property.dealScore}/100" else NOT_AVAILABLE

private fun downPaymentLabel(fin: StrategyFinancialMetrics): String {
    if (fin.purchasePrice <= 0.0) return "Down Payment"
    val share = fin.downPayment / fin.purchasePrice * 100.0
    return if (share.isFinite()) "Down Payment (${String.format("%.0f", share)}%)" else "Down Payment"
}

private fun bedroomsAndBathrooms(property: PropertyEntity): String {
    val parts = mutableListOf<String>()
    if (property.bedrooms > 0) parts += "${property.bedrooms} Bed"
    if (property.bathrooms > 0.0) parts += "${formatNumber(property.bathrooms)} Bath"
    return if (parts.isEmpty()) NOT_AVAILABLE else parts.joinToString(" / ")
}

private fun marketDemandText(value: String?): String {
    val text = value?.trim().orEmpty()
    return if (text.isEmpty() || text.equals("unknown", ignoreCase = true)) UNKNOWN else text
}

private fun intelligenceText(value: IntelligenceValue?): String {
    if (value == null) return UNKNOWN
    val text = value.textValue.trim()
    val numeric = value.numericValue
    val numericText = when {
        numeric == null || !numeric.isFinite() -> null
        numeric == numeric.toLong().toDouble() -> numeric.toLong().toString()
        else -> String.format("%.2f", numeric)
    }
    val unit = value.unit.trim()
    val parts = mutableListOf<String>()
    if (text.isNotEmpty()) parts += text
    if (numericText != null) parts += if (unit.isEmpty()) numericText else "$numericText $unit"
    return if (parts.isEmpty()) UNKNOWN else parts.joinToString(" · ")
}

private fun walkScoreText(value: IntelligenceValue?): String {
    if (value == null) return UNKNOWN
    val numeric = value.numericValue
    return when {
        numeric != null && numeric.isFinite() -> "${numeric.toInt()}/100"
        value.textValue.isNotBlank() -> value.textValue.trim()
        else -> UNKNOWN
    }
}

private fun intelligenceCaption(value: IntelligenceValue?): String? {
    if (value == null) return null
    val parts = mutableListOf(value.provider.ifBlank { "provider not recorded" })
    if (value.confidence > 0.0) parts += confidenceText(value.confidence)
    if (value.effectiveAt > 0L) parts += "effective ${dateText(value.effectiveAt)}"
    return parts.joinToString(" · ")
}

private fun compDetail(comp: PropertyCompEntity): String {
    val facts = mutableListOf<String>()
    if (comp.compBeds > 0) facts += "${comp.compBeds} bd"
    if (comp.compBaths > 0.0) facts += "${formatNumber(comp.compBaths)} ba"
    if (comp.compSqFt > 0) facts += "${String.format("%,d", comp.compSqFt)} sq ft"
    if (comp.distanceMiles > 0.0) facts += "${formatNumber(comp.distanceMiles)} mi away"
    val record = listOfNotNull(
        comp.compStatus.trim().takeIf { it.isNotEmpty() },
        comp.saleDate.trim().takeIf { it.isNotEmpty() }
    ).joinToString(" · ")
    val factsText = if (facts.isEmpty()) NOT_AVAILABLE else facts.joinToString(" • ")
    return if (record.isEmpty()) factsText else "$factsText • $record"
}

private fun compMatchText(comp: PropertyCompEntity): String =
    if (comp.similarityScore > 0.0) "${String.format("%.0f", comp.similarityScore)}% match" else "similarity not scored"

private fun jsonStringList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index, "").trim().takeIf { it.isNotEmpty() }
        }
    }.getOrDefault(emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealRoomScreen(
    propertyId: String,
    onBack: () -> Unit,
    onNavigateToOffer: () -> Unit,
    viewModel: DealRoomViewModel = viewModel()
) {
    LaunchedEffect(propertyId) {
        viewModel.loadDealRoom(propertyId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val property = state.property
    val tabs = listOf(
        "Overview", "Financials", "Comps", "Market", "Financing",
        "AI Analysis", "Risk", "Due Diligence", "Offer", "Documents",
        "Communications", "Tasks", "Notes", "Timeline"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "US Property Deal Room",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = property?.address ?: "Loading Deal Room...",
                            fontSize = 11.sp,
                            color = Slate400,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToOffer) {
                        Icon(Icons.Filled.Description, contentDescription = "Make Offer", tint = CyanPrimary)
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CyanPrimary)
            }
        } else if (property == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(state.errorMessage ?: "Property record not found.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    TopBanner(property = property, state = state)
                }

                item {
                    TabsStrip(
                        tabs = tabs,
                        selectedTab = state.activeTab,
                        onSelectTab = viewModel::selectTab
                    )
                }

                when (state.activeTab) {
                    "Overview" -> item {
                        OverviewSection(property = property, state = state)
                    }

                    "Financials" -> {
                        item {
                            FinancialsSection(
                                fin = state.dynamicFinancials,
                                strategy = state.selectedStrategy,
                                modelInputs = state.modelInputs,
                                rentSourced = state.rentSourced,
                                onSelectStrategy = viewModel::setStrategy
                            )
                        }
                        item {
                            CanonicalFactsSection(
                                financials = state.financials,
                                rentEstimate = state.rentEstimate,
                                taxRecord = state.taxRecord
                            )
                        }
                    }

                    "Comps" -> item {
                        CompsSection(comps = state.comps)
                    }

                    "Market" -> item {
                        MarketSection(state = state)
                    }

                    "Financing" -> item {
                        FinancingSection(
                            selected = state.selectedFinancing,
                            onSelect = viewModel::setFinancing,
                            fin = state.dynamicFinancials
                        )
                    }

                    "AI Analysis" -> item {
                        AiAnalysisSection(ai = state.aiAnalysis)
                    }

                    "Risk" -> item {
                        RiskSection(state = state)
                    }

                    "Due Diligence" -> item {
                        DueDiligenceSection(ai = state.aiAnalysis)
                    }

                    "Offer" -> item {
                        OfferSection(state = state, onNavigateToOffer = onNavigateToOffer)
                    }

                    "Documents" -> item {
                        DocumentsSection(state = state)
                    }

                    "Communications" -> item {
                        CommunicationsSection(state = state)
                    }

                    "Tasks" -> item {
                        EmptyStateCard(
                            title = "Tasks",
                            icon = Icons.Filled.FactCheck,
                            status = "NOT AVAILABLE",
                            message = "No task store is wired for the Deal Room yet.",
                            detail = "Inspection, escrow and insurance follow-ups will be listed here once a task layer stores them; nothing is displayed in the meantime."
                        )
                    }

                    "Notes" -> item {
                        EmptyStateCard(
                            title = "Notes",
                            icon = Icons.Filled.Description,
                            status = "NOT AVAILABLE",
                            message = "No per-deal notes store is wired for the Deal Room yet.",
                            detail = "Investor notes are not persisted per property in this build, so no note is displayed."
                        )
                    }

                    "Timeline" -> item {
                        TimelineSection(events = state.timeline)
                    }
                }

                // DATA PROVENANCE SECTION (Always available at bottom)
                item {
                    ProvenanceSection(
                        provenance = state.provenance,
                        intelligence = state.intelligence,
                        coverage = state.coverage
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBanner(property: PropertyEntity, state: DealRoomUiState) {
    val fin = state.dynamicFinancials
    val coverage = state.coverage
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deal_room_top_banner")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = property.address,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${property.city}, ${property.state} ${property.zipCode}".trim(),
                        fontSize = 12.sp,
                        color = Slate400
                    )
                }
                StatusBadge(status = property.status.uppercase())
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Key metrics strip: stored values or the deterministic engine's model output only.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricItem(
                    label = "Price",
                    value = bannerText(storedMoney(property.price)),
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Deal Score",
                    value = bannerText(dealScoreText(property)),
                    highlight = property.dealScore >= 75,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Cash Flow",
                    value = bannerText(monthlyMoney(fin?.monthlyCashFlow)),
                    highlight = fin?.monthlyCashFlow?.let { it > 0.0 } == true,
                    caption = if (fin == null) null else MODELLED_CAPTION,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "Cap Rate",
                    value = bannerText(pct(fin?.capRate, 1)),
                    caption = if (fin == null) null else MODELLED_CAPTION,
                    modifier = Modifier.weight(1f)
                )
                MetricItem(
                    label = "DSCR",
                    value = bannerDscr(fin),
                    caption = if (fin == null) null else MODELLED_CAPTION,
                    modifier = Modifier.weight(1f)
                )
            }

            if (fin != null && !state.rentSourced) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Modelled metrics use the engine's default rent: no stored rent figure exists for this property.",
                    fontSize = 10.sp,
                    color = AmberAccent
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = AmberAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Data confidence: UNKNOWN — no confidence-scored record is stored",
                    fontSize = 11.sp,
                    color = AmberAccent,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = if (coverage.trackedCount > 0 && coverage.sourcedCount == coverage.trackedCount) {
                        EmeraldGain
                    } else {
                        Slate400
                    },
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Source-backed coverage: ${coverage.sourcedCount} of ${coverage.trackedCount} tracked fields · ${state.provenance.size} source record(s)",
                    fontSize = 11.sp,
                    color = Slate300
                )
            }

            if (coverage.missingFields.isNotEmpty()) {
                Text(
                    text = "No stored value: ${coverage.missingFields.joinToString(", ")}",
                    fontSize = 10.sp,
                    color = Slate400
                )
            }
        }
    }
}

@Composable
private fun TabsStrip(
    tabs: List<String>,
    selectedTab: String,
    onSelectTab: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { tab ->
            val isSelected = selectedTab == tab
            FilterChip(
                selected = isSelected,
                onClick = { onSelectTab(tab) },
                label = {
                    Text(
                        tab,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary,
                    selectedLabelColor = Slate950
                ),
                modifier = Modifier.testTag("deal_room_tab_$tab")
            )
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    caption: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = label, fontSize = 10.sp, color = Slate400, maxLines = 1)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = when {
                isUnavailable(value) -> Slate400
                highlight -> EmeraldGain
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
        if (caption != null) {
            Text(text = caption, fontSize = 9.sp, color = Slate400, maxLines = 1)
        }
    }
}

@Composable
private fun FinancialRow(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    val unavailable = isUnavailable(value)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = Slate400)
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = if (highlight && !unavailable) FontWeight.Bold else FontWeight.Medium,
            color = when {
                unavailable -> Slate400
                highlight -> EmeraldGain
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun ModelInputRow(input: ModelInputBasis) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(input.label, fontSize = 12.sp, color = Slate400)
        Text(
            text = if (input.sourced) {
                "sourced · ${input.sourceDetail.ifBlank { "stored fact" }}"
            } else {
                "not sourced · ${input.notSourcedReason.ifBlank { "no stored fact" }}"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            color = if (input.sourced) EmeraldGain else AmberAccent,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp)
        )
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    icon: ImageVector,
    status: String,
    message: String,
    detail: String
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBadge(status = status)
                Text(message, fontSize = 12.sp, color = Slate300, modifier = Modifier.weight(1f))
            }
            Text(detail, fontSize = 11.sp, color = Slate400)
        }
    }
}

@Composable
private fun ClaimList(items: List<String>, emptyText: String) {
    if (items.isEmpty()) {
        Text(emptyText, fontSize = 12.sp, color = Slate400)
    } else {
        items.forEach { item ->
            Text("• $item", fontSize = 12.sp, color = Slate300)
        }
    }
}

@Composable
private fun SourceNote(text: String) {
    Text(text, fontSize = 10.sp, color = Slate400)
}

@Composable
private fun OverviewSection(property: PropertyEntity, state: DealRoomUiState) {
    val floodZone = state.intelligenceFor(PropertyEnrichmentType.FLOOD_RISK)
    val walkScore = state.intelligenceFor(PropertyEnrichmentType.WALK_SCORE)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Property Overview", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                property.description.takeIf { it.isNotBlank() }
                    ?: "No listing description was captured for this property.",
                fontSize = 13.sp,
                color = Slate300
            )

            Divider(color = Slate800)

            FinancialRow("Bedrooms / Bathrooms", bedroomsAndBathrooms(property))
            FinancialRow("Square Footage", if (property.squareFeet > 0) "${property.squareFeet} sq ft" else NOT_AVAILABLE)
            FinancialRow("Year Built", if (property.yearBuilt > 0) "${property.yearBuilt}" else NOT_AVAILABLE)
            FinancialRow("FEMA Flood Zone", intelligenceText(floodZone))
            intelligenceCaption(floodZone)?.let { SourceNote("Flood zone source: $it") }
            FinancialRow("Walk Score", walkScoreText(walkScore))
            intelligenceCaption(walkScore)?.let { SourceNote("Walk score source: $it") }

            Divider(color = Slate800)

            SourceNote(
                "Flood zone and walk score are displayed only when a stored provider value exists. " +
                    "No zone, score or rating is assumed when the provider record is absent."
            )
        }
    }
}

@Composable
private fun FinancialsSection(
    fin: StrategyFinancialMetrics?,
    strategy: InvestmentStrategy,
    modelInputs: List<ModelInputBasis>,
    rentSourced: Boolean,
    onSelectStrategy: (InvestmentStrategy) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Deterministic Financial Underwriting", fontWeight = FontWeight.Bold, fontSize = 15.sp)

            // Strategy Chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InvestmentStrategy.values().forEach { st ->
                    FilterChip(
                        selected = strategy == st,
                        onClick = { onSelectStrategy(st) },
                        label = { Text(st.name.replace("_", " "), fontSize = 11.sp) }
                    )
                }
            }

            Divider(color = Slate800)

            Text("Model input basis", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            modelInputs.forEach { input ->
                ModelInputRow(input)
            }
            SourceNote(
                "Closing costs, repair allowance, maintenance, management and the equity projection are " +
                    "also deterministic engine assumptions, not stored facts."
            )

            Divider(color = Slate800)

            if (fin == null) {
                Text(
                    "$NOT_AVAILABLE — the deterministic engine produced no model output for this property.",
                    fontSize = 12.sp,
                    color = Slate400
                )
            } else {
                Text("Purchase & Cash (engine model)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FinancialRow("Purchase Price", money(fin.purchasePrice))
                FinancialRow(downPaymentLabel(fin), money(fin.downPayment))
                FinancialRow("Closing Costs (engine assumption)", money(fin.closingCosts))
                FinancialRow("Rehab / Repair Allowance (engine model)", money(fin.estimatedRepairs))
                FinancialRow("Total Cash Required", money(fin.totalCashRequired), highlight = true)

                Divider(color = Slate800)

                Text("Income & Returns (engine model)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (!rentSourced) {
                    Text(
                        "No stored rent figure was available for this model, so the engine applied its own " +
                            "default rent. The rows below are assumption-based model output, not sourced market data.",
                        fontSize = 10.sp,
                        color = AmberAccent
                    )
                }
                FinancialRow("Gross Monthly Rent (model input)", monthlyMoney(fin.grossMonthlyRent))
                FinancialRow("Vacancy Loss (engine assumption)", negativeMonthly(fin.vacancyLossMonthly))
                FinancialRow("Effective Gross Income (model)", monthlyMoney(fin.effectiveGrossIncomeMonthly))
                FinancialRow("Operating Expenses (Taxes, Ins, Mgmt)", negativeMonthly(fin.operatingExpensesMonthly))
                FinancialRow("Monthly Debt Service", negativeMonthly(fin.monthlyDebtService))
                FinancialRow("Net Monthly Cash Flow", monthlyMoney(fin.monthlyCashFlow), highlight = true)
                FinancialRow("Annual Cash Flow", money(fin.annualCashFlow))
                FinancialRow("Cash-on-Cash Return", pct(fin.cashOnCashReturn, 2), highlight = true)
                FinancialRow("Cap Rate", pct(fin.capRate, 2))
                FinancialRow("5-Year Projected ROI (engine model)", pct(fin.projectedRoi5Years, 1))
            }
        }
    }
}

@Composable
private fun CanonicalFactsSection(
    financials: PropertyFinancialEntity?,
    rentEstimate: RentEstimateEntity?,
    taxRecord: TaxRecordEntity?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Canonical Asset Facts", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            SourceNote(
                "Stored facts about the asset (property_financials, rent_estimates, tax_records). " +
                    "The basis block above lists which of them the model actually consumed."
            )

            Divider(color = Slate800)

            if (financials == null) {
                Text(
                    "$NOT_AVAILABLE — no canonical asset facts are stored for this property. Asset facts " +
                        "are written by county-record and provider imports.",
                    fontSize = 12.sp,
                    color = Slate400
                )
            } else {
                FinancialRow("Record class", financials.dataSource.ifBlank { "unspecified" })
                FinancialRow("Assessed Value", storedMoney(financials.assessedValue))
                FinancialRow(
                    "Assessment Year",
                    if (financials.assessmentYear > 0) "${financials.assessmentYear}" else NOT_AVAILABLE
                )
                FinancialRow("Annual Property Tax", storedMoney(financials.annualPropertyTax))
                FinancialRow("Effective Tax Rate", storedPct(financials.effectiveTaxRatePct))
                FinancialRow("Annual Insurance", storedMoney(financials.annualInsurance))
                FinancialRow("HOA (Monthly)", storedMonthlyMoney(financials.hoaMonthly))
                FinancialRow("Capital Reserve (Monthly)", storedMonthlyMoney(financials.capitalReserveMonthly))
                FinancialRow("Maintenance Reserve (Monthly)", storedMonthlyMoney(financials.maintenanceReserveMonthly))
                FinancialRow("Rent Estimate (Monthly)", storedMonthlyMoney(financials.monthlyRentEstimate))
                FinancialRow(
                    "Rent Estimate Range",
                    rangeText(financials.rentEstimateLow, financials.rentEstimateHigh)
                )
                FinancialRow("Rent Confidence", storedPct(financials.rentConfidence, 0))
                FinancialRow("Market Rent per Sq Ft", perSqFt(financials.marketRentPerSqFt))
                FinancialRow("Gross Rent Multiplier", storedRatio(financials.grossRentMultiplier))
                FinancialRow("Gross Yield", storedPct(financials.grossYieldPct))
                FinancialRow("Operating Expense Ratio", storedPct(financials.operatingExpenseRatioPct))
                FinancialRow("Vacancy Rate", storedPct(financials.vacancyRatePct))
                FinancialRow("Last Updated", dateText(financials.lastUpdatedAt))
            }

            if (rentEstimate != null) {
                Divider(color = Slate800)
                Text("Rent AVM Snapshot (rent_estimates)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FinancialRow("Estimated Rent", storedMonthlyMoney(rentEstimate.estimatedRent))
                FinancialRow("Rent Range", rangeText(rentEstimate.rentRangeLow, rentEstimate.rentRangeHigh))
                FinancialRow("Rent Confidence", storedPct(rentEstimate.rentConfidenceScore, 0))
                FinancialRow("Gross Yield", storedPct(rentEstimate.grossYield))
            }

            if (taxRecord != null) {
                Divider(color = Slate800)
                Text("County Tax Record (tax_records)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                FinancialRow("Annual Tax Amount", storedMoney(taxRecord.annualTaxAmount))
                FinancialRow("Assessed Value", storedMoney(taxRecord.assessedValue))
                FinancialRow(
                    "Assessment Year",
                    if (taxRecord.assessmentYear > 0) "${taxRecord.assessmentYear}" else NOT_AVAILABLE
                )
                FinancialRow(
                    "Delinquency",
                    if (taxRecord.taxDelinquent) "Recorded delinquent" else "No recorded delinquency"
                )
            }
        }
    }
}

@Composable
private fun CompsSection(comps: List<PropertyCompEntity>) {
    if (comps.isEmpty()) {
        EmptyStateCard(
            title = "Comparable Market Sales",
            icon = Icons.Filled.SearchOff,
            status = "NOT AVAILABLE",
            message = "No comparable sales are stored for this property.",
            detail = "Comparables are written by MLS / county import runs. No placeholder or simulated comps are shown."
        )
    } else {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Comparable Market Sales (${comps.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                comps.forEach { comp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(comp.compAddress, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(compDetail(comp), fontSize = 11.sp, color = Slate400)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(storedMoney(comp.compPrice), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                            Text(compMatchText(comp), fontSize = 11.sp, color = EmeraldGain)
                        }
                    }
                }
                SourceNote("Comp rows are stored records; missing fields are labelled rather than estimated.")
            }
        }
    }
}

@Composable
private fun MarketSection(state: DealRoomUiState) {
    val market = state.marketData
    val rent = state.rentEstimate
    val school = state.intelligenceFor(PropertyEnrichmentType.SCHOOL_RATING)
    val crime = state.intelligenceFor(PropertyEnrichmentType.CRIME_INDEX)
    val walkScore = state.intelligenceFor(PropertyEnrichmentType.WALK_SCORE)
    val floodZone = state.intelligenceFor(PropertyEnrichmentType.FLOOD_RISK)
    val marketTrend = state.intelligenceFor(PropertyEnrichmentType.MARKET_TREND)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Local Submarket Intelligence", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            SourceNote("Stored provider and import values only. Rows without a stored value stay UNKNOWN.")

            Divider(color = Slate800)

            FinancialRow("Estimated Market Value", storedMoney(market?.estimatedValue))
            FinancialRow("Median Area Price", storedMoney(market?.medianAreaPrice))
            FinancialRow("Area Appreciation (Annual)", storedPct(market?.neighborhoodAppreciationRate, 1))
            FinancialRow("Average Days on Market", storedDays(market?.averageDaysOnMarket))
            FinancialRow("Area Price per Sq Ft", perSqFt(market?.pricePerSqFt))
            FinancialRow("Market Demand", marketDemandText(market?.marketDemand))
            FinancialRow("Median Household Income", NOT_AVAILABLE)
            FinancialRow("School Rating", intelligenceText(school))
            intelligenceCaption(school)?.let { SourceNote("School source: $it") }
            FinancialRow("Crime Index", intelligenceText(crime))
            intelligenceCaption(crime)?.let { SourceNote("Crime source: $it") }
            FinancialRow("Walk Score", walkScoreText(walkScore))
            FinancialRow("FEMA Flood Zone", intelligenceText(floodZone))
            FinancialRow("Market Trend (provider)", intelligenceText(marketTrend))
            intelligenceCaption(marketTrend)?.let { SourceNote("Market trend source: $it") }
            FinancialRow("Rent Benchmark (AVM)", storedMonthlyMoney(rent?.estimatedRent))
            if (rent != null && (rent.rentRangeLow > 0.0 || rent.rentRangeHigh > 0.0)) {
                FinancialRow("Rent Benchmark Range", rangeText(rent.rentRangeLow, rent.rentRangeHigh))
            }
            if (rent != null && rent.rentConfidenceScore > 0.0) {
                FinancialRow("Rent Benchmark Confidence", storedPct(rent.rentConfidenceScore, 0))
            }
            FinancialRow("HUD Fair Market Rent", NOT_AVAILABLE)

            Divider(color = Slate800)

            SourceNote(
                "Median household income requires a census/ACS provider and HUD Fair Market Rent requires a " +
                    "HUD provider; neither is configured, so both stay Not available instead of showing an estimate."
            )
        }
    }
}

@Composable
private fun FinancingSection(
    selected: FinancingType,
    onSelect: (FinancingType) -> Unit,
    fin: StrategyFinancialMetrics?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Financing Assumptions", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FinancingType.values().forEach { ft ->
                    FilterChip(
                        selected = selected == ft,
                        onClick = { onSelect(ft) },
                        label = { Text(ft.name.replace("_", " "), fontSize = 11.sp) }
                    )
                }
            }
            Divider(color = Slate800)

            if (fin == null) {
                Text(
                    "$NOT_AVAILABLE — no financing model output is stored for this property.",
                    fontSize = 12.sp,
                    color = Slate400
                )
            } else {
                FinancialRow("Loan Amount", money(fin.loanAmount))
                FinancialRow("Interest Rate", pct(fin.interestRate, 2))
                FinancialRow("Loan Term", "${fin.loanTermYears} Years")
                FinancialRow("Monthly Debt Service", monthlyMoney(fin.monthlyDebtService))
                FinancialRow("DSCR", dscrText(fin))
                FinancialRow("Loan-to-Value", pct(fin.ltv, 1))
            }

            Divider(color = Slate800)

            SourceNote(
                "Rate, term and down payment are the deterministic engine's assumptions for the selected " +
                    "financing product. They are not lender quotes, and no rate is displayed for a product " +
                    "that has no model output."
            )
        }
    }
}

@Composable
private fun AiAnalysisSection(ai: PropertyAiAnalysisEntity?) {
    if (ai == null) {
        EmptyStateCard(
            title = "AI Analyst Report",
            icon = Icons.Filled.SmartToy,
            status = "NOT AVAILABLE",
            message = "No validated analyst report is stored for this property.",
            detail = "A report is shown only when a stored, evidence-backed analysis exists. The Deal Room " +
                "does not generate or simulate an analyst summary."
        )
    } else {
        val strengths = remember(ai.strengthsJson) { jsonStringList(ai.strengthsJson) }
        val weaknesses = remember(ai.weaknessesJson) { jsonStringList(ai.weaknessesJson) }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AI Analyst Report", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CyanPrimary)
                Text(ai.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                Divider(color = Slate800)
                Text("Investment Thesis", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(ai.investmentThesis, fontSize = 12.sp, color = Slate300)
                Divider(color = Slate800)
                Text("Supported Strengths", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = EmeraldGain)
                ClaimList(strengths, "No strengths were supplied by the stored analysis.")
                Text("Weaknesses", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AmberAccent)
                ClaimList(weaknesses, "No weaknesses were supplied by the stored analysis.")
                Divider(color = Slate800)
                FinancialRow("Recommended Strategy", ai.recommendedStrategy.ifBlank { NOT_AVAILABLE })
                FinancialRow("Target Offer Range", ai.recommendedOfferRange.ifBlank { NOT_AVAILABLE })
                FinancialRow("Analyst Confidence", analysisConfidenceText(ai.confidence))
                FinancialRow("Analyzed At", dateText(ai.analyzedAt))
                SourceNote("Analyst output is qualitative. Financial calculations stay with the deterministic engine.")
            }
        }
    }
}

@Composable
private fun RiskSection(state: DealRoomUiState) {
    val floodZone = state.intelligenceFor(PropertyEnrichmentType.FLOOD_RISK)
    val crime = state.intelligenceFor(PropertyEnrichmentType.CRIME_INDEX)
    val taxRecord = state.taxRecord
    val ai = state.aiAnalysis
    val redFlags: List<String> =
        if (ai == null) emptyList() else remember(ai.redFlagsJson) { jsonStringList(ai.redFlagsJson) }
    val risks: List<String> =
        if (ai == null) emptyList() else remember(ai.risksJson) { jsonStringList(ai.risksJson) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Risk Assessment & Red Flags", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            SourceNote("Stored provider records and stored analysis only.")

            Divider(color = Slate800)

            FinancialRow("FEMA Flood Zone", intelligenceText(floodZone))
            intelligenceCaption(floodZone)?.let { SourceNote("Flood source: $it") }
            FinancialRow("Crime Index", intelligenceText(crime))
            intelligenceCaption(crime)?.let { SourceNote("Crime source: $it") }
            FinancialRow(
                "Property Tax Delinquency",
                if (taxRecord == null) {
                    NOT_AVAILABLE
                } else if (taxRecord.taxDelinquent) {
                    "Recorded delinquent"
                } else {
                    "No recorded delinquency"
                }
            )

            Divider(color = Slate800)

            Text("Stored Analysis Red Flags", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CrimsonAlert)
            ClaimList(
                redFlags,
                "No red flags are stored for this property. A red flag is only listed when a stored analysis supplies it."
            )
            Text("Stored Analysis Risks", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AmberAccent)
            ClaimList(risks, "No risk items are stored for this property.")

            Divider(color = Slate800)

            SourceNote(
                "No flood rating, crime rating or operational risk list is inferred here. Insurance, tenant and " +
                    "rate-sensitivity notes appear only when a provider record or stored analysis carries them."
            )
        }
    }
}

@Composable
private fun DueDiligenceSection(ai: PropertyAiAnalysisEntity?) {
    if (ai == null) {
        EmptyStateCard(
            title = "Due Diligence Checklist",
            icon = Icons.Filled.FactCheck,
            status = "NOT AVAILABLE",
            message = "No source-backed due diligence items are stored for this property.",
            detail = "Checklist items appear only when a stored analysis supplies them; no generic inspection " +
                "list is displayed in their place."
        )
    } else {
        val dueDiligence = remember(ai.dueDiligenceJson) { jsonStringList(ai.dueDiligenceJson) }
        val questions = remember(ai.questionsForSellerJson) { jsonStringList(ai.questionsForSellerJson) }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Due Diligence Checklist", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                ClaimList(dueDiligence, "No due diligence items were supplied by the stored analysis.")
                Divider(color = Slate800)
                Text("Questions for the Seller", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                ClaimList(questions, "No seller questions were supplied by the stored analysis.")
                FinancialRow("Analyzed At", dateText(ai.analyzedAt))
            }
        }
    }
}

@Composable
private fun OfferSection(state: DealRoomUiState, onNavigateToOffer: () -> Unit) {
    val offer = state.offer
    val offerDocuments = state.offerDocuments
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Purchase Agreement / LOI", fontWeight = FontWeight.Bold, fontSize = 15.sp)

            if (offer == null) {
                Text(
                    "No offer is recorded for this property. Nothing is generated or sent until you create one " +
                        "in the offer workflow.",
                    fontSize = 12.sp,
                    color = Slate400
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recorded offer", fontSize = 12.sp, color = Slate400)
                    StatusBadge(status = offer.status.uppercase())
                }
                FinancialRow("Offer Price", storedMoney(offer.offerPrice))
                FinancialRow("Earnest Money", storedMoney(offer.earnestMoney))
                FinancialRow("Inspection Period", "${offer.inspectionPeriodDays} days")
                FinancialRow("Closing Period", "${offer.closingPeriodDays} days")
                FinancialRow("Expiration", offer.expirationDate.ifBlank { NOT_AVAILABLE })
                FinancialRow("Created", dateText(offer.createdAt))
                FinancialRow("Sent", dateText(offer.sentAt))
                FinancialRow("Stored Documents", storedCount(offerDocuments.size))
            }

            Button(
                onClick = onNavigateToOffer,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Slate950, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (offer == null) "Draft & Send Offer" else "Open Offer Workflow",
                    color = Slate950,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DocumentsSection(state: DealRoomUiState) {
    val offer = state.offer
    val documents = state.offerDocuments
    if (documents.isEmpty()) {
        EmptyStateCard(
            title = "Documents",
            icon = Icons.Filled.PictureAsPdf,
            status = "NOT AVAILABLE",
            message = if (offer == null) {
                "No documents are stored for this property."
            } else {
                "No documents are stored for the recorded offer."
            },
            detail = "Generated LOI / PDF artifacts appear here from stored offer documents. The Deal Room does " +
                "not display expected or sample documents."
        )
    } else {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stored Documents (${documents.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                documents.forEach { document ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(document.fileName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(fileSizeText(document.fileSizeBytes), fontSize = 11.sp, color = Slate400)
                        }
                        Text(dateText(document.createdAt), fontSize = 11.sp, color = Slate400)
                    }
                }
                SourceNote("Rows are read from stored offer document records.")
            }
        }
    }
}

@Composable
private fun CommunicationsSection(state: DealRoomUiState) {
    val offer = state.offer
    val events = state.offerAuditEvents
    if (offer == null) {
        EmptyStateCard(
            title = "Communications",
            icon = Icons.Filled.Forum,
            status = "NOT AVAILABLE",
            message = "No offer or dispatch record is stored for this property.",
            detail = "Agent dispatch logs and email audit trails appear here once an offer exists; no sample " +
                "messages are shown."
        )
    } else if (events.isEmpty()) {
        EmptyStateCard(
            title = "Communications",
            icon = Icons.Filled.Forum,
            status = "NOT AVAILABLE",
            message = "No audit events are stored for offer ${offer.id}.",
            detail = "Dispatch and delivery events are written by the offer workflow. Nothing is listed until " +
                "an event is recorded."
        )
    } else {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Stored Audit Trail (${events.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                events.forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.eventType.replace("_", " "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            event.details?.takeIf { it.isNotBlank() }?.let { details ->
                                Text(details, fontSize = 11.sp, color = Slate400)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(event.status ?: UNKNOWN, fontSize = 11.sp, color = Slate300)
                            Text(dateText(event.timestamp), fontSize = 10.sp, color = Slate400)
                        }
                    }
                }
                SourceNote("Audit rows exclude message bodies and tokens; only event metadata is stored.")
            }
        }
    }
}

@Composable
private fun TimelineSection(events: List<DealRoomEvent>) {
    if (events.isEmpty()) {
        EmptyStateCard(
            title = "Timeline",
            icon = Icons.Filled.HistoryEdu,
            status = "NOT AVAILABLE",
            message = "No timestamped records are stored for this property.",
            detail = "The timeline is assembled from stored import, enrichment and offer events only. No " +
                "contingency or closing schedule is assumed."
        )
    } else {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Recorded Timeline (${events.size} events)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                events.forEach { event ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            if (event.detail.isNotBlank()) {
                                Text(event.detail, fontSize = 11.sp, color = Slate400)
                            }
                        }
                        Text(dateText(event.timestamp), fontSize = 11.sp, color = Slate400)
                    }
                }
                SourceNote("Every entry comes from a stored row; the timeline is capped at the most recent 25 events.")
            }
        }
    }
}

@Composable
private fun ProvenanceSection(
    provenance: List<PropertyProvenanceEntity>,
    intelligence: List<IntelligenceValue>,
    coverage: FieldCoverage
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.HistoryEdu, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(16.dp))
                Text("Data Provenance & Source Audit Trail", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            SourceNote(
                "Each row below is a stored record: source, ingestion method, source trust and timestamps. " +
                    "Nothing on this screen is estimated in the UI layer."
            )

            Divider(color = Slate800)

            if (provenance.isEmpty()) {
                Text("No source records are stored for this property.", fontSize = 12.sp, color = Slate400)
            } else {
                provenance.forEach { record ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("External ID: ${record.externalId}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Source: ${record.sourceId} (${record.ingestionMethod})",
                                fontSize = 10.sp,
                                color = Slate400
                            )
                            Text("Fetched: ${dateText(record.fetchedAt)}", fontSize = 10.sp, color = Slate400)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (record.isPrimaryForProperty) "PRIMARY SOURCE" else "SECONDARY SOURCE",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (record.isPrimaryForProperty) CyanPrimary else Slate400
                            )
                            Text(
                                text = trustText(record.confidence),
                                fontSize = 10.sp,
                                color = if (record.confidence > 0.0) EmeraldGain else Slate400
                            )
                        }
                    }
                }
            }

            if (intelligence.isNotEmpty()) {
                Divider(color = Slate800)
                Text("Stored Intelligence Values", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                intelligence.forEach { value ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(value.enrichmentType.replace("_", " "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${value.provider.ifBlank { "provider not recorded" }} · ${confidenceText(value.confidence)}",
                                fontSize = 10.sp,
                                color = Slate400
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(intelligenceText(value), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("effective ${dateText(value.effectiveAt)}", fontSize = 10.sp, color = Slate400)
                        }
                    }
                }
            }

            Divider(color = Slate800)

            Text(
                "Tracked field coverage: ${coverage.sourcedCount} of ${coverage.trackedCount} fields have a stored value.",
                fontSize = 11.sp,
                color = Slate300
            )
            if (coverage.missingFields.isNotEmpty()) {
                Text("No stored value: ${coverage.missingFields.joinToString(", ")}", fontSize = 11.sp, color = Slate400)
            }
        }
    }
}
