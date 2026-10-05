package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.ApiConfigurationEntity
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section 1: Gemini Multi-Slot Pool
            item {
                Text(
                    text = "1. GEMINI API MULTI-SLOT MANAGER",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            items(state.apiSlots) { slot ->
                ApiSlotCard(
                    slot = slot,
                    onSave = { viewModel.updateApiSlot(it) },
                    onTest = { slotIndex, onResult -> viewModel.testApiSlot(slotIndex, onResult) }
                )
            }

            // Section 2: Gmail Account Integration
            item {
                Text(
                    text = "2. GMAIL ACCOUNT INTEGRATION & OAUTH",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            item {
                val gmail = state.gmailConfig
                var email by remember(gmail.accountEmail) { mutableStateOf(gmail.accountEmail) }
                var sender by remember(gmail.senderName) { mutableStateOf(gmail.senderName) }
                var sig by remember(gmail.signature) { mutableStateOf(gmail.signature) }
                var subjectTemplate by remember(gmail.defaultSubjectTemplate) { mutableStateOf(gmail.defaultSubjectTemplate) }
                var isConnected by remember(gmail.isConnected) { mutableStateOf(gmail.isConnected) }
                var authFeedback by remember { mutableStateOf<String?>(null) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Google Gmail Account", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    text = when (gmail.authStatus) {
                                        "SENT" -> "Connected • Verified • Ready"
                                        "SENDING" -> "Sending offer via Gmail..."
                                        "AUTH_REQUIRED" -> if (isConnected) "Connected • OAuth Active" else "Authorization Required (Google OAuth)"
                                        "AUTH_EXPIRED" -> "OAuth Expired • Re-auth Required"
                                        "FAILED" -> "Connection Error • Check Settings"
                                        else -> "Not Configured"
                                    },
                                    fontSize = 11.sp,
                                    color = when {
                                        isConnected || gmail.authStatus == "SENT" -> EmeraldGain
                                        gmail.authStatus == "AUTH_REQUIRED" || gmail.authStatus == "AUTH_EXPIRED" -> AmberAccent
                                        gmail.authStatus == "FAILED" -> CrimsonAlert
                                        else -> Slate400
                                    }
                                )
                            }
                            StatusBadge(status = if (isConnected) "CONNECTED" else gmail.authStatus)
                        }

                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Gmail Account Email") },
                            modifier = Modifier.fillMaxWidth().testTag("gmail_email_input"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = sender,
                            onValueChange = { sender = it },
                            label = { Text("Sender Name (e.g. Acquisitions Team)") },
                            modifier = Modifier.fillMaxWidth().testTag("gmail_sender_input"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = subjectTemplate,
                            onValueChange = { subjectTemplate = it },
                            label = { Text("Offer Subject Template") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = sig,
                            onValueChange = { sig = it },
                            label = { Text("Email Signature") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )

                        if (authFeedback != null) {
                            Text(
                                text = authFeedback ?: "",
                                fontSize = 11.sp,
                                color = if (isConnected) EmeraldGain else AmberAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Actions Row: Connect / Disconnect / Refresh / Save
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!isConnected) {
                                Button(
                                    onClick = {
                                        val targetEmail = email.trim()
                                        if (targetEmail.isBlank()) {
                                            authFeedback = "Please enter your Gmail account address."
                                        } else {
                                            viewModel.saveGmailAccountSettings(
                                                email = targetEmail,
                                                senderName = sender.ifBlank { "Real Estate Acquisitions Team" },
                                                signature = sig,
                                                subjectTemplate = subjectTemplate
                                            )
                                            authFeedback = "Settings saved for $targetEmail. Google OAuth sign-in required to authorize email dispatch."
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("connect_gmail_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                                ) {
                                    Icon(Icons.Filled.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = Slate950)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Save Account", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.refreshGmailAuth { success, msg ->
                                            authFeedback = msg
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("refresh_gmail_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Refresh", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        isConnected = false
                                        viewModel.disconnectGmail()
                                        authFeedback = "Gmail disconnected successfully."
                                    },
                                    modifier = Modifier.weight(1f).testTag("disconnect_gmail_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert)
                                ) {
                                    Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Disconnect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = {
                                    viewModel.saveGmailAccountSettings(
                                        email = email,
                                        senderName = sender,
                                        signature = sig,
                                        subjectTemplate = subjectTemplate
                                    )
                                    authFeedback = "Settings saved successfully."
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Slate700)
                            ) {
                                Text("Save", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Section 3: Offer Template
            item {
                Text(
                    text = "3. OFFER TEMPLATE & CONTRACT CLAUSES",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            item {
                val t = state.offerTemplate
                var earnest by remember(t.earnestMoneyPercent) { mutableStateOf(t.earnestMoneyPercent.toString()) }
                var inspDays by remember(t.defaultInspectionDays) { mutableStateOf(t.defaultInspectionDays.toString()) }
                var closeDays by remember(t.defaultClosingDays) { mutableStateOf(t.defaultClosingDays.toString()) }
                var terms by remember(t.standardTerms) { mutableStateOf(t.standardTerms) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = earnest,
                                onValueChange = { earnest = it },
                                label = { Text("Earnest %") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            OutlinedTextField(
                                value = inspDays,
                                onValueChange = { inspDays = it },
                                label = { Text("Insp Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = closeDays,
                                onValueChange = { closeDays = it },
                                label = { Text("Close Days") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }

                        OutlinedTextField(
                            value = terms,
                            onValueChange = { terms = it },
                            label = { Text("Standard Purchase Agreement Terms") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5
                        )

                        Button(
                            onClick = {
                                viewModel.updateOfferTemplate(
                                    t.copy(
                                        earnestMoneyPercent = earnest.toDoubleOrNull() ?: 1.5,
                                        defaultInspectionDays = inspDays.toIntOrNull() ?: 10,
                                        defaultClosingDays = closeDays.toIntOrNull() ?: 21,
                                        standardTerms = terms
                                    )
                                )
                            },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Text("Save Template", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Section 4: Storage & Data Backup
            item {
                Text(
                    text = "4. LOCAL STORAGE & DATA EXPORT",
                    style = MaterialTheme.typography.labelMedium,
                    color = Slate400,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(state.storageStats, fontSize = 12.sp, color = Slate300)

                        if (state.exportStatus != null) {
                            Text(state.exportStatus!!, fontSize = 12.sp, color = EmeraldGain, fontWeight = FontWeight.Bold)
                        }

                        var showImportDialog by remember { mutableStateOf(false) }
                        var importJsonText by remember { mutableStateOf("") }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.exportData(context) },
                                modifier = Modifier.weight(1f).testTag("export_backup_button")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { showImportDialog = true },
                                modifier = Modifier.weight(1f).testTag("import_backup_button")
                            ) {
                                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { viewModel.resetToSampleData() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Slate700)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Sync Feed", fontSize = 12.sp)
                            }
                        }

                        if (showImportDialog) {
                            AlertDialog(
                                onDismissRequest = { showImportDialog = false },
                                title = { Text("Restore Database Backup", fontWeight = FontWeight.Bold) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Paste your exported JSON backup to validate and restore properties, underwriting analyses, and offers:", fontSize = 12.sp, color = Slate300)
                                        OutlinedTextField(
                                            value = importJsonText,
                                            onValueChange = { importJsonText = it },
                                            label = { Text("JSON Payload") },
                                            modifier = Modifier.fillMaxWidth().height(140.dp),
                                            maxLines = 6
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showImportDialog = false
                                            if (importJsonText.isNotBlank()) {
                                                viewModel.importData(context, importJsonText)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                                    ) {
                                        Text("Restore", color = Slate950, fontWeight = FontWeight.Bold)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showImportDialog = false }) {
                                        Text("Cancel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiSlotCard(
    slot: ApiConfigurationEntity,
    onSave: (ApiConfigurationEntity) -> Unit,
    onTest: (Int, (Boolean, String) -> Unit) -> Unit
) {
    var apiKey by remember(slot.apiKey) { mutableStateOf(slot.apiKey) }
    var model by remember(slot.model) { mutableStateOf(slot.model) }
    var isExpanded by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

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
                Column {
                    Text(slot.label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Model: $model", fontSize = 11.sp, color = Slate400)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(status = slot.status)
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(28.dp)) {
                        Icon(if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                    }
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Name (e.g. gemini-2.5-flash, gemini-3.1-pro-preview)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (testResult != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = testResult!!.second,
                        fontSize = 11.sp,
                        color = if (testResult!!.first) EmeraldGain else CrimsonAlert,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Reqs: ${slot.usageCount} | Err: ${slot.errorCount}", fontSize = 11.sp, color = Slate400)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                isTesting = true
                                onTest(slot.slotIndex) { success, msg ->
                                    isTesting = false
                                    testResult = Pair(success, msg)
                                }
                            },
                            enabled = !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Test Ping", fontSize = 11.sp)
                            }
                        }

                        Button(
                            onClick = {
                                val newStatus = if (apiKey.isNotBlank()) "READY" else "DISABLED"
                                onSave(slot.copy(apiKey = apiKey, model = model, status = newStatus))
                                isExpanded = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                        ) {
                            Text("Save Slot", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
