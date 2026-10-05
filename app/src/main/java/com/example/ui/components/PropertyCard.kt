package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.PropertyEntity
import com.example.ui.theme.*

@Composable
fun PropertyCard(
    property: PropertyEntity,
    onSelect: () -> Unit,
    onAnalyze: () -> Unit,
    onDraftOffer: () -> Unit,
    onToggleSave: () -> Unit,
    modifier: Modifier = Modifier,
    estimatedRent: Double? = null,
    cashFlow: Double? = null,
    capRate: Double? = null,
    dscr: Double? = null
) {
    Card(
        modifier = modifier
            .testTag("property_card_${property.id}")
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Image with Overlays
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                AsyncImage(
                    model = property.primaryImageUrl,
                    contentDescription = property.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Top Bar in Image
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadge(status = property.sourceType)

                    if (property.dealScore > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (property.dealScore >= 80) EmeraldGain else AmberAccent)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Score: ${property.dealScore}",
                                color = Slate950,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                // Price Badge at Bottom of Image
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Slate950.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "$${String.format("%,.0f", property.price)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Save Icon Button
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Slate950.copy(alpha = 0.75f))
                ) {
                    Icon(
                        imageVector = if (property.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = "Save Property",
                        tint = if (property.isSaved) AmberAccent else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Property Details
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = property.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "${property.city}, ${property.state} ${property.zipCode} • ${property.propertyType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate400
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Beds / Baths / SqFt
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${property.bedrooms} Beds",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "•", color = Slate600)
                    Text(
                        text = "${property.bathrooms} Baths",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(text = "•", color = Slate600)
                    Text(
                        text = "${property.squareFeet} SqFt",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = Slate800)
                Spacer(modifier = Modifier.height(10.dp))

                // Underwriting Metrics Strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val rentVal = estimatedRent ?: (property.price * 0.008)
                    val flowVal = cashFlow ?: (rentVal * 0.35 - (property.price * 0.004))
                    val capVal = capRate ?: ((rentVal * 12 * 0.60 / property.price) * 100.0)
                    val dscrVal = dscr ?: 1.35

                    MiniMetric(label = "Est Rent", value = "$${String.format("%,.0f", rentVal)}/mo")
                    MiniMetric(
                        label = "Cash Flow",
                        value = "$${String.format("%,.0f", flowVal)}/mo",
                        textColor = if (flowVal > 0) EmeraldGain else CrimsonAlert
                    )
                    MiniMetric(label = "Cap Rate", value = "${String.format("%.1f", capVal)}%")
                    MiniMetric(label = "DSCR", value = String.format("%.2f", dscrVal))
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onAnalyze,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Analyze", fontSize = 12.sp)
                    }

                    Button(
                        onClick = onDraftOffer,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanDark),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Offer", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniMetric(
    label: String,
    value: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Slate400)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}
