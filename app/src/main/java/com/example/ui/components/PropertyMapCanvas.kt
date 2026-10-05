package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.entity.PropertyEntity
import com.example.ui.theme.*

@Composable
fun PropertyMapCanvas(
    properties: List<PropertyEntity>,
    selectedPropertyId: String?,
    onSelectProperty: (PropertyEntity) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAnalyzer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeProperty by remember(selectedPropertyId, properties) {
        mutableStateOf(properties.find { it.id == selectedPropertyId } ?: properties.firstOrNull())
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Map Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(Slate950)
                .pointerInput(properties) {
                    detectTapGestures { tapOffset ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()

                        // Find closest property pin
                        val minLat = properties.minOfOrNull { it.latitude } ?: 25.0
                        val maxLat = properties.maxOfOrNull { it.latitude } ?: 35.0
                        val minLng = properties.minOfOrNull { it.longitude } ?: -115.0
                        val maxLng = properties.maxOfOrNull { it.longitude } ?: -80.0

                        val latSpan = (maxLat - minLat).coerceAtLeast(0.5)
                        val lngSpan = (maxLng - minLng).coerceAtLeast(0.5)

                        var closest: PropertyEntity? = null
                        var minDist = Float.MAX_VALUE

                        properties.forEach { p ->
                            val normX = ((p.longitude - minLng) / lngSpan).toFloat()
                            val normY = 1.0f - ((p.latitude - minLat) / latSpan).toFloat()

                            val px = 40f + normX * (w - 80f)
                            val py = 40f + normY * (h - 180f)

                            val dist = (tapOffset.x - px) * (tapOffset.x - px) + (tapOffset.y - py) * (tapOffset.y - py)
                            if (dist < 40f * 40f && dist < minDist) {
                                minDist = dist
                                closest = p
                            }
                        }

                        if (closest != null) {
                            activeProperty = closest
                            onSelectProperty(closest!!)
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            // Draw stylized radar grid lines
            for (i in 1..6) {
                val y = (h / 7) * i
                drawLine(
                    color = Slate800.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }
            for (i in 1..5) {
                val x = (w / 6) * i
                drawLine(
                    color = Slate800.copy(alpha = 0.4f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
            }

            if (properties.isEmpty()) return@Canvas

            val minLat = properties.minOf { it.latitude }
            val maxLat = properties.maxOf { it.latitude }
            val minLng = properties.minOf { it.longitude }
            val maxLng = properties.maxOf { it.longitude }

            val latSpan = (maxLat - minLat).coerceAtLeast(0.5)
            val lngSpan = (maxLng - minLng).coerceAtLeast(0.5)

            // Draw Pins
            properties.forEach { p ->
                val normX = ((p.longitude - minLng) / lngSpan).toFloat()
                val normY = 1.0f - ((p.latitude - minLat) / latSpan).toFloat()

                val px = 40f + normX * (w - 80f)
                val py = 40f + normY * (h - 180f)

                val isSelected = p.id == activeProperty?.id
                val pinColor = when {
                    p.isSavedDeal || p.dealScore >= 80 -> EmeraldGain
                    p.sourceType == "OFF_MARKET" -> AmberAccent
                    else -> CyanPrimary
                }

                if (isSelected) {
                    // Pulsing outer halo
                    drawCircle(
                        color = pinColor.copy(alpha = 0.25f),
                        radius = 24.dp.toPx(),
                        center = Offset(px, py)
                    )
                    drawCircle(
                        color = pinColor.copy(alpha = 0.45f),
                        radius = 16.dp.toPx(),
                        center = Offset(px, py),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Core Pin
                drawCircle(
                    color = Slate950,
                    radius = 10.dp.toPx(),
                    center = Offset(px, py)
                )
                drawCircle(
                    color = pinColor,
                    radius = 8.dp.toPx(),
                    center = Offset(px, py)
                )
                drawCircle(
                    color = Color.White,
                    radius = 3.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }

        // Top Legend Overlay
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.90f)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MapLegendItem(color = EmeraldGain, label = "Qualified Deal")
                MapLegendItem(color = CyanPrimary, label = "On-Market")
                MapLegendItem(color = AmberAccent, label = "Off-Market")
            }
        }

        // Bottom Selected Property Preview Card
        activeProperty?.let { p ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = p.primaryImageUrl,
                        contentDescription = p.address,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$${String.format("%,.0f", p.price)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            StatusBadge(status = p.sourceType)
                        }

                        Text(
                            text = p.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Text(
                            text = "${p.bedrooms} Beds • ${p.bathrooms} Baths • ${p.city}, ${p.state}",
                            fontSize = 11.sp,
                            color = Slate400
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onNavigateToAnalyzer(p.id) },
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Analyze", fontSize = 11.sp)
                            }

                            Button(
                                onClick = { onNavigateToDetail(p.id) },
                                modifier = Modifier.height(30.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CyanDark),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Details", fontSize = 11.sp)
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(Icons.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, color = Slate200)
    }
}
