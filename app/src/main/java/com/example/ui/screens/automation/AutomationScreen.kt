package com.example.ui.screens.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.automation.AutomationStatus
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: AutomationViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRunning = state.status == AutomationStatus.RUNNING || state.status == AutomationStatus.SCANNING || state.status == AutomationStatus.ANALYZING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Autonomous Operations Center", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (state.selectedJob != null) {
            JobDetailsDialog(
                job = state.selectedJob!!,
                onDismiss = { viewModel.selectJob(null) }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Engine Status & Global Kill Switch Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Slate800 else Slate900
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusBadge(status = state.status.name)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("AUTO MODE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.currentTaskDescription, fontSize = 11.sp, color = Slate400)
                        }

                        Switch(
                            checked = isRunning,
                            onCheckedChange = { checked ->
                                if (checked) viewModel.startAutomation() else viewModel.stopAutomation()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Slate950,
                                checkedTrackColor = CyanPrimary
                            ),
                            modifier = Modifier.testTag("auto_mode_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // EMERGENCY GLOBAL KILL SWITCH
                    Button(
                        onClick = { viewModel.globalKillSwitch() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("global_kill_switch_action"),
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                    ) {
                        Icon(Icons.Filled.Dangerous, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GLOBAL KILL SWITCH (STOP EVERYTHING)", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }
            }

            // Tab Selector
            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = CyanPrimary
            ) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.setSelectedTab(0) },
                    text = { Text("Rules", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.setSelectedTab(1) },
                    text = { Text("Jobs (${state.jobs.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.setSelectedTab(2) },
                    text = { Text("Logs (${state.activityLogs.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = state.selectedTab == 3,
                    onClick = { viewModel.setSelectedTab(3) },
                    text = { Text("Errors (${state.errorLogs.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = state.selectedTab == 4,
                    onClick = { viewModel.setSelectedTab(4) },
                    text = { Text("API", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                )
            }

            when (state.selectedTab) {
                0 -> {
                    // Automation & Qualification Rules Tab
                    val rules = state.rules
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            RuleCategoryCard(title = "1. DISCOVERY & FILTERING RULES") {
                                RuleTextField(
                                    label = "Target Market Locations (Comma-separated)",
                                    value = rules.allowedLocations,
                                    onValueChange = { viewModel.updateRules(rules.copy(allowedLocations = it)) }
                                )
                                RuleTextField(
                                    label = "Allowed Property Types",
                                    value = rules.allowedPropertyTypes,
                                    onValueChange = { viewModel.updateRules(rules.copy(allowedPropertyTypes = it)) }
                                )
                                RuleNumberField(
                                    label = "Max Purchase Price ($)",
                                    value = rules.maxPurchasePrice,
                                    onValueChange = { viewModel.updateRules(rules.copy(maxPurchasePrice = it)) }
                                )
                            }
                        }

                        item {
                            RuleCategoryCard(title = "2. QUALIFICATION HURDLE RATES") {
                                RuleNumberField(
                                    label = "Min Net Cash Flow ($/mo)",
                                    value = rules.minCashFlow,
                                    onValueChange = { viewModel.updateRules(rules.copy(minCashFlow = it)) }
                                )
                                RuleNumberField(
                                    label = "Min Cap Rate (%)",
                                    value = rules.minCapRate,
                                    onValueChange = { viewModel.updateRules(rules.copy(minCapRate = it)) }
                                )
                                RuleNumberField(
                                    label = "Min DSCR Coverage",
                                    value = rules.minDscr,
                                    onValueChange = { viewModel.updateRules(rules.copy(minDscr = it)) }
                                )
                                RuleNumberField(
                                    label = "Min Cash-on-Cash Return (%)",
                                    value = rules.minCashOnCash,
                                    onValueChange = { viewModel.updateRules(rules.copy(minCashOnCash = it)) }
                                )
                                RuleNumberField(
                                    label = "Max Renovation Budget ($)",
                                    value = rules.maxRenovationCost,
                                    onValueChange = { viewModel.updateRules(rules.copy(maxRenovationCost = it)) }
                                )
                            }
                        }

                        item {
                            RuleCategoryCard(title = "3. OFFER & DISPATCH RULES") {
                                RuleNumberField(
                                    label = "Offer Discount Off Asking (%)",
                                    value = rules.offerDiscountPercent,
                                    onValueChange = { viewModel.updateRules(rules.copy(offerDiscountPercent = it)) }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Auto Generate Offers", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Draft LOI & PDF agreement automatically", fontSize = 11.sp, color = Slate400)
                                    }
                                    Switch(
                                        checked = rules.autoGenerateOffers,
                                        onCheckedChange = { viewModel.updateRules(rules.copy(autoGenerateOffers = it)) }
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Auto Send via Gmail", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text("Transmit contract directly when qualified", fontSize = 11.sp, color = Slate400)
                                    }
                                    Switch(
                                        checked = rules.autoSendOffers,
                                        onCheckedChange = { viewModel.updateRules(rules.copy(autoSendOffers = it)) }
                                    )
                                }
                            }
                        }

                        item {
                            RuleCategoryCard(title = "4. SCHEDULER & SAFETY THRESHOLDS") {
                                RuleNumberField(
                                    label = "Scan Cycle Interval (Minutes)",
                                    value = rules.scanIntervalMinutes.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(scanIntervalMinutes = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Max Properties Per Cycle",
                                    value = rules.maxPropertiesPerCycle.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(maxPropertiesPerCycle = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Max Analyses Per Run",
                                    value = rules.maxAnalysesPerRun.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(maxAnalysesPerRun = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Max Offers Created Per Run",
                                    value = rules.maxOffersPerRun.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(maxOffersPerRun = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Max Emails Sent Per Run",
                                    value = rules.maxEmailsPerRun.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(maxEmailsPerRun = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Max Retries Per Step",
                                    value = rules.maxRetries.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(maxRetries = it.toInt())) }
                                )
                                RuleNumberField(
                                    label = "Consecutive Error Threshold",
                                    value = rules.consecutiveFailureThreshold.toDouble(),
                                    onValueChange = { viewModel.updateRules(rules.copy(consecutiveFailureThreshold = it.toInt())) }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    // Jobs Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.jobs.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("No automation jobs recorded yet. Turn on Auto Mode to begin pipeline.", color = Slate400, fontSize = 13.sp)
                                }
                            }
                        } else {
                            items(state.jobs) { job ->
                                JobItemCard(job = job, onClick = { viewModel.selectJob(job) })
                            }
                        }
                    }
                }

                2 -> {
                    // Activity Log Tab
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Engine Event Timeline", style = MaterialTheme.typography.labelMedium, color = Slate400)
                            TextButton(onClick = { viewModel.clearLogs() }) {
                                Text("Clear Logs", color = CrimsonAlert, fontSize = 12.sp)
                            }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.activityLogs) { log ->
                                LogItemCard(log = log)
                            }
                        }
                    }
                }

                3 -> {
                    // Error Log Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (state.errorLogs.isEmpty()) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                    Text("Zero system errors logged. Everything running smoothly.", color = EmeraldGain, fontSize = 13.sp)
                                }
                            }
                        } else {
                            items(state.errorLogs) { err ->
                                LogItemCard(log = err)
                            }
                        }
                    }
                }

                4 -> {
                    // API Status Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Text("GEMINI MULTI-SLOT POOL", style = MaterialTheme.typography.labelMedium, color = Slate400)
                        }

                        items(state.apiSlots) { slot ->
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
                                        Text(slot.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        StatusBadge(status = slot.status)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Model: ${slot.model}", fontSize = 11.sp, color = Slate400)
                                    Text("API Key: ${if (slot.apiKey.isNotBlank()) "••••••••" + slot.apiKey.takeLast(4) else "Unconfigured"}", fontSize = 11.sp, color = Slate400)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("Usage: ${slot.usageCount}", fontSize = 11.sp, color = CyanPrimary, fontWeight = FontWeight.Bold)
                                        Text("Errors: ${slot.errorCount}", fontSize = 11.sp, color = if (slot.errorCount > 0) CrimsonAlert else Slate400)
                                        if (slot.cooldownUntil > System.currentTimeMillis()) {
                                            val remainingSec = (slot.cooldownUntil - System.currentTimeMillis()) / 1000
                                            Text("Cooling: ${remainingSec}s", fontSize = 11.sp, color = AmberAccent, fontWeight = FontWeight.Bold)
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
private fun RuleCategoryCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CyanPrimary)
            content()
        }
    }
}

@Composable
private fun RuleNumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit
) {
    var text by remember(value) { mutableStateOf(String.format(if (value % 1.0 == 0.0) "%.0f" else "%.2f", value)) }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            it.toDoubleOrNull()?.let { num -> onValueChange(num) }
        },
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun RuleTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun LogItemCard(log: com.example.data.local.entity.AutomationLogEntity) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    val timeStr = timeFormat.format(Date(log.timestamp))

    Surface(
        color = Slate900,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(status = log.level)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("[${log.tag}]", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = CyanPrimary)
                    Text(timeStr, fontSize = 10.sp, color = Slate400)
                }
                Text(log.message, fontSize = 11.sp, color = Slate200)
            }
        }
    }
}

