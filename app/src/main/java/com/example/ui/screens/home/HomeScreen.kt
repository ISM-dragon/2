package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.PropertyEntity
import com.example.domain.automation.AutomationStatus
import com.example.domain.market.MarketCity
import com.example.domain.market.MarketScannerService
import com.example.ui.components.DealComparisonDialog
import com.example.ui.components.MetricCard
import com.example.ui.components.PropertyCard
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToDiscover: () -> Unit,
    onNavigateToPropertyDetail: (String) -> Unit,
    onNavigateToAnalyzer: (String) -> Unit,
    onNavigateToAi: (String) -> Unit,
    onNavigateToOffers: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToUrlParser: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showAddPropertyDialog by remember { mutableStateOf(false) }
    var showScanMarketDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyanDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Apartment, contentDescription = null, tint = Slate950, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("REAL ESTATE AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            Text("Autonomous Deal Finder & Underwriter", fontSize = 11.sp, color = Slate400)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("settings_button")) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Slate200)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Quick Acquisition Action Strip
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChipButton(
                        icon = Icons.Filled.Add,
                        label = "Add Property",
                        sublabel = "إضافة عقار",
                        accentColor = CyanPrimary,
                        modifier = Modifier.weight(1f),
                        onClick = { showAddPropertyDialog = true }
                    )
                    ActionChipButton(
                        icon = Icons.Filled.TravelExplore,
                        label = "Scan Market",
                        sublabel = "فحص السوق",
                        accentColor = EmeraldGain,
                        modifier = Modifier.weight(1f),
                        onClick = { showScanMarketDialog = true }
                    )
                    ActionChipButton(
                        icon = Icons.Filled.Link,
                        label = "Analyze URL",
                        sublabel = "تحليل رابط",
                        accentColor = AmberAccent,
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToUrlParser
                    )
                }
            }

            // High Visibility STOP AUTOMATION Kill Switch
            item {
                Card(
                    modifier = Modifier
                        .testTag("kill_switch_card")
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.automationStatus == AutomationStatus.RUNNING || state.automationStatus == AutomationStatus.SCANNING || state.automationStatus == AutomationStatus.ANALYZING) CrimsonAlert else Slate800
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (state.automationStatus == AutomationStatus.RUNNING || state.automationStatus == AutomationStatus.SCANNING || state.automationStatus == AutomationStatus.ANALYZING) EmeraldGain else CrimsonAlert
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "STATUS: ${state.automationStatus.name}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (state.automationStatus == AutomationStatus.RUNNING || state.automationStatus == AutomationStatus.SCANNING || state.automationStatus == AutomationStatus.ANALYZING) {
                                    Button(
                                        onClick = { viewModel.stopAutomation() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        modifier = Modifier.testTag("stop_automation_button")
                                    ) {
                                        Icon(Icons.Filled.Stop, contentDescription = null, tint = CrimsonAlert)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("STOP", color = CrimsonAlert, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = { viewModel.startAutomation() },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                        modifier = Modifier.testTag("start_automation_button")
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Slate950)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("START AUTO", color = Slate950, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.currentTaskDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )

                        if (state.persistentState.killSwitchEngaged) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "KILL SWITCH ENGAGED - ${state.persistentState.killSwitchReason ?: "automation halted"}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Button(
                                onClick = { viewModel.clearKillSwitch() },
                                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGain),
                                modifier = Modifier.testTag("clear_kill_switch_button")
                            ) {
                                Icon(Icons.Filled.LockOpen, contentDescription = null, tint = Slate950, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CLEAR KILL SWITCH", color = Slate950, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Quick Metrics Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ACQUISITION PIPELINE METRICS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate400,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    if (state.selectedComparisonIds.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.openComparison(true) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = Slate950, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compare (${state.selectedComparisonIds.size})", color = Slate950, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Metrics Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            label = "Properties Tracked",
                            value = "${state.propertiesScanned}",
                            icon = Icons.Filled.Radar,
                            accentColor = CyanPrimary,
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToDiscover
                        )
                        MetricCard(
                            label = "Underwritten",
                            value = "${state.propertiesAnalyzed}",
                            icon = Icons.Filled.Calculate,
                            accentColor = AmberAccent,
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToDiscover
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            label = "Qualified Deals",
                            value = "${state.qualifiedDeals}",
                            icon = Icons.Filled.Verified,
                            accentColor = EmeraldGain,
                            subValue = "Score >= 70",
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToDiscover
                        )
                        MetricCard(
                            label = "Offers Generated",
                            value = "${state.offersGenerated}",
                            icon = Icons.Filled.Description,
                            accentColor = CyanPrimary,
                            subValue = "${state.offersSent} Dispatched",
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToOffers
                        )
                    }
                }
            }

            // Top Opportunities Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TOP OPPORTUNITIES",
                            style = MaterialTheme.typography.labelMedium,
                            color = Slate400,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Deterministic financial returns & deal scores",
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                    TextButton(onClick = onNavigateToDiscover) {
                        Text("View All (${state.propertiesScanned})", fontSize = 12.sp, color = CyanPrimary)
                    }
                }
            }

            // Opportunities Cards OR Rich Empty State
            if (state.recentOpportunities.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(CyanPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Apartment, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(28.dp))
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Your Deal Pipeline is Ready",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "ابدأ بإضافة عقارك الحقيقي أو فحص السوق أو تحليل رابط أي إعلان عقاري:",
                                fontSize = 12.sp,
                                color = Slate400,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showScanMarketDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGain),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.TravelExplore, contentDescription = null, tint = Slate950, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Scan", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = { showAddPropertyDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, tint = Slate950, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Manual", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Button(
                                    onClick = onNavigateToUrlParser,
                                    colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                    modifier = Modifier.weight(1.1f)
                                ) {
                                    Icon(Icons.Filled.Link, contentDescription = null, tint = Slate950, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Parse URL", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                items(state.recentOpportunities, key = { it.id }) { property ->
                    val isSelected = state.selectedComparisonIds.contains(property.id)
                    Column {
                        PropertyCard(
                            property = property,
                            onSelect = { onNavigateToPropertyDetail(property.id) },
                            onAnalyze = { onNavigateToAnalyzer(property.id) },
                            onDraftOffer = { onNavigateToPropertyDetail(property.id) },
                            onToggleSave = { viewModel.toggleSave(property.id, property.isSaved) }
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleComparisonSelection(property.id) }
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleComparisonSelection(property.id) },
                                    colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                                )
                                Text("Select to Compare", fontSize = 11.sp, color = if (isSelected) CyanPrimary else Slate400)
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onNavigateToAnalyzer(property.id) }) {
                                    Text("Underwrite", fontSize = 11.sp, color = AmberAccent)
                                }
                                TextButton(onClick = { viewModel.deleteProperty(property.id) }) {
                                    Text("Delete", fontSize = 11.sp, color = CrimsonAlert)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialog 1: Add Property Dialog
    if (showAddPropertyDialog) {
        HomeAddPropertyDialog(
            onDismiss = { showAddPropertyDialog = false },
            onAdd = { addr, city, state, zip, price, type, beds, baths, sqft, rent, source ->
                viewModel.addCustomProperty(addr, city, state, zip, price, type, beds, baths, sqft, rent, source)
                showAddPropertyDialog = false
            }
        )
    }

    // Dialog 2: Scan Real Market Dialog
    if (showScanMarketDialog) {
        ScanMarketDialog(
            onDismiss = { showScanMarketDialog = false },
            onScan = { city, type, count ->
                viewModel.scanMarketCity(city, type, count)
                showScanMarketDialog = false
            }
        )
    }

    // Dialog 3: Analyze URL Dialog
    if (showUrlDialog) {
        AnalyzeUrlDialog(
            isAnalyzing = state.isAnalyzingUrl,
            statusMessage = state.urlStatusMessage,
            onDismiss = { showUrlDialog = false },
            onAnalyze = { url ->
                viewModel.analyzeListingUrl(url) { propId ->
                    showUrlDialog = false
                    onNavigateToAnalyzer(propId)
                }
            }
        )
    }

    // Dialog 4: Side-by-Side Deal Comparison Dialog
    if (state.isComparisonOpen) {
        val selectedProperties = state.recentOpportunities.filter { state.selectedComparisonIds.contains(it.id) }
        DealComparisonDialog(
            properties = selectedProperties,
            onDismiss = { viewModel.openComparison(false) },
            onSelectProperty = { propId ->
                viewModel.openComparison(false)
                onNavigateToAnalyzer(propId)
            }
        )
    }
}

