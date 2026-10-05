package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor) = when (status.uppercase()) {
        "QUALIFIED", "ACTIVE", "READY", "SENT", "SIGNED", "SUCCESS" -> Pair(EmeraldGain.copy(alpha = 0.15f), EmeraldGain)
        "PENDING", "GENERATED", "COOLDOWN", "WARN", "MODERATE" -> Pair(AmberAccent.copy(alpha = 0.15f), AmberAccent)
        "FAILED", "DECLINED", "ERROR", "EXPIRED", "STOPPED", "DISABLED" -> Pair(CrimsonAlert.copy(alpha = 0.15f), CrimsonAlert)
        "ON_MARKET" -> Pair(CyanPrimary.copy(alpha = 0.15f), CyanPrimary)
        "OFF_MARKET" -> Pair(PurpleAccent.copy(alpha = 0.15f), PurpleAccent)
        else -> Pair(Slate400.copy(alpha = 0.15f), Slate200)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = status.replace("_", " "),
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