@Composable
private fun JobItemCard(
    job: com.example.data.local.entity.AutomationJobEntity,
    onClick: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    val timeStr = timeFormat.format(Date(job.updatedAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(status = job.currentState)
                Text("Updated $timeStr", fontSize = 10.sp, color = Slate400)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(job.propertyAddress, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Slate100)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Job: ${job.jobId}", fontSize = 11.sp, color = Slate400)
                if (job.attempts > 0) {
                    Text("Attempts: ${job.attempts}/${job.maxRetries}", fontSize = 11.sp, color = AmberAccent)
                }
            }
            if (!job.blockageReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Blocked: ${job.blockageReason}", fontSize = 11.sp, color = CrimsonAlert)
            } else if (!job.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Error: ${job.lastError}", fontSize = 11.sp, color = CrimsonAlert)
            }
        }
    }
}

@Composable
private fun JobDetailsDialog(
    job: com.example.data.local.entity.AutomationJobEntity,
    onDismiss: () -> Unit
) {
    val fullFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.US)
    val createdStr = fullFormat.format(Date(job.createdAt))
    val updatedStr = fullFormat.format(Date(job.updatedAt))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                StatusBadge(status = job.currentState)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Automation Job Details", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Property Address:", fontSize = 11.sp, color = Slate400)
                Text(job.propertyAddress, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Slate100)

                Divider(color = Slate800, thickness = 1.dp)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Job ID:", fontSize = 11.sp, color = Slate400)
                    Text(job.jobId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate200)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Current State:", fontSize = 11.sp, color = Slate400)
                    Text(job.currentState, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Success:", fontSize = 11.sp, color = Slate400)
                    Text(job.lastSuccessfulState, fontSize = 11.sp, color = EmeraldGain)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Attempts:", fontSize = 11.sp, color = Slate400)
                    Text("${job.attempts} of ${job.maxRetries}", fontSize = 11.sp, color = Slate200)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Started At:", fontSize = 11.sp, color = Slate400)
                    Text(createdStr, fontSize = 10.sp, color = Slate300)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Last Updated:", fontSize = 11.sp, color = Slate400)
                    Text(updatedStr, fontSize = 10.sp, color = Slate300)
                }

                if (!job.offerId.isNullOrBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Offer Contract:", fontSize = 11.sp, color = Slate400)
                        Text(job.offerId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmeraldGain)
                    }
                }
                if (!job.emailMessageId.isNullOrBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Email Message:", fontSize = 11.sp, color = Slate400)
                        Text(job.emailMessageId, fontSize = 11.sp, color = CyanPrimary)
                    }
                }
                if (!job.recipientEmail.isNullOrBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Recipient:", fontSize = 11.sp, color = Slate400)
                        Text(job.recipientEmail, fontSize = 11.sp, color = Slate200)
                    }
                }
                if (!job.blockageReason.isNullOrBlank()) {
                    Text("Blockage Reason:", fontSize = 11.sp, color = CrimsonAlert)
                    Text(job.blockageReason, fontSize = 11.sp, color = CrimsonAlert)
                }
                if (!job.lastError.isNullOrBlank() && job.lastError != job.blockageReason) {
                    Text("Last Error Encountered:", fontSize = 11.sp, color = CrimsonAlert)
                    Text(job.lastError, fontSize = 11.sp, color = CrimsonAlert)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
            ) {
                Text("Close", color = Slate950, fontWeight = FontWeight.Bold)
            }
        }
    )
}
