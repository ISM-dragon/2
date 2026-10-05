package com.example.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.example.domain.automation.AutomationStatus
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
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanDark),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.Apartment, contentDescription = null, tint = Slate950, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("REAL ESTATE AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            Text("Autonomous Deal Finder", fontSize = 11.sp, color = Slate400)
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

                        Spacer(modifier = Modifier.height(10.dp))
                        // Emergency Kill Switch
                        OutlinedButton(
                            onClick = { viewModel.globalKillSwitch() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                                .testTag("global_kill_switch_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Filled.Dangerous, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("GLOBAL KILL SWITCH (EMERGENCY ABORT)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Quick Metrics Header
            item {
                Text(
                    text = "ACQUISITION PIPELINE METRICS",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            // Metrics Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            label = "Properties Scanned",
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
                            subValue = "${state.offersSent} Sent",
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToOffers
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        MetricCard(
                            label = "Offers Dispatched",
                            value = "${state.offersSent}",
                            icon = Icons.Filled.Send,
                            accentColor = EmeraldGain,
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToOffers
                        )
                        MetricCard(
                            label = "Failed Offers",
                            value = "${state.failedOffers}",
                            icon = Icons.Filled.Warning,
                            accentColor = if (state.failedOffers > 0) CrimsonAlert else Slate400,
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateToOffers
                        )
                    }
                }
            }

            // Last Automation Activity Card
            item {
                val pState = state.persistentState
                val lastTimeStr = if (pState.lastActivityTime > 0) {
                    val df = java.text.SimpleDateFormat("MMM dd, HH:mm:ss", java.util.Locale.US)
                    df.format(java.util.Date(pState.lastActivityTime))
                } else "None"

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LAST AUTOMATION ACTIVITY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Slate400)
                            Text(lastTimeStr, fontSize = 11.sp, color = Slate400)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pState.lastSuccessfulAction.ifBlank { pState.currentOperation },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate200
                        )
                        if (pState.lastError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Last error: ${pState.lastError}", fontSize = 11.sp, color = CrimsonAlert)
                        }
                    }
                }
            }

            // Gemini API Health Strip
            item {
                val activeSlot = state.apiSlots.firstOrNull { it.status == "READY" || it.status == "ACTIVE" }
                val configuredCount = state.apiSlots.count { it.apiKey.isNotBlank() }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToSettings() },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyanPrimary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Gemini Multi-Slot Engine",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (activeSlot != null) "Active: ${activeSlot.label} (${activeSlot.model})" else "No active slot configured ($configuredCount/6 keys stored)",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        StatusBadge(status = if (activeSlot != null) "READY" else "CONFIG")
                    }
                }
            }

            // Recent Opportunities Section Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TOP OPPORTUNITIES",
                        style = MaterialTheme.typography.labelMedium,
                        color = Slate400,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    TextButton(onClick = onNavigateToDiscover) {
                        Text("View All (${state.propertiesScanned})", fontSize = 12.sp, color = CyanPrimary)
                    }
                }
            }

            // Recent Opportunities Cards
            if (state.recentOpportunities.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No properties found. Tap Start Auto to scan MLS and Off-Market sources.", color = Slate400, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                items(state.recentOpportunities) { property ->
                    PropertyCard(
                        property = property,
                        onSelect = { onNavigateToPropertyDetail(property.id) },
                        onAnalyze = { onNavigateToAnalyzer(property.id) },
                        onDraftOffer = { onNavigateToPropertyDetail(property.id) },
                        onToggleSave = { viewModel.toggleSave(property.id, property.isSaved) }
                    )
                }
            }
        }
    }
}
