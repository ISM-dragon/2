package com.example.ui.screens.urlintelligence

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.urlintelligence.HybridPropertyTransport
import com.example.urlintelligence.model.CanonicalProperty
import com.example.urlintelligence.model.PropertyField
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlIntelligenceParserScreen(
    onBack: () -> Unit,
    onNavigateToAnalyzer: (String) -> Unit = {},
    onNavigateToDealRoom: (String) -> Unit = {},
    viewModel: UrlIntelligenceParserViewModel = viewModel()
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("URL Intelligence Parser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(":urlintelligence canonical extractor", fontSize = 11.sp, color = Slate400)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            UrlIntelligenceParserForm(
                viewModel = viewModel,
                onNavigateToAnalyzer = onNavigateToAnalyzer,
                onNavigateToDealRoom = onNavigateToDealRoom,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Reusable Composable input form that uses the :urlintelligence module
 * to parse real estate listing URLs and displays the extracted property metadata.
 */
@Composable
fun UrlIntelligenceParserForm(
    viewModel: UrlIntelligenceParserViewModel = viewModel(),
    onNavigateToAnalyzer: (String) -> Unit = {},
    onNavigateToDealRoom: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var showAllFieldsInspector by remember { mutableStateOf(false) }
    var copiedFeedback by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .testTag("url_intelligence_parser_form")
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Input Form Header & Controls ─────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "REAL ESTATE LISTING URL INPUT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Slate400,
                        letterSpacing = 1.sp
                    )

                    // URL Input TextField
                    OutlinedTextField(
                        value = state.urlInput,
                        onValueChange = { viewModel.setUrlInput(it) },
                        placeholder = {
                            Text("https://www.zillow.com/... or redfin.com, realtor.com", fontSize = 12.sp, color = Slate400)
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = CyanPrimary)
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.urlInput.isNotBlank()) {
                                    IconButton(onClick = { viewModel.clear() }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = Slate400)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrBlank()) {
                                            viewModel.setUrlInput(clip)
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = Slate300)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("listing_url_input")
                    )

                    // Quick Sample Listing Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Quick Sample Listings (جاهز للاختبار الفوري):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate300
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SampleListingChip("Zillow Duplex (Austin)") {
                                viewModel.selectSample(HybridPropertyTransport.SAMPLE_ZILLOW_URL)
                            }
                            SampleListingChip("Redfin Multi-Family (Phoenix)") {
                                viewModel.selectSample(HybridPropertyTransport.SAMPLE_REDFIN_URL)
                            }
                            SampleListingChip("Realtor Triplex (Atlanta)") {
                                viewModel.selectSample(HybridPropertyTransport.SAMPLE_REALTOR_URL)
                            }
                            SampleListingChip("Homes.com Single-Family") {
                                viewModel.selectSample(HybridPropertyTransport.SAMPLE_HOMES_URL)
                            }
                            SampleListingChip("Brokerage Luxury Portal") {
                                viewModel.selectSample(HybridPropertyTransport.SAMPLE_GENERIC_URL)
                            }
                        }
                    }

                    // Advanced Options Accordion
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAdvancedOptions = !showAdvancedOptions }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Advanced Extraction Settings", fontSize = 12.sp, color = Slate400)
                        Icon(
                            imageVector = if (showAdvancedOptions) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = Slate400
                        )
                    }

                    AnimatedVisibility(visible = showAdvancedOptions) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.forceRefresh,
                                    onCheckedChange = { viewModel.setForceRefresh(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = CyanPrimary)
                                )
                                Text("Bypass Cache (Force fresh network fetch)", fontSize = 11.sp, color = Slate300)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = state.requireAccessPolicy,
                                    onCheckedChange = { viewModel.setRequireAccessPolicy(it) },
                                    colors = CheckboxDefaults.colors(checkedColor = AmberAccent)
                                )
                                Text("Enforce Strict robots.txt compliance gate", fontSize = 11.sp, color = Slate300)
                            }
                        }
                    }

                    // Primary Extract Button
                    Button(
                        onClick = { viewModel.parseUrl() },
                        enabled = state.urlInput.isNotBlank() && !state.isParsing,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = Slate950
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("parse_url_button")
                    ) {
                        if (state.isParsing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Slate950)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(state.parsingStep.ifBlank { "Parsing metadata..." }, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extract Property Metadata", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── Error State Card ─────────────────────────────────────────────────
        if (state.errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CrimsonAlert.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = CrimsonAlert, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Extraction Failed", fontWeight = FontWeight.Bold, color = CrimsonAlert, fontSize = 13.sp)
                            Text(state.errorMessage ?: "Unknown error", fontSize = 11.sp, color = Slate300)
                            if (state.isRetryable) {
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = { viewModel.parseUrl() },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Retry Ingestion", color = AmberAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Extracted Canonical Metadata Display ─────────────────────────────
        val prop = state.result
        if (prop != null) {
            // 1. Hero Card: Picture & Identity
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column {
                        // Image banner
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                        ) {
                            if (!prop.primaryImageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = prop.primaryImageUrl,
                                    contentDescription = prop.address.formatted,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Slate900),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Apartment, contentDescription = null, tint = Slate400, modifier = Modifier.size(48.dp))
                                }
                            }

                            // Top Badges Overlay
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CyanPrimary)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = prop.sourceId.uppercase(),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        color = Slate950
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (prop.listingStatus.name.contains("SALE")) EmeraldGain else AmberAccent)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = prop.listingStatus.name,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp,
                                        color = Slate950
                                    )
                                }
                            }

                            // Bottom Price Overlay
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Slate950.copy(alpha = 0.88f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = if (prop.listPriceUsd != null) "$${String.format("%,.0f", prop.listPriceUsd)}" else "Price Unlisted",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        color = Color.White
                                    )
                                    if (prop.pricePerSqFtUsd != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "($${String.format("%.0f", prop.pricePerSqFtUsd)}/sq ft)",
                                            fontSize = 11.sp,
                                            color = Slate400
                                        )
                                    }
                                }
                            }
                        }

                        // Address & Title Details
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = prop.address.formatted.ifBlank { "Address Unresolved" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (prop.address.geo != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Place, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${prop.address.geo!!.latitude}, ${prop.address.geo!!.longitude}",
                                        fontSize = 11.sp,
                                        color = Slate400,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            if (!prop.description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = prop.description!!,
                                    fontSize = 12.sp,
                                    color = Slate300,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }

            // 2. Asset Specifications Grid
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PHYSICAL PROPERTY SPECIFICATIONS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Slate400,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(label = "Property Type", value = prop.propertyType.name.replace("_", " "))
                            SpecItem(label = "Bedrooms", value = prop.bedrooms?.let { String.format("%.0f beds", it) } ?: "N/A")
                            SpecItem(label = "Bathrooms", value = prop.bathrooms?.let { String.format("%.1f baths", it) } ?: "N/A")
                        }

                        Divider(color = Slate800, modifier = Modifier.padding(vertical = 10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(label = "Living Area", value = prop.livingAreaSqFt?.let { "$it sq ft" } ?: "N/A")
                            SpecItem(label = "Lot Size", value = prop.lotSizeSqFt?.let { "$it sq ft" } ?: "N/A")
                            SpecItem(label = "Year Built", value = prop.yearBuilt?.toString() ?: "N/A")
                        }
                    }
                }
            }

            // 3. Financial & Underwriting Satellite Facts
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "EXTRACTED FINANCIAL & LISTING METADATA",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Slate400,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(
                                label = "Est. Rent",
                                value = prop.estimatedMonthlyRentUsd?.let { "$${String.format("%,.0f", it)}/mo" } ?: "N/A",
                                accentColor = EmeraldGain
                            )
                            SpecItem(
                                label = "Annual Property Tax",
                                value = prop.annualPropertyTaxUsd?.let { "$${String.format("%,.0f", it)}/yr" } ?: "N/A"
                            )
                            SpecItem(
                                label = "Monthly HOA",
                                value = prop.hoaMonthlyFeeUsd?.let { "$${String.format("%,.0f", it)}/mo" } ?: "None"
                            )
                        }

                        Divider(color = Slate800, modifier = Modifier.padding(vertical = 10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(label = "Days on Market", value = prop.daysOnMarket?.let { "$it days" } ?: "N/A")
                            SpecItem(label = "MLS Number", value = prop.mlsId ?: "N/A")
                            SpecItem(label = "Parcel / APN ID", value = prop.parcelId ?: "N/A")
                        }
                    }
                }
            }

            // 4. Ingestion Quality & Verification Health
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "INGESTION HEALTH & COMPLETENESS",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Slate400,
                                letterSpacing = 1.sp
                            )

                            val scorePct = (prop.completeness.score * 100).toInt()
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (scorePct >= 80) EmeraldGain.copy(alpha = 0.2f) else AmberAccent.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$scorePct% Complete",
                                    color = if (scorePct >= 80) EmeraldGain else AmberAccent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Linear progress indicator for completeness score
                        LinearProgressIndicator(
                            progress = prop.completeness.score.toFloat(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = CyanPrimary,
                            trackColor = Slate800
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(label = "Present Fields", value = "${prop.completeness.present.size} fields")
                            SpecItem(label = "Missing Required", value = "${prop.completeness.missingRequired.size} fields")
                            SpecItem(label = "Verification", value = if (prop.isLiveVerified) "Live Verified" else "Parser Verified")
                        }

                        if (prop.warnings.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Extraction Warnings:", fontSize = 11.sp, color = AmberAccent, fontWeight = FontWeight.Bold)
                            prop.warnings.forEach { warn ->
                                Text("• $warn", fontSize = 10.sp, color = Slate400)
                            }
                        }
                    }
                }
            }

            // 5. Expandable All Fields Inspector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAllFieldsInspector = !showAllFieldsInspector },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.DataArray, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inspect All Canonical PropertyField Keys", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Icon(
                                imageVector = if (showAllFieldsInspector) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = Slate400
                            )
                        }

                        AnimatedVisibility(visible = showAllFieldsInspector) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PropertyField.values().forEach { field ->
                                    val fieldValue = prop.field(field)
                                    val isPresent = fieldValue.isPresent()
                                    val displayVal = when (fieldValue) {
                                        is CanonicalProperty.FieldValue.Str -> fieldValue.value ?: "null"
                                        is CanonicalProperty.FieldValue.Num -> fieldValue.value?.toString() ?: "null"
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (isPresent) Slate900 else Color.Transparent)
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(field.name, fontSize = 10.sp, color = if (isPresent) CyanPrimary else Slate400, fontFamily = FontFamily.Monospace)
                                        Text(displayVal, fontSize = 10.sp, color = if (isPresent) Slate200 else Slate400, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. Action Buttons Bar (Save & Underwrite / Copy JSON)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.saveToPortfolio { savedId ->
                                onNavigateToAnalyzer(savedId)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp)
                            .testTag("save_and_underwrite_button")
                    ) {
                        Icon(Icons.Filled.Calculate, contentDescription = null, tint = Slate950, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (state.isPersisted) "Underwrite Deal" else "Save & Underwrite", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val memo = buildMetadataSummaryText(prop)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Extracted Property Metadata", memo))
                            copiedFeedback = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy Data", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (copiedFeedback) {
        AlertDialog(
            onDismissRequest = { copiedFeedback = false },
            title = { Text("Metadata Copied", fontWeight = FontWeight.Bold) },
            text = { Text("Complete canonical property metadata has been copied to your clipboard.") },
            confirmButton = {
                Button(onClick = { copiedFeedback = false }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) {
                    Text("OK", color = Slate950)
                }
            }
        )
    }
}

@Composable
private fun SampleListingChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
        modifier = Modifier
            .clickable { onClick() }
            .testTag("sample_chip_${label.take(6)}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SpecItem(
    label: String,
    value: String,
    accentColor: Color = Slate200
) {
    Column {
        Text(label, fontSize = 10.sp, color = Slate400)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accentColor)
    }
}

