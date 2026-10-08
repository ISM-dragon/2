package com.example.ui.screens.property

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.BookmarkBorder
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
import com.example.ui.components.StatusBadge
import com.example.ui.components.TrendAppreciationCanvas
import com.example.ui.components.CashFlowTrendCanvas
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertyDetailScreen(
    propertyId: String,
    onBack: () -> Unit,
    onNavigateToAnalyzer: (String) -> Unit,
    onNavigateToAi: (String) -> Unit,
    onNavigateToOffers: () -> Unit,
    viewModel: PropertyDetailViewModel = viewModel()
) {
    LaunchedEffect(propertyId) {
        viewModel.loadProperty(propertyId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val property = state.property
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(property?.address ?: "Property Details", maxLines = 1, fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier.testTag("edit_property_button")
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit Property", tint = Slate400)
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.testTag("delete_property_button")
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete Property", tint = CrimsonAlert)
                    }
                    IconButton(onClick = { viewModel.toggleSave() }) {
                        Icon(
                            imageVector = if (property?.isSaved == true) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Save",
                            tint = if (property?.isSaved == true) AmberAccent else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // Persistent Underwriting & Offer Action Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onNavigateToAnalyzer(propertyId) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("deep_analyze_button")
                    ) {
                        Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Analyzer", fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = { onNavigateToAi(propertyId) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ai_advisor_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AI Advisor", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            viewModel.draftOffer {
                                onNavigateToOffers()
                            }
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(48.dp)
                            .testTag("generate_offer_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                    ) {
                        if (state.isDraftingOffer) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Slate950)
                        } else {
                            Icon(Icons.Filled.Description, contentDescription = null, tint = Slate950, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Draft Offer", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (property == null) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyanPrimary)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Image Gallery
                item {
                    val allImages = if (state.images.isNotEmpty()) state.images.map { it.imageUrl } else listOf(property.primaryImageUrl)
                    var selectedImgIndex by remember { mutableStateOf(0) }

                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(230.dp)
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            AsyncImage(
                                model = allImages[selectedImgIndex],
                                contentDescription = property.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Status & Source pill overlay
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                StatusBadge(status = property.sourceType)
                                if (property.dealScore > 0) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (property.dealScore >= 80) EmeraldGain else AmberAccent)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text("Score: ${property.dealScore}/100", color = Slate950, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Thumbnails
                        if (allImages.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(allImages.indices.toList()) { idx ->
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { selectedImgIndex = idx }
                                    ) {
                                        AsyncImage(
                                            model = allImages[idx],
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Header Information
                item {
                    Column {
                        Text(
                            text = "$${String.format("%,.0f", property.price)}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = property.address,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${property.city}, ${property.state} ${property.zipCode}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate400
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Specs Grid
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            SpecItem(label = "Beds", value = "${property.bedrooms}")
                            SpecItem(label = "Baths", value = "${property.bathrooms}")
                            SpecItem(label = "Area", value = "${property.squareFeet} sqft")
                            SpecItem(label = "Year", value = "${property.yearBuilt}")
                            SpecItem(label = "Type", value = property.propertyType)
                        }
                    }
                }

                // Financial Snapshot
                item {
                    val fin = state.financialAnalysis
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "FINANCIAL SNAPSHOT",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate200
                                )
                                TextButton(onClick = { onNavigateToAnalyzer(propertyId) }) {
                                    Text("Full Model →", color = CyanPrimary, fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FinancialMetricBox(
                                    label = "Net Cash Flow",
                                    value = "$${String.format("%,.0f", fin?.monthlyCashFlow ?: 450.0)}/mo",
                                    color = EmeraldGain,
                                    modifier = Modifier.weight(1f)
                                )
                                FinancialMetricBox(
                                    label = "Cap Rate",
                                    value = "${String.format("%.2f", fin?.capRate ?: 7.8)}%",
                                    color = CyanPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FinancialMetricBox(
                                    label = "Cash-on-Cash",
                                    value = "${String.format("%.2f", fin?.cashOnCashReturn ?: 9.2)}%",
                                    color = EmeraldGain,
                                    modifier = Modifier.weight(1f)
                                )
                                FinancialMetricBox(
                                    label = "DSCR Coverage",
                                    value = String.format("%.2f", fin?.dscr ?: 1.35),
                                    color = AmberAccent,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Market & Rental Insights
                item {
                    val market = state.marketData
                    val rent = state.rentEstimate
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "MARKET & RENT BENCHMARKS",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Slate200
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Estimated Market Value:", color = Slate400, fontSize = 13.sp)
                                Text("$${String.format("%,.0f", market?.estimatedValue ?: property.price)}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Median Rent Benchmark:", color = Slate400, fontSize = 13.sp)
                                Text("$${String.format("%,.0f", rent?.estimatedRent ?: 2400.0)}/mo", fontWeight = FontWeight.Bold, color = EmeraldGain, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Area Appreciation Rate:", color = Slate400, fontSize = 13.sp)
                                Text("+${market?.neighborhoodAppreciationRate ?: 5.4}% / yr", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Avg Days on Market:", color = Slate400, fontSize = 13.sp)
                                Text("${market?.averageDaysOnMarket ?: 22} Days", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // 5-Year Equity Projection Chart
                item {
                    TrendAppreciationCanvas(purchasePrice = property.price)
                }

                // 5-Year Cumulative Cash Flow Trend Chart
                item {
                    CashFlowTrendCanvas(monthlyCashFlow = state.financialAnalysis?.monthlyCashFlow ?: 450.0)
                }

                // Description
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("INVESTMENT SUMMARY", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(property.description, style = MaterialTheme.typography.bodyMedium, color = Slate300)
                        }
                    }
                }

                // Sales History
                if (state.salesHistory.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("SALES & PRICE HISTORY", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(10.dp))
                                state.salesHistory.forEach { h ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("${h.date} • ${h.event}", fontSize = 12.sp, color = Slate400)
                                        Text("$${String.format("%,.0f", h.price)}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Comparable Properties
                if (state.comps.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("COMPARABLE SALES (COMPS)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(10.dp))
                                state.comps.forEach { comp ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(comp.compAddress, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            Text("${comp.compBeds}b/${comp.compBaths}ba • ${comp.compSqFt} sqft • ${comp.distanceMiles} mi", fontSize = 11.sp, color = Slate400)
                                        }
                                        Text("$${String.format("%,.0f", comp.compPrice)}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CyanPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Edit Dialog
        if (showEditDialog && property != null) {
            EditPropertyDialog(
                property = property,
                onDismiss = { showEditDialog = false },
                onSave = { title, addr, price, beds, baths, sqft, type ->
                    viewModel.updateProperty(title, addr, price, beds, baths, sqft, type)
                    showEditDialog = false
                }
            )
        }

        // Delete Dialog
        if (showDeleteDialog && property != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("Delete Property", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to permanently delete \"${property.address}\" and all its associated analysis and records?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            viewModel.deleteProperty(onDeleted = onBack)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                    ) {
                        Text("Delete", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun EditPropertyDialog(
    property: com.example.data.local.entity.PropertyEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, addr: String, price: Double, beds: Int, baths: Double, sqft: Int, type: String) -> Unit
) {
    var title by remember { mutableStateOf(property.title) }
    var address by remember { mutableStateOf(property.address) }
    var priceText by remember { mutableStateOf(property.price.toInt().toString()) }
    var bedsText by remember { mutableStateOf(property.bedrooms.toString()) }
    var bathsText by remember { mutableStateOf(property.bathrooms.toString()) }
    var sqftText by remember { mutableStateOf(property.squareFeet.toString()) }
    var propertyType by remember { mutableStateOf(property.propertyType) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Property", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; if (title.isBlank()) title = it },
                        label = { Text("Address") },
                        modifier = Modifier.fillMaxWidth().testTag("edit_prop_address"),
                        singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Price ($)") },
                        modifier = Modifier.fillMaxWidth().testTag("edit_prop_price"),
                        singleLine = true
                    )
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
                            label = { Text("SqFt") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = propertyType,
                        onValueChange = { propertyType = it },
                        label = { Text("Property Type") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull() ?: property.price
                    val b = bedsText.toIntOrNull() ?: property.bedrooms
                    val ba = bathsText.toDoubleOrNull() ?: property.bathrooms
                    val s = sqftText.toIntOrNull() ?: property.squareFeet
                    onSave(title.ifBlank { address }, address, p, b, ba, s, propertyType)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Save Changes", color = Slate950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SpecItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = Slate400)
        Text(text = value, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FinancialMetricBox(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Slate900)
            .padding(12.dp)
    ) {
        Column {
            Text(label, fontSize = 11.sp, color = Slate400)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}
