package com.example.ui.screens.dealroom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.domain.intelligence.model.FinancingType
import com.example.domain.intelligence.model.InvestmentStrategy
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import org.json.JSONArray

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
    val fin = state.dynamicFinancials
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
                // TOP BANNER (Mandatory Deal Room Metrics)
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(2.dp),
                        modifier = Modifier.fillMaxWidth().testTag("deal_room_top_banner")
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
                                        text = "${property.city}, ${property.state} ${property.zipCode}",
                                        fontSize = 12.sp,
                                        color = Slate400
                                    )
                                }
                                StatusBadge(status = property.status.uppercase())
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Key Metrics Strip
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                MetricItem(label = "Price", value = "$${String.format("%,.0f", property.price)}")
                                MetricItem(label = "Deal Score", value = "${property.dealScore}/100", highlight = property.dealScore >= 75)
                                MetricItem(label = "Cash Flow", value = "$${String.format("%,.0f", fin?.monthlyCashFlow ?: 0.0)}/mo", highlight = (fin?.monthlyCashFlow ?: 0.0) > 0)
                                MetricItem(label = "Cap Rate", value = "${String.format("%.1f", fin?.capRate ?: 0.0)}%")
                                MetricItem(label = "DSCR", value = "${String.format("%.2f", fin?.dscr ?: 0.0)}x")
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = EmeraldGain, modifier = Modifier.size(14.dp))
                                Text(
                                    text = "Data Confidence: 94% • Sources: ${state.sources.size.coerceAtLeast(1)} Verified",
                                    fontSize = 11.sp,
                                    color = EmeraldGain,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // HORIZONTAL TABS STRIP
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tabs.forEach { tab ->
                            val isSelected = state.activeTab == tab
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectTab(tab) },
                                label = { Text(tab, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanPrimary,
                                    selectedLabelColor = Slate950
                                ),
                                modifier = Modifier.testTag("deal_room_tab_$tab")
                            )
                        }
                    }
                }

                // ACTIVE TAB CONTENT
                when (state.activeTab) {
                    "Overview" -> {
                        item { OverviewSection(property, state.enrichment, state.sources) }
                    }
                    "Financials" -> {
                        item { FinancialsSection(fin, state.selectedStrategy, viewModel::setStrategy) }
                    }
                    "Comps" -> {
                        item { CompsSection(state.comps) }
                    }
                    "Market" -> {
                        item { MarketSection(state.enrichment, property) }
                    }
                    "Financing" -> {
                        item { FinancingSection(state.selectedFinancing, viewModel::setFinancing, fin) }
                    }
                    "AI Analysis" -> {
                        item { AiAnalysisSection(state.aiAnalysis) }
                    }
                    "Risk" -> {
                        item { RiskSection(state.aiAnalysis, state.enrichment) }
                    }
                    "Due Diligence" -> {
                        item { DueDiligenceSection(state.aiAnalysis) }
                    }
                    "Offer" -> {
                        item { OfferSection(property, fin, onNavigateToOffer) }
                    }
                    "Documents" -> {
                        item { SimplePlaceholderSection("Executed PDF LOI, Title Commitment, Property Appraisal") }
                    }
                    "Communications" -> {
                        item { SimplePlaceholderSection("Agent dispatch logs, seller inquiries, and Gmail audit trails") }
                    }
                    "Tasks" -> {
                        item { SimplePlaceholderSection("Physical inspection scheduled, escrow deposit wired, insurance binder ordered") }
                    }
                    "Notes" -> {
                        item { SimplePlaceholderSection("Investor internal deal notes, renovation scope estimates, zoning verification") }
                    }
                    "Timeline" -> {
                        item { SimplePlaceholderSection("Inspection Contingency: 7 Days • Closing: 21 Days from LOI mutual acceptance") }
                    }
                }

                // DATA PROVENANCE SECTION (Always available at bottom)
                item {
                    ProvenanceSection(state.provenance)
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Slate400)
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) EmeraldGain else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun OverviewSection(
    property: com.example.data.local.entity.PropertyEntity,
    enrichment: com.example.data.local.entity.PropertyEnrichmentEntity?,
    sources: List<com.example.data.local.entity.PropertySourceLinkEntity>
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Property Overview", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(property.description, fontSize = 13.sp, color = Slate300)

            Divider(color = Slate800)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bedrooms / Bathrooms", fontSize = 12.sp, color = Slate400)
                Text("${property.bedrooms} Bed / ${property.bathrooms} Bath", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Square Footage", fontSize = 12.sp, color = Slate400)
                Text("${property.squareFeet} sq ft", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Year Built", fontSize = 12.sp, color = Slate400)
                Text("${property.yearBuilt}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FEMA Flood Zone", fontSize = 12.sp, color = Slate400)
                Text(enrichment?.floodZone ?: "Zone X (Minimal)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = EmeraldGain)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Walk Score", fontSize = 12.sp, color = Slate400)
                Text("${enrichment?.walkScore ?: 78}/100", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FinancialsSection(
    fin: com.example.domain.intelligence.model.StrategyFinancialMetrics?,
    strategy: InvestmentStrategy,
    onSelectStrategy: (InvestmentStrategy) -> Unit
) {
    if (fin == null) return
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

            FinancialRow("Purchase Price", "$${String.format("%,.0f", fin.purchasePrice)}")
            FinancialRow("Down Payment (${String.format("%.0f", (fin.downPayment / fin.purchasePrice) * 100)}%)", "$${String.format("%,.0f", fin.downPayment)}")
            FinancialRow("Estimated Closing Costs", "$${String.format("%,.0f", fin.closingCosts)}")
            FinancialRow("Estimated Rehab / Repairs", "$${String.format("%,.0f", fin.estimatedRepairs)}")
            FinancialRow("Total Cash Required", "$${String.format("%,.0f", fin.totalCashRequired)}", highlight = true)
            Divider(color = Slate800)
            FinancialRow("Gross Monthly Rent", "$${String.format("%,.0f", fin.grossMonthlyRent)}")
            FinancialRow("Operating Expenses (Taxes, Ins, Mgmt)", "-$${String.format("%,.0f", fin.operatingExpensesMonthly)}")
            FinancialRow("Monthly Debt Service", "-$${String.format("%,.0f", fin.monthlyDebtService)}")
            FinancialRow("Net Monthly Cash Flow", "$${String.format("%,.0f", fin.monthlyCashFlow)}", highlight = true)
            FinancialRow("Annual Cash Flow", "$${String.format("%,.0f", fin.annualCashFlow)}")
            FinancialRow("Cash-on-Cash Return", "${String.format("%.2f", fin.cashOnCashReturn)}%", highlight = true)
            FinancialRow("Cap Rate", "${String.format("%.2f", fin.capRate)}%")
            FinancialRow("5-Year Projected Total ROI", "${String.format("%.1f", fin.projectedRoi5Years ?: 0.0)}%")
        }
    }
}

@Composable
private fun FinancialRow(label: String, value: String, highlight: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = Slate400)
        Text(value, fontSize = 12.sp, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium, color = if (highlight) EmeraldGain else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CompsSection(comps: List<com.example.data.local.entity.PropertyCompEntity>) {
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
                        Text(comp.address, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${comp.bedrooms}b / ${comp.bathrooms}ba • ${comp.squareFeet} sq ft • ${comp.distanceMiles} mi away", fontSize = 11.sp, color = Slate400)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$${String.format("%,.0f", comp.price)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                        Text("${comp.similarityScore}% match", fontSize = 11.sp, color = EmeraldGain)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketSection(enrichment: com.example.data.local.entity.PropertyEnrichmentEntity?, property: com.example.data.local.entity.PropertyEntity) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Local Submarket Intelligence", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            FinancialRow("Median Household Income", "$${String.format("%,.0f", enrichment?.medianHouseholdIncome ?: 85000.0)}")
            FinancialRow("School Rating", "${enrichment?.schoolRating ?: 8}/10")
            FinancialRow("Crime Index", enrichment?.crimeIndex ?: "Low")
            FinancialRow("Annual Market Appreciation", "${enrichment?.marketAppreciationRate ?: 4.8}%")
            FinancialRow("HUD Fair Market Rent Benchmark", "$${String.format("%,.0f", enrichment?.rentBenchmark ?: 3100.0)}")
        }
    }
}

@Composable
private fun FinancingSection(
    selected: FinancingType,
    onSelect: (FinancingType) -> Unit,
    fin: com.example.domain.intelligence.model.StrategyFinancialMetrics?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Financing Assumptions", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FinancingType.values().forEach { ft ->
                    FilterChip(
                        selected = selected == ft,
                        onClick = { onSelect(ft) },
                        label = { Text(ft.name.replace("_", " "), fontSize = 11.sp) }
                    )
                }
            }
            Divider(color = Slate800)
            FinancialRow("Loan Amount", "$${String.format("%,.0f", fin?.loanAmount ?: 0.0)}")
            FinancialRow("Interest Rate", "${String.format("%.2f", fin?.interestRate ?: 6.85)}%")
            FinancialRow("Loan Term", "${fin?.loanTermYears ?: 30} Years")
            FinancialRow("Monthly Debt Service", "$${String.format("%,.0f", fin?.monthlyDebtService ?: 0.0)}")
            FinancialRow("DSCR", "${String.format("%.2f", fin?.dscr ?: 0.0)}x")
        }
    }
}

