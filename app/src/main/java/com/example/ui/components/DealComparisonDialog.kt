package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entity.PropertyEntity
import com.example.ui.theme.*

@Composable
fun DealComparisonDialog(
    properties: List<PropertyEntity>,
    onDismiss: () -> Unit,
    onSelectProperty: (String) -> Unit
) {
    if (properties.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyanPrimary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Side-by-Side Deal Comparison",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Evaluating ${properties.size} investment opportunities",
                                fontSize = 11.sp,
                                color = Slate400
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val scrollState = rememberScrollState()

                // Comparative Table
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalScroll(scrollState)
                    ) {
                        // Header Row with Property Addresses
                        Row(modifier = Modifier.padding(vertical = 8.dp)) {
                            Box(modifier = Modifier.width(140.dp)) {
                                Text("Metric", fontWeight = FontWeight.Bold, color = Slate400, fontSize = 12.sp)
                            }
                            properties.forEach { prop ->
                                Card(
                                    modifier = Modifier
                                        .width(180.dp)
                                        .padding(horizontal = 4.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = prop.address,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${prop.city}, ${prop.state}",
                                            fontSize = 10.sp,
                                            color = Slate400
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Button(
                                            onClick = { onSelectProperty(prop.id) },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.fillMaxWidth().height(28.dp)
                                        ) {
                                            Text("Underwrite", color = Slate950, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Divider(color = Slate800, modifier = Modifier.padding(vertical = 4.dp))

                        val lowestPrice = properties.minOfOrNull { it.price } ?: 0.0
                        val highestScore = properties.maxOfOrNull { it.dealScore } ?: 0
                        val bestSqFtPrice = properties.minOfOrNull { if (it.squareFeet > 0) it.price / it.squareFeet else 99999.0 } ?: 0.0

                        LazyColumn(modifier = Modifier.weight(1f)) {
                            item {
                                ComparisonMetricRow(
                                    label = "Purchase Price",
                                    values = properties.map { "$${String.format("%,.0f", it.price)}" },
                                    bestIndex = properties.indexOfFirst { it.price == lowestPrice }
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Deal Score",
                                    values = properties.map { "${it.dealScore}/100" },
                                    bestIndex = properties.indexOfFirst { it.dealScore == highestScore }
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Property Type",
                                    values = properties.map { it.propertyType },
                                    bestIndex = -1
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Bed / Bath",
                                    values = properties.map { "${it.bedrooms} beds / ${it.bathrooms.toInt()} baths" },
                                    bestIndex = -1
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Living Area",
                                    values = properties.map { "${it.squareFeet} sq ft" },
                                    bestIndex = -1
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Price / Sq Ft",
                                    values = properties.map {
                                        val pps = if (it.squareFeet > 0) it.price / it.squareFeet else 0.0
                                        "$${String.format("%.0f", pps)}/sq ft"
                                    },
                                    bestIndex = properties.indexOfFirst { (if (it.squareFeet > 0) it.price / it.squareFeet else 99999.0) == bestSqFtPrice }
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Source Category",
                                    values = properties.map { it.sourceType },
                                    bestIndex = -1
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Est. Monthly Rent",
                                    values = properties.map {
                                        val rent = it.price * 0.0075
                                        "$${String.format("%,.0f", rent)}/mo"
                                    },
                                    bestIndex = -1
                                )
                            }
                            item {
                                ComparisonMetricRow(
                                    label = "Cap Rate Est.",
                                    values = properties.map {
                                        val cap = ((it.price * 0.0075 * 12 * 0.65) / it.price) * 100.0
                                        "${String.format("%.1f", cap)}%"
                                    },
                                    bestIndex = 0
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Slate800)
                    ) {
                        Text("Close Comparison", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonMetricRow(
    label: String,
    values: List<String>,
    bestIndex: Int
) {
    Row(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(140.dp)) {
            Text(label, fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.SemiBold)
        }
        values.forEachIndexed { index, value ->
            val isBest = (index == bestIndex)
            Box(
                modifier = Modifier
                    .width(180.dp)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isBest) EmeraldGain.copy(alpha = 0.12f) else Color.Transparent)
                    .border(
                        width = if (isBest) 1.dp else 0.dp,
                        color = if (isBest) EmeraldGain else Color.Transparent,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = value,
                        fontSize = 12.sp,
                        fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                        color = if (isBest) EmeraldGain else MaterialTheme.colorScheme.onSurface
                    )
                    if (isBest) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Filled.Star, contentDescription = "Best", tint = EmeraldGain, modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}