@Composable
private fun ActionChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    sublabel: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = label, tint = accentColor, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(sublabel, fontSize = 9.sp, color = Slate400, maxLines = 1)
        }
    }
}

@Composable
private fun ScanMarketDialog(
    onDismiss: () -> Unit,
    onScan: (MarketCity, String, Int) -> Unit
) {
    val markets = MarketScannerService.SUPPORTED_MARKETS
    var selectedMarket by remember { mutableStateOf(markets.first()) }
    var selectedType by remember { mutableStateOf("Single Family") }
    var count by remember { mutableStateOf(3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TravelExplore, contentDescription = null, tint = EmeraldGain)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Real Estate Market", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Select Target City Market:", style = MaterialTheme.typography.labelMedium, color = Slate400)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                items(markets) { city ->
                    val isSelected = selectedMarket.id == city.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedMarket = city },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) EmeraldGain.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${city.name}, ${city.stateOrCountry}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Median: $${String.format("%,.0f", city.medianPrice)} | Avg Rent: $${String.format("%,.0f", city.averageRent)}", fontSize = 11.sp, color = Slate400)
                            }
                            if (isSelected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = EmeraldGain, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Property Category:", style = MaterialTheme.typography.labelMedium, color = Slate400)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Single Family", "Condo / Apartment", "Multi-Family").forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onScan(selectedMarket, selectedType, count) },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldGain)
            ) {
                Text("Scan ${selectedMarket.name}", color = Slate950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AnalyzeUrlDialog(
    isAnalyzing: Boolean,
    statusMessage: String?,
    onDismiss: () -> Unit,
    onAnalyze: (String) -> Unit
) {
    var urlText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = AmberAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Analyze Property URL", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Paste a listing link from Zillow, Redfin, Realtor.com, or MLS to underwrite immediately:",
                    fontSize = 12.sp,
                    color = Slate400
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    placeholder = { Text("https://www.zillow.com/homedetails/...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (statusMessage != null) {
                    Text(statusMessage, fontSize = 11.sp, color = AmberAccent)
                }
                if (isAnalyzing) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = AmberAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Resolving listing & financial metadata...", fontSize = 11.sp, color = Slate400)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAnalyze(urlText) },
                enabled = urlText.isNotBlank() && !isAnalyzing,
                colors = ButtonDefaults.buttonColors(containerColor = AmberAccent)
            ) {
                Text("Underwrite Deal", color = Slate950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HomeAddPropertyDialog(
    onDismiss: () -> Unit,
    onAdd: (
        addr: String,
        city: String,
        state: String,
        zip: String,
        price: Double,
        type: String,
        beds: Int,
        baths: Double,
        sqft: Int,
        rent: Double,
        source: String
    ) -> Unit
) {
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("Austin") }
    var stateCode by remember { mutableStateOf("TX") }
    var zipCode by remember { mutableStateOf("78701") }
    var priceText by remember { mutableStateOf("385000") }
    var rentText by remember { mutableStateOf("2600") }
    var propertyType by remember { mutableStateOf("Single Family") }
    var bedsText by remember { mutableStateOf("3") }
    var bathsText by remember { mutableStateOf("2.0") }
    var sqftText by remember { mutableStateOf("1650") }
    var sourceType by remember { mutableStateOf("ON_MARKET") }

    val calculatedPrice = priceText.toDoubleOrNull() ?: 0.0
    val calculatedRent = rentText.toDoubleOrNull() ?: 0.0
    val grossYield = if (calculatedPrice > 0) (calculatedRent * 12.0 / calculatedPrice) * 100.0 else 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Real Estate Property", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Quick yield indicator banner
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CyanPrimary.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Estimated Gross Yield:", fontSize = 11.sp, color = CyanPrimary)
                            Text("${String.format("%.2f", grossYield)}%", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = CyanPrimary)
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Street Address (العنوان)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 742 Evergreen Terrace") },
                        singleLine = true
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("City (المدينة)") },
                            modifier = Modifier.weight(2f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = stateCode,
                            onValueChange = { stateCode = it },
                            label = { Text("State") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = zipCode,
                            onValueChange = { zipCode = it },
                            label = { Text("Zip") },
                            modifier = Modifier.weight(1.5f),
                            singleLine = true
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = { Text("Price ($)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = rentText,
                            onValueChange = { rentText = it },
                            label = { Text("Est. Rent ($/mo)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bedsText,
                            onValueChange = { bedsText = it },
                            label = { Text("Beds") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = bathsText,
                            onValueChange = { bathsText = it },
                            label = { Text("Baths") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = sqftText,
                            onValueChange = { sqftText = it },
                            label = { Text("Sq Ft") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                item {
                    Text("Source Market:", style = MaterialTheme.typography.labelMedium, color = Slate400)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sourceType == "ON_MARKET",
                            onClick = { sourceType = "ON_MARKET" },
                            label = { Text("On-Market MLS") }
                        )
                        FilterChip(
                            selected = sourceType == "OFF_MARKET",
                            onClick = { sourceType = "OFF_MARKET" },
                            label = { Text("Off-Market Wholesale") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull() ?: 350000.0
                    val r = rentText.toDoubleOrNull() ?: (p * 0.0075)
                    val b = bedsText.toIntOrNull() ?: 3
                    val ba = bathsText.toDoubleOrNull() ?: 2.0
                    val s = sqftText.toIntOrNull() ?: 1600
                    val finalAddr = address.ifBlank { "405 Main Street" }
                    onAdd(finalAddr, city.ifBlank { "Austin" }, stateCode.ifBlank { "TX" }, zipCode.ifBlank { "78701" }, p, propertyType, b, ba, s, r, sourceType)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Save & Underwrite", color = Slate950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