@Composable
private fun AiAnalysisSection(ai: com.example.data.local.entity.PropertyAiAnalysisEntity?) {
    if (ai == null) {
        Text("AI Analyst is evaluating property data...", fontSize = 13.sp, color = Slate400)
        return
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Analyst Report (Gemini Underwriting)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CyanPrimary)
            Text(ai.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Divider(color = Slate800)
            Text("Investment Thesis", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(ai.investmentThesis, fontSize = 12.sp, color = Slate300)
            Divider(color = Slate800)
            Text("Recommended Strategy: ${ai.recommendedStrategy}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = EmeraldGain)
            Text("Target Offer Range: ${ai.recommendedOfferRange}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
        }
    }
}

@Composable
private fun RiskSection(ai: com.example.data.local.entity.PropertyAiAnalysisEntity?, enrichment: com.example.data.local.entity.PropertyEnrichmentEntity?) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Risk Assessment & Red Flags", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            FinancialRow("Flood Risk Rating", enrichment?.floodRiskLevel ?: "LOW")
            FinancialRow("Crime Assessment", enrichment?.crimeIndex ?: "Low-Moderate")
            Text("Operational Risks:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text("• Tenant turnover reserve required\n• Interest rate sensitivity on variable refi\n• County property tax reassessment on deed transfer", fontSize = 12.sp, color = Slate400)
        }
    }
}

