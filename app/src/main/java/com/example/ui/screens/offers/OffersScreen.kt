package com.example.ui.screens.offers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.OfferEntity
import com.example.data.local.entity.PropertyEntity
import com.example.ui.components.StatusBadge
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen(
    onNavigateToDetail: (String) -> Unit,
    viewModel: OffersViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val statuses = listOf("ALL", "DRAFT", "GENERATED", "READY", "SENT", "OPENED", "SIGNED", "DECLINED", "EXPIRED", "FAILED")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Institutional Offer Pipeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
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
            // Status Tabs
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(statuses) { status ->
                    val isSelected = state.selectedStatus == status
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { viewModel.selectStatus(status) }
                    ) {
                        Text(
                            text = status,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Slate950 else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (state.offers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = Slate400, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No offers found in '${state.selectedStatus}'", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Qualified deals generate offers automatically in Auto Mode or via Property Details.", fontSize = 12.sp, color = Slate400)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.offers, key = { it.id }) { offer ->
                        val property = state.propertyMap[offer.propertyId]
                        OfferCard(
                            offer = offer,
                            property = property,
                            onClick = { viewModel.selectOffer(offer) },
                            onSendGmail = { viewModel.sendOffer(context, offer.id) }
                        )
                    }
                }
            }

            // Offer Detail Dialog
            state.selectedOffer?.let { offer ->
                val prop = state.propertyMap[offer.propertyId]
                OfferDetailDialog(
                    offer = offer,
                    property = prop,
                    isSending = state.isSendingOffer,
                    sendStatus = state.sendResultStatus,
                    onDismiss = { viewModel.selectOffer(null) },
                    onSend = { viewModel.sendOffer(context, offer.id) },
                    onOpenPdf = { offer.pdfPath?.let { path -> viewModel.openPdf(context, path) } },
                    onUpdateStatus = { newStatus -> viewModel.updateOfferStatus(offer.id, newStatus) }
                )
            }
        }
    }
}

@Composable
private fun OfferCard(
    offer: OfferEntity,
    property: PropertyEntity?,
    onClick: () -> Unit,
    onSendGmail: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(offer.id, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate400)
                StatusBadge(status = offer.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = property?.address ?: "Property #${offer.propertyId}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "${property?.city ?: ""}, ${property?.state ?: ""} • Recipient: ${offer.recipientName}",
                fontSize = 11.sp,
                color = Slate400
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Slate800)
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Offer Amount", fontSize = 10.sp, color = Slate400)
                    Text(
                        "$${String.format("%,.0f", offer.offerPrice)}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = CyanPrimary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onClick,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text("View Contract", fontSize = 11.sp)
                    }

                    if (offer.status == "READY" || offer.status == "FAILED") {
                        Button(
                            onClick = onSendGmail,
                            modifier = Modifier.height(34.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            contentPadding = PaddingValues(horizontal = 10.dp)
                        ) {
                            Icon(Icons.Filled.Mail, contentDescription = null, tint = Slate950, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Send Gmail", fontSize = 11.sp, color = Slate950, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferDetailDialog(
    offer: OfferEntity,
    property: PropertyEntity?,
    isSending: Boolean,
    sendStatus: String?,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onOpenPdf: () -> Unit,
    onUpdateStatus: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(offer.id, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusBadge(status = offer.status)
                }
                Text(property?.address ?: "", fontSize = 12.sp, color = Slate400)
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("COMMERCIAL SUMMARY", style = MaterialTheme.typography.labelSmall, color = CyanPrimary, fontWeight = FontWeight.Bold)
                    Text("Purchase Offer: $${String.format("%,.0f", offer.offerPrice)}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Earnest Money Deposit: $${String.format("%,.0f", offer.earnestMoney)}", fontSize = 12.sp, color = Slate300)
                    Text("Inspection Window: ${offer.inspectionPeriodDays} Days", fontSize = 12.sp, color = Slate300)
                    Text("Closing Timeline: ${offer.closingPeriodDays} Days", fontSize = 12.sp, color = Slate300)
                    Text("Offer Expiration: ${offer.expirationDate}", fontSize = 12.sp, color = Slate400)
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("RECIPIENT", style = MaterialTheme.typography.labelSmall, color = CyanPrimary, fontWeight = FontWeight.Bold)
                    Text("${offer.recipientName} (${offer.recipientEmail})", fontSize = 12.sp, color = Slate200)
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("GENERATED LETTER OF INTENT", style = MaterialTheme.typography.labelSmall, color = CyanPrimary, fontWeight = FontWeight.Bold)
                    Surface(
                        color = Slate800,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = offer.generatedLetterContent,
                            fontSize = 11.sp,
                            color = Slate200,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (sendStatus != null) {
                    item {
                        Text(sendStatus, fontSize = 11.sp, color = EmeraldGain, fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("STATUS LIFECYCLE CONTROLS", style = MaterialTheme.typography.labelSmall, color = Slate400, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { onUpdateStatus("OPENED") },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate700),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Opened", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { onUpdateStatus("SIGNED") },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGain),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Signed", fontSize = 10.sp, color = Slate950, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onUpdateStatus("DECLINED") },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Declined", fontSize = 10.sp)
                        }
                        Button(
                            onClick = { onUpdateStatus("EXPIRED") },
                            modifier = Modifier.weight(1f).height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Expired", fontSize = 10.sp, color = Slate400)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offer.pdfPath != null) {
                    OutlinedButton(onClick = onOpenPdf) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open PDF", fontSize = 12.sp)
                    }
                }
                Button(
                    onClick = onSend,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Slate950, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Send, contentDescription = null, tint = Slate950, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send Gmail", color = Slate950, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