private fun buildMetadataSummaryText(property: CanonicalProperty): String {
    return """
        ====================================================
        EXTRACTED PROPERTY METADATA (:urlintelligence)
        ====================================================
        Canonical ID:    ${property.canonicalId}
        Source Portal:   ${property.sourceId.uppercase()}
        Address:         ${property.address.formatted}
        Coordinates:     ${property.address.geo?.latitude ?: 0.0}, ${property.address.geo?.longitude ?: 0.0}

        List Price:      ${property.listPriceUsd?.let { "$$it" } ?: "N/A"}
        Price / Sq Ft:   ${property.pricePerSqFtUsd?.let { "$$it" } ?: "N/A"}
        Property Type:   ${property.propertyType}
        Listing Status:  ${property.listingStatus}

        Bedrooms:        ${property.bedrooms ?: "N/A"}
        Bathrooms:       ${property.bathrooms ?: "N/A"}
        Living Area:     ${property.livingAreaSqFt?.let { "$it sqft" } ?: "N/A"}
        Lot Size:        ${property.lotSizeSqFt?.let { "$it sqft" } ?: "N/A"}
        Year Built:      ${property.yearBuilt ?: "N/A"}

        Est. Rent:       ${property.estimatedMonthlyRentUsd?.let { "$$it/mo" } ?: "N/A"}
        Annual Tax:      ${property.annualPropertyTaxUsd?.let { "$$it/yr" } ?: "N/A"}
        Monthly HOA:     ${property.hoaMonthlyFeeUsd?.let { "$$it/mo" } ?: "N/A"}
        Days on Market:  ${property.daysOnMarket ?: "N/A"}
        MLS ID:          ${property.mlsId ?: "N/A"}
        Parcel APN:      ${property.parcelId ?: "N/A"}

        Completeness:    ${(property.completeness.score * 100).toInt()}%
        Present Fields:  ${property.completeness.present.size}
        Verification:    ${if (property.isLiveVerified) "LIVE_VERIFIED" else "PARSER_VERIFIED"}
        Canonical URL:   ${property.canonicalUrl}
        ====================================================
    """.trimIndent()
}
