package com.example.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.PropertyCard
import com.example.ui.components.PropertyMapCanvas
import com.example.ui.components.PropertyUrlIntelligenceCard
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAnalyzer: (String) -> Unit,
    onNavigateToDealRoom: (String) -> Unit = {},
    viewModel: DiscoverViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedMapPropId by remember { mutableStateOf<String?>(null) }
    var isAddPropertyDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { isAddPropertyDialogOpen = true },
                containerColor = CyanPrimary,
                contentColor = Slate950,
                modifier = Modifier.testTag("add_property_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Property")
            }
        },
        topBar = {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Top Search Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.filter.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("discover_search_input"),
                        placeholder = { Text("Search city, address, zip...", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = Slate400)
                        },
                        trailingIcon = {
                            if (state.filter.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    // Filter Button
                    IconButton(
                        onClick = { viewModel.openFilterSheet(true) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("filter_button")
                    ) {
                        Icon(Icons.Filled.Tune, contentDescription = "Filter", tint = CyanPrimary)
                    }

                    // Refresh Button
                    IconButton(
                        onClick = { viewModel.refreshFromSources() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("refresh_sources_button")
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = CyanPrimary)
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "Sync", tint = Slate200)
                        }
                    }
                }

                Text(
                    "Cached local properties only · No licensed nationwide listing provider configured. " +
                        "Refresh does not fetch live listings; coverage and freshness are unverified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Source Tabs: All | On-Market | Off-Market | Map
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterTabChip(
                            label = "All Deals",
                            isSelected = state.filter.sourceType == "ALL" && state.filter.viewMode != DiscoverViewMode.MAP,
                            onClick = {
                                viewModel.setSourceTab("ALL")
                                if (state.filter.viewMode == DiscoverViewMode.MAP) {
                                    viewModel.setViewMode(DiscoverViewMode.LIST)
                                }
                            }
                        )
                        FilterTabChip(
                            label = "On-Market",
                            isSelected = state.filter.sourceType == "ON_MARKET" && state.filter.viewMode != DiscoverViewMode.MAP,
                            onClick = {
                                viewModel.setSourceTab("ON_MARKET")
                                if (state.filter.viewMode == DiscoverViewMode.MAP) {
                                    viewModel.setViewMode(DiscoverViewMode.LIST)
                                }
                            }
                        )
                        FilterTabChip(
                            label = "Off-Market",
                            isSelected = state.filter.sourceType == "OFF_MARKET" && state.filter.viewMode != DiscoverViewMode.MAP,
                            onClick = {
                                viewModel.setSourceTab("OFF_MARKET")
                                if (state.filter.viewMode == DiscoverViewMode.MAP) {
                                    viewModel.setViewMode(DiscoverViewMode.LIST)
                                }
                            }
                        )
                    }

                    // View Mode Switcher
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        IconButton(
                            onClick = { viewModel.setViewMode(DiscoverViewMode.LIST) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.FormatListBulleted,
                                contentDescription = "List",
                                tint = if (state.filter.viewMode == DiscoverViewMode.LIST) CyanPrimary else Slate400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.setViewMode(DiscoverViewMode.GRID) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.GridView,
                                contentDescription = "Grid",
                                tint = if (state.filter.viewMode == DiscoverViewMode.GRID) CyanPrimary else Slate400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.setViewMode(DiscoverViewMode.MAP) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Filled.Map,
                                contentDescription = "Map",
                                tint = if (state.filter.viewMode == DiscoverViewMode.MAP) CyanPrimary else Slate400,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (state.filter.viewMode) {
                DiscoverViewMode.MAP -> {
                    PropertyMapCanvas(
                        properties = state.properties.filter { it.latitude != 0.0 && it.longitude != 0.0 },
                        selectedPropertyId = selectedMapPropId,
                        onSelectProperty = { selectedMapPropId = it.id },
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToAnalyzer = onNavigateToAnalyzer
                    )
                }

                DiscoverViewMode.GRID -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            PropertyUrlIntelligenceCard(
                                urlInput = state.urlInput,
                                onUrlChange = { viewModel.setUrlInput(it) },
                                onAnalyze = { force ->
                                    viewModel.analyzePropertyUrl(forceRefresh = force) { propId ->
                                        onNavigateToDealRoom(propId)
                                    }
                                },
                                jobProgress = state.jobProgress,
                                onCancelJob = { id -> viewModel.cancelJob(id) },
                                onClearJob = { viewModel.clearJob() },
                                onOpenDealRoom = { propId -> onNavigateToDealRoom(propId) }
                            )
                        }

                        if (state.properties.isEmpty()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                EmptyDiscoverState()
                            }
                        } else {
                            items(state.properties, key = { it.id }) { property ->
                                PropertyCard(
                                    property = property,
                                    onSelect = { onNavigateToDetail(property.id) },
                                    onAnalyze = { onNavigateToAnalyzer(property.id) },
                                    onDraftOffer = { onNavigateToDetail(property.id) },
                                    onToggleSave = { viewModel.toggleSave(property.id, property.isSaved) }
                                )
                            }
                        }
                    }
                }

                DiscoverViewMode.LIST -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            PropertyUrlIntelligenceCard(
                                urlInput = state.urlInput,
                                onUrlChange = { viewModel.setUrlInput(it) },
                                onAnalyze = { force ->
                                    viewModel.analyzePropertyUrl(forceRefresh = force) { propId ->
                                        onNavigateToDealRoom(propId)
                                    }
                                },
                                jobProgress = state.jobProgress,
                                onCancelJob = { id -> viewModel.cancelJob(id) },
                                onClearJob = { viewModel.clearJob() },
                                onOpenDealRoom = { propId -> onNavigateToDealRoom(propId) }
                            )
                        }

                        if (state.properties.isEmpty()) {
                            item {
                                EmptyDiscoverState()
                            }
                        } else {
                            items(state.properties, key = { it.id }) { property ->
                                PropertyCard(
                                    property = property,
                                    onSelect = { onNavigateToDetail(property.id) },
                                    onAnalyze = { onNavigateToAnalyzer(property.id) },
                                    onDraftOffer = { onNavigateToDetail(property.id) },
                                    onToggleSave = { viewModel.toggleSave(property.id, property.isSaved) }
                                )
                            }
                        }
                    }
                }
            }

            // Filter Bottom Sheet
            if (state.isFilterSheetOpen) {
                FilterBottomSheet(
                    currentFilter = state.filter,
                    onApply = { viewModel.updateFilters(it) },
                    onReset = { viewModel.resetFilters() },
                    onDismiss = { viewModel.openFilterSheet(false) }
                )
            }

            // Add Property Dialog
            if (isAddPropertyDialogOpen) {
                AddPropertyDialog(
                    onDismiss = { isAddPropertyDialogOpen = false },
                    onAdd = { addr, city, state, zip, price, type, beds, baths, sqft, rent, source ->
                        viewModel.addCustomProperty(
                            title = addr,
                            address = addr,
                            city = city,
                            state = state,
                            zipCode = zip,
                            price = price,
                            propertyType = type,
                            beds = beds,
                            baths = baths,
                            sqft = sqft,
                            rent = rent,
                            sourceType = source
                        )
                        isAddPropertyDialogOpen = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FilterTabChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) CyanPrimary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (isSelected) Slate950 else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyDiscoverState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = Slate400)
            Spacer(modifier = Modifier.height(12.dp))
            Text("No properties match current criteria", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Try clearing search filters or syncing new listings.", fontSize = 12.sp, color = Slate400)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    currentFilter: DiscoverFilterState,
    onApply: (DiscoverFilterState) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var minPriceText by remember { mutableStateOf(currentFilter.minPrice?.toInt()?.toString() ?: "") }
    var maxPriceText by remember { mutableStateOf(currentFilter.maxPrice?.toInt()?.toString() ?: "") }
    var minBeds by remember { mutableStateOf(currentFilter.minBeds) }
    var minBaths by remember { mutableStateOf(currentFilter.minBaths) }
    var propertyType by remember { mutableStateOf(currentFilter.propertyType) }
    var sortOption by remember { mutableStateOf(currentFilter.sortOption) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Deal Search Filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = onReset) {
                    Text("Reset All", color = CrimsonAlert)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Price Range
            Text("Price Range ($ USD)", style = MaterialTheme.typography.labelMedium, color = Slate400)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = minPriceText,
                    onValueChange = { minPriceText = it },
                    placeholder = { Text("Min Price") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxPriceText,
                    onValueChange = { maxPriceText = it },
                    placeholder = { Text("Max Price") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bedrooms
            Text("Minimum Bedrooms", style = MaterialTheme.typography.labelMedium, color = Slate400)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 3, 4, 5).forEach { beds ->
                    FilterChip(
                        selected = minBeds == beds,
                        onClick = { minBeds = beds },
                        label = { Text(if (beds == 0) "Any" else "$beds+") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Property Type
            Text("Property Type", style = MaterialTheme.typography.labelMedium, color = Slate400)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL", "Single Family", "Multi-Family", "Condo").forEach { type ->
                    FilterChip(
                        selected = propertyType.equals(type, ignoreCase = true),
                        onClick = { propertyType = type },
                        label = { Text(type) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Apply Button
            Button(
                onClick = {
                    val minP = minPriceText.toDoubleOrNull()
                    val maxP = maxPriceText.toDoubleOrNull()
                    onApply(
                        currentFilter.copy(
                            minPrice = minP,
                            maxPrice = maxP,
                            minBeds = minBeds,
                            minBaths = minBaths,
                            propertyType = propertyType,
                            sortOption = sortOption
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Apply Filters", color = Slate950, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun AddPropertyDialog(
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
    var city by remember { mutableStateOf("") }
    var stateCode by remember { mutableStateOf("TX") }
    var zipCode by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var propertyType by remember { mutableStateOf("Single Family") }
    var bedsText by remember { mutableStateOf("3") }
    var bathsText by remember { mutableStateOf("2.0") }
    var sqftText by remember { mutableStateOf("1600") }
    var rentText by remember { mutableStateOf("") }
    var sourceType by remember { mutableStateOf("ON_MARKET") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Property", fontWeight = FontWeight.Bold) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Street Address") },
                        modifier = Modifier.fillMaxWidth().testTag("add_prop_address"),
                        singleLine = true
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = city,
                            onValueChange = { city = it },
                            label = { Text("City") },
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
                            modifier = Modifier.weight(1f).testTag("add_prop_price"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = rentText,
                            onValueChange = { rentText = it },
                            label = { Text("Est. Rent ($)") },
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
                            label = { Text("Sqft") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                item {
                    Text("Source Type", style = MaterialTheme.typography.labelMedium, color = Slate400)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sourceType == "ON_MARKET",
                            onClick = { sourceType = "ON_MARKET" },
                            label = { Text("On-Market") }
                        )
                        FilterChip(
                            selected = sourceType == "OFF_MARKET",
                            onClick = { sourceType = "OFF_MARKET" },
                            label = { Text("Off-Market") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = priceText.toDoubleOrNull() ?: 0.0
                    val r = rentText.toDoubleOrNull() ?: (p * 0.008)
                    val b = bedsText.toIntOrNull() ?: 3
                    val ba = bathsText.toDoubleOrNull() ?: 2.0
                    val s = sqftText.toIntOrNull() ?: 1500
                    onAdd(address.ifBlank { "123 Sample St" }, city.ifBlank { "Austin" }, stateCode.ifBlank { "TX" }, zipCode.ifBlank { "78701" }, p, propertyType, b, ba, s, r, sourceType)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Add Property", color = Slate950, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
