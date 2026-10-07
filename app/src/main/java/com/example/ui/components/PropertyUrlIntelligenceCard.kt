package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.intelligence.job.JobProgressState
import com.example.domain.intelligence.job.JobStatus
import com.example.ui.theme.*

@Composable
fun PropertyUrlIntelligenceCard(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onAnalyze: (forceRefresh: Boolean) -> Unit,
    jobProgress: JobProgressState?,
    onCancelJob: (String) -> Unit,
    onClearJob: () -> Unit,
    onOpenDealRoom: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("url_intelligence_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(CyanPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = CyanPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "US Property URL Intelligence",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Instant ingestion, underwriting & AI Deal Room",
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                }
                StatusBadge(status = "ACTIVE")
            }

            // URL Input field
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlChange,
                placeholder = {
                    Text("Paste Property URL (e.g. Zillow, Redfin, Realtor.com)", fontSize = 12.sp, color = Slate400)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = CyanPrimary)
                },
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { onUrlChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = Slate400)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = CyanPrimary,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paste_property_url_input")
            )

            // Quick Samples Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Samples:",
                    fontSize = 11.sp,
                    color = Slate400,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                SampleChip(label = "Zillow") {
                    onUrlChange("https://www.zillow.com/homedetails/1420-S-Congress-Ave-Austin-TX-78704/29481920_zpid/")
                }
                SampleChip(label = "Redfin") {
                    onUrlChange("https://www.redfin.com/TX/Austin/2804-E-4th-St-78702/home/9876543")
                }
                SampleChip(label = "Realtor") {
                    onUrlChange("https://www.realtor.com/realestateandhomes-detail/1105-Nueces-St_Austin_TX_78701_M1105")
                }
                SampleChip(label = "Homes.com") {
                    onUrlChange("https://www.homes.com/property/5204-menchaca-rd-austin-tx/abc123xyz/")
                }
            }

            // Action Button
            val isJobRunning = jobProgress != null &&
                    jobProgress.status != JobStatus.COMPLETED &&
                    jobProgress.status != JobStatus.FAILED

            Button(
                onClick = { onAnalyze(false) },
                enabled = urlInput.isNotBlank() && !isJobRunning,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyanPrimary,
                    contentColor = Slate950
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("analyze_property_button")
            ) {
                if (isJobRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Slate950
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyzing Pipeline...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Analyze Property", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Progress & Ingestion Job State UI
            if (jobProgress != null) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("job_progress_container")
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val statusColor = when (jobProgress.status) {
                                    JobStatus.COMPLETED -> EmeraldGain
                                    JobStatus.FAILED -> CrimsonAlert
                                    else -> CyanPrimary
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = "Status: ${jobProgress.status.name}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                            Text(
                                text = "${(jobProgress.progressPercent * 100).toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate300
                            )
                        }

                        // Progress Bar
                        LinearProgressIndicator(
                            progress = { jobProgress.progressPercent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = when (jobProgress.status) {
                                JobStatus.COMPLETED -> EmeraldGain
                                JobStatus.FAILED -> CrimsonAlert
                                else -> CyanPrimary
                            },
                            trackColor = Slate800
                        )

                        // Stepper row showing progress through stages
                        JobStepsRow(currentStatus = jobProgress.status)

                        Text(
                            text = jobProgress.currentStepDescription,
                            fontSize = 11.sp,
                            color = Slate300
                        )

                        // Failure message if any
                        if (jobProgress.status == JobStatus.FAILED && jobProgress.errorMessage != null) {
                            Text(
                                text = "Error [${jobProgress.errorCode}]: ${jobProgress.errorMessage}",
                                fontSize = 11.sp,
                                color = CrimsonAlert,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Action Buttons based on status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (jobProgress.status == JobStatus.COMPLETED && jobProgress.propertyId != null) {
                                Button(
                                    onClick = { onOpenDealRoom(jobProgress.propertyId) },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGain),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.testTag("open_deal_room_button")
                                ) {
                                    Icon(Icons.Filled.Launch, contentDescription = null, modifier = Modifier.size(14.dp), tint = Slate950)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Open Deal Room", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate950)
                                }
                            } else if (jobProgress.status == JobStatus.FAILED) {
                                OutlinedButton(
                                    onClick = onClearJob,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Dismiss", fontSize = 11.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = { onAnalyze(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Retry", fontSize = 11.sp, color = Slate950, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                TextButton(onClick = { onCancelJob(jobProgress.jobId) }) {
                                    Text("Cancel", fontSize = 11.sp, color = CrimsonAlert)
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
private fun JobStepsRow(currentStatus: JobStatus) {
    val steps = listOf("QUEUED", "FETCH", "PARSE", "NORM", "ENRICH", "AI", "DONE")
    val currentIndex = when (currentStatus) {
        JobStatus.QUEUED -> 0
        JobStatus.FETCHING -> 1
        JobStatus.PARSING -> 2
        JobStatus.NORMALIZING -> 3
        JobStatus.ENRICHING -> 4
        JobStatus.ANALYZING -> 5
        JobStatus.COMPLETED -> 6
        JobStatus.FAILED -> -1
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        steps.forEachIndexed { index, stepName ->
            val isPassed = currentIndex >= index
            val isCurrent = currentIndex == index
            Text(
                text = stepName,
                fontSize = 9.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    currentStatus == JobStatus.FAILED && isCurrent -> CrimsonAlert
                    isPassed -> EmeraldGain
                    isCurrent -> CyanPrimary
                    else -> Slate400
                }
            )
        }
    }
}

@Composable
private fun SampleChip(label: String, onClick: () -> Unit) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(label, fontSize = 10.sp) },
        shape = RoundedCornerShape(8.dp),
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    )
}