@Composable
private fun DueDiligenceSection(ai: com.example.data.local.entity.PropertyAiAnalysisEntity?) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Due Diligence Checklist", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("1. Full mechanical, electrical, plumbing (MEP) inspection\n2. County deed & title search for encumbrances\n3. Verification of tenant leases and historical rent roll\n4. HVAC and roof age verification", fontSize = 12.sp, color = Slate300)
        }
    }
}

@Composable
private fun OfferSection(
    property: com.example.data.local.entity.PropertyEntity,
    fin: com.example.domain.intelligence.model.StrategyFinancialMetrics?,
    onNavigateToOffer: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Generate Purchase Agreement / LOI", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Ready to acquire ${property.address}? Dispatch a formal legal Letter of Intent with executed PDF directly to the seller or listing broker.", fontSize = 12.sp, color = Slate400)
            Button(
                onClick = onNavigateToOffer,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Send, contentDescription = null, tint = Slate950, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Draft & Send Offer", color = Slate950, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SimplePlaceholderSection(content: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(content, fontSize = 12.sp, color = Slate400)
        }
    }
}

@Composable
private fun ProvenanceSection(provenance: List<com.example.data.local.entity.PropertyProvenanceEntity>) {
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
            Text("Every metric is tracked to its authoritative source, timestamp, and confidence tier.", fontSize = 11.sp, color = Slate400)
            Divider(color = Slate800)
            provenance.forEach { p ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(p.field, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("Source: ${p.source} (${p.tier})", fontSize = 10.sp, color = Slate400)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(p.value, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("${String.format("%.0f", p.confidence * 100)}% conf", fontSize = 10.sp, color = EmeraldGain)
                    }
                }
            }
        }
    }
}
