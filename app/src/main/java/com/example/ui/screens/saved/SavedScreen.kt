package com.example.ui.screens.saved

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.PropertyCard
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAnalyzer: (String) -> Unit,
    viewModel: SavedViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Portfolio & Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = CyanPrimary
            ) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Properties (${state.savedProperties.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("Deals (${state.savedDeals.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text("Analyses (${state.savedAnalyses.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            when (state.selectedTab) {
                0 -> {
                    if (state.savedProperties.isEmpty()) {
                        EmptySavedView(message = "No saved properties yet. Tap the bookmark icon on any property card to save it here.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.savedProperties, key = { it.id }) { prop ->
                                PropertyCard(
                                    property = prop,
                                    onSelect = { onNavigateToDetail(prop.id) },
                                    onAnalyze = { onNavigateToAnalyzer(prop.id) },
                                    onDraftOffer = { onNavigateToDetail(prop.id) },
                                    onToggleSave = { viewModel.toggleSave(prop.id, prop.isSaved) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (state.savedDeals.isEmpty()) {
                        EmptySavedView(message = "No qualified deals saved yet. Deals qualifying under automation rules will appear here.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.savedDeals, key = { it.id }) { prop ->
                                PropertyCard(
                                    property = prop,
                                    onSelect = { onNavigateToDetail(prop.id) },
                                    onAnalyze = { onNavigateToAnalyzer(prop.id) },
                                    onDraftOffer = { onNavigateToDetail(prop.id) },
                                    onToggleSave = { viewModel.toggleSave(prop.id, prop.isSaved) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    if (state.savedAnalyses.isEmpty()) {
                        EmptySavedView(message = "No underwritten financial analyses yet. Use the Analyzer to model properties.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.savedAnalyses, key = { it.propertyId }) { fin ->
                                val prop = state.propertyMap[fin.propertyId]
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onNavigateToAnalyzer(fin.propertyId) },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Text(
                                            text = prop?.address ?: "Property #${fin.propertyId}",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Purchase: $${String.format("%,.0f", fin.purchasePrice)} • Down: ${fin.downPaymentPct.toInt()}% @ ${fin.interestRatePct}%",
                                            fontSize = 11.sp,
                                            color = Slate400
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text("Net Cash Flow", fontSize = 10.sp, color = Slate400)
                                                Text("$${String.format("%,.0f", fin.monthlyCashFlow)}/mo", fontWeight = FontWeight.Bold, color = if (fin.monthlyCashFlow > 0) EmeraldGain else CrimsonAlert, fontSize = 13.sp)
                                            }
                                            Column {
                                                Text("Cap Rate", fontSize = 10.sp, color = Slate400)
                                                Text("${String.format("%.2f", fin.capRate)}%", fontWeight = FontWeight.Bold, color = CyanPrimary, fontSize = 13.sp)
                                            }
                                            Column {
                                                Text("CoC Return", fontSize = 10.sp, color = Slate400)
                                                Text("${String.format("%.2f", fin.cashOnCashReturn)}%", fontWeight = FontWeight.Bold, color = EmeraldGain, fontSize = 13.sp)
                                            }
                                            Column {
                                                Text("DSCR", fontSize = 10.sp, color = Slate400)
                                                Text(String.format("%.2f", fin.dscr), fontWeight = FontWeight.Bold, color = AmberAccent, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySavedView(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Slate600, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = Slate400, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 24.dp))
        }
    }
}
