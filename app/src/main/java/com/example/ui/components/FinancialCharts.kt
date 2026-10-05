package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.finance.FinancialResult
import com.example.ui.theme.*

@Composable
fun CashFlowBreakdownBar(
    result: FinancialResult,
    modifier: Modifier = Modifier
) {
    val grossRent = result.grossRentalIncome.coerceAtLeast(1.0)
    val debtPct = (result.monthlyDebtService / grossRent).toFloat().coerceIn(0f, 1f)
    val taxPct = ((result.input.propertyTaxAnnual / 12.0) / grossRent).toFloat().coerceIn(0f, 1f)
    val insPct = ((result.input.insuranceAnnual / 12.0) / grossRent).toFloat().coerceIn(0f, 1f)
    val maintPct = ((result.input.monthlyRent * result.input.maintenancePct / 100.0) / grossRent).toFloat().coerceIn(0f, 1f)
    val flowPct = (result.monthlyCashFlow.coerceAtLeast(0.0) / grossRent).toFloat().coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Monthly Income Allocation",
            style = MaterialTheme.typography.labelMedium,
            color = Slate400,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Stacked Progress Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Slate800)
        ) {
            if (flowPct > 0f) {
                Box(
                    modifier = Modifier
                        .weight(flowPct.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(EmeraldGain)
                )
            }
            if (debtPct > 0f) {
                Box(
                    modifier = Modifier
                        .weight(debtPct.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(CyanPrimary)
                )
            }
            if (taxPct > 0f) {
                Box(
                    modifier = Modifier
                        .weight(taxPct.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(AmberAccent)
                )
            }
            if (insPct > 0f) {
                Box(
                    modifier = Modifier
                        .weight(insPct.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(PurpleAccent)
                )
            }
            if (maintPct > 0f) {
                Box(
                    modifier = Modifier
                        .weight(maintPct.coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(CrimsonAlert)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Legend items
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LegendItem(color = EmeraldGain, label = "Cash Flow: $${result.monthlyCashFlow.toInt()}")
            LegendItem(color = CyanPrimary, label = "Debt P&I: $${result.monthlyDebtService.toInt()}")
            LegendItem(color = AmberAccent, label = "Tax/Ins: $${((result.input.propertyTaxAnnual + result.input.insuranceAnnual) / 12.0).toInt()}")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 11.sp, color = Slate400)
    }
}

@Composable
fun TrendAppreciationCanvas(
    purchasePrice: Double,
    appreciationRatePct: Double = 5.0,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "5-Year Equity & Value Projection",
            style = MaterialTheme.typography.labelMedium,
            color = Slate400,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Slate800.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            val w = size.width
            val h = size.height

            // Calculate 5 points
            val points = (0..5).map { year ->
                val futureVal = purchasePrice * Math.pow(1.0 + (appreciationRatePct / 100.0), year.toDouble())
                futureVal
            }
            val minVal = purchasePrice * 0.95
            val maxVal = points.last() * 1.05

            val path = Path()
            val fillPath = Path()

            points.forEachIndexed { i, v ->
                val x = (i.toFloat() / (points.size - 1)) * w
                val normalizedY = ((v - minVal) / (maxVal - minVal)).toFloat()
                val y = h - (normalizedY * h)

                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }

                drawCircle(
                    color = CyanPrimary,
                    radius = 3.dp.toPx(),
                    center = Offset(x, y)
                )
            }

            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(
                path = fillPath,
                color = CyanPrimary.copy(alpha = 0.15f)
            )

            drawPath(
                path = path,
                color = CyanPrimary,
                style = Stroke(width = 2.5f.dp.toPx())
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Year 0: $${String.format("%,.0f", purchasePrice)}", fontSize = 10.sp, color = Slate400)
            val yr5 = purchasePrice * Math.pow(1.0 + (appreciationRatePct / 100.0), 5.0)
            Text("Year 5: $${String.format("%,.0f", yr5)} (+${String.format("%.0f", ((yr5 - purchasePrice) / purchasePrice) * 100)}%)", fontSize = 10.sp, color = EmeraldGain, fontWeight = FontWeight.Bold)
        }
    }
}
