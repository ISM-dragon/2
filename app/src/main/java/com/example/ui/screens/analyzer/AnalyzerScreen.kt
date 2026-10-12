package com.example.ui.screens.analyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.domain.finance.strategy.InvestmentStrategyType
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    propertyId: String,
    onBack: () -> Unit,
    viewModel: AnalyzerViewModel = viewModel()
) {
    LaunchedEffect(propertyId) {
        viewModel.loadProperty(propertyId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCopyFeedback by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Institutional Underwriting Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(state.property?.address ?: "Property Analysis", fontSize = 11.sp, color = Slate400)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val memo = generateDealMemo(state)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Deal Memo", memo))
                            showCopyFeedback = true
                        }
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share Memo", tint = CyanPrimary)
                    }
                    IconButton(
                        onClick = {
                            viewModel.saveAnalysis()
                        }
                    ) {
                        Icon(Icons.Filled.Bookmark, contentDescription = "Save Analysis", tint = AmberAccent)
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
            // Strategy Selection Tabs
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val strategies = listOf(
                        Triple(InvestmentStrategyType.BUY_AND_HOLD, "🏢 Long-Term Rental", "تأجير طويل المدى"),
                        Triple(InvestmentStrategyType.BRRRR, "🔨 BRRRR Strategy", "شراء-ترميم-إعادة تمويل"),
                        Triple(InvestmentStrategyType.FIX_AND_FLIP, "🔄 Fix & Flip", "شراء وتجديد وبيع"),
                        Triple(InvestmentStrategyType.WHOLESALE, "🏷️ Wholesale MAO", "وساطة وعرض أقصى")
                    )

                    items(strategies) { (strat, title, arabic) ->
                        val isSelected = state.selectedStrategy == strat
                        Card(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { viewModel.setStrategy(strat) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) CyanPrimary else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(
                                    text = title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Slate950 else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = arabic,
                                    fontSize = 9.sp,
                                    color = if (isSelected) Slate900 else Slate400
                                )
                            }
                        }
                    }
                }
            }

            // Strategy Display Body
            when (state.selectedStrategy) {
                InvestmentStrategyType.BUY_AND_HOLD -> {
                    val bnh = state.buyAndHoldResult
                    val inputs = state.buyAndHoldInputs

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("PROJECTED CASH FLOW & RETURN", style = MaterialTheme.typography.labelMedium, color = Slate400, fontWeight = FontWeight.Bold)
                                    if (bnh.onePercentRuleMet) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(EmeraldGain.copy(alpha = 0.2f))
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text("1% RULE MET", color = EmeraldGain, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Monthly Net Cash Flow", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "$${String.format("%,.0f", bnh.monthlyCashFlow)}/mo",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (bnh.monthlyCashFlow >= 0) EmeraldGain else CrimsonAlert
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Cash-on-Cash ROI", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "${String.format("%.2f", bnh.cashOnCashReturn)}%",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CyanPrimary
                                        )
                                    }
                                }

                                Divider(color = Slate800, modifier = Modifier.padding(vertical = 14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    KeyMetricColumn(label = "Cap Rate", value = "${String.format("%.2f", bnh.capRate)}%")
                                    KeyMetricColumn(label = "DSCR Coverage", value = String.format("%.2f", bnh.dscr))
                                    KeyMetricColumn(label = "Gross Rent Mult.", value = "${String.format("%.1f", bnh.grossRentMultiplier)}x")
                                    KeyMetricColumn(label = "Total Cash In", value = "$${String.format("%,.0f", bnh.totalCashInvested)}")
                                }
                            }
                        }
                    }

                    // Interactive Sensitivity Sliders
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("INTERACTIVE STRESS-TEST & SENSITIVITY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate400)
                                Spacer(modifier = Modifier.height(12.dp))

                                // Interest Rate Slider
                                Text("Mortgage Interest Rate: ${String.format("%.2f", inputs.interestRatePct)}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.interestRatePct.toFloat(),
                                    onValueChange = { viewModel.updateSensitivity(interestRate = it.toDouble()) },
                                    valueRange = 4.0f..11.0f,
                                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Vacancy Rate Slider
                                Text("Vacancy Allowance: ${String.format("%.1f", inputs.vacancyRatePct)}%", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.vacancyRatePct.toFloat(),
                                    onValueChange = { viewModel.updateSensitivity(vacancyRate = it.toDouble()) },
                                    valueRange = 0.0f..15.0f,
                                    colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Down Payment Slider
                                Text("Down Payment: ${String.format("%.0f", inputs.downPaymentPct)}% ($${String.format("%,.0f", inputs.purchasePrice * inputs.downPaymentPct / 100.0)})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.downPaymentPct.toFloat(),
                                    onValueChange = { viewModel.updateSensitivity(downPaymentPct = it.toDouble()) },
                                    valueRange = 0.0f..50.0f,
                                    colors = SliderDefaults.colors(thumbColor = EmeraldGain, activeTrackColor = EmeraldGain)
                                )
                            }
                        }
                    }

                    // 10-Year Wealth Growth Forecast
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("10-YEAR WEALTH ACCUMULATION FORECAST", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate400)
                                Text("Amortization paydown + 4% appreciation + 3% rent growth", fontSize = 11.sp, color = Slate400)
                                Spacer(modifier = Modifier.height(14.dp))

                                val milestonesToShow = state.wealthForecast.filter { it.year in listOf(1, 3, 5, 7, 10) }
                                milestonesToShow.forEach { ms ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Year ${ms.year}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = CyanPrimary)
                                        Text("Val: $${String.format("%,.0f", ms.propertyValue)}", fontSize = 11.sp, color = Slate400)
                                        Text("Equity: $${String.format("%,.0f", ms.totalEquity)}", fontSize = 11.sp, color = Slate200)
                                        Text(
                                            text = "Wealth: $${String.format("%,.0f", ms.totalWealthCreated)}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = EmeraldGain
                                        )
                                    }
                                    if (ms.year != 10) Divider(color = Slate800, modifier = Modifier.padding(vertical = 2.dp))
                                }
                            }
                        }
                    }
                }

                InvestmentStrategyType.BRRRR -> {
                    val brrrr = state.brrrrResult
                    val inputs = state.brrrrInputs

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("BRRRR CAPITAL RECYCLING SUMMARY", style = MaterialTheme.typography.labelMedium, color = Slate400, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Net Capital Left in Deal", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = if (brrrr.isInfiniteReturn) "$0 (100% Cash Out!)" else "$${String.format("%,.0f", brrrr.netCapitalLeftInDeal)}",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (brrrr.isInfiniteReturn) EmeraldGain else CyanPrimary
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Post-Refi Monthly Cash Flow", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "$${String.format("%,.0f", brrrr.postRefiMonthlyCashFlow)}/mo",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EmeraldGain
                                        )
                                    }
                                }

                                Divider(color = Slate800, modifier = Modifier.padding(vertical = 14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    KeyMetricColumn(label = "Initial Invested", value = "$${String.format("%,.0f", brrrr.totalInitialCashInvested)}")
                                    KeyMetricColumn(label = "New Refi Loan", value = "$${String.format("%,.0f", brrrr.newLoanAmount)}")
                                    KeyMetricColumn(label = "Instant Equity", value = "$${String.format("%,.0f", brrrr.instantEquityCreated)}")
                                    KeyMetricColumn(label = "Capital Recovered", value = "${String.format("%.1f", brrrr.capitalRecoveryPct)}%")
                                }
                            }
                        }
                    }

                    // BRRRR Sliders
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("RENOVATION & ARV CALIBRATION", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate400)
                                Spacer(modifier = Modifier.height(10.dp))

                                Text("Estimated Rehab Budget: $${String.format("%,.0f", inputs.rehabCost)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.rehabCost.toFloat(),
                                    onValueChange = { viewModel.updateBrrrrInputs(rehabCost = it.toDouble()) },
                                    valueRange = 10000.0f..150000.0f,
                                    colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Post-Rehab ARV: $${String.format("%,.0f", inputs.arv)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.arv.toFloat(),
                                    onValueChange = { viewModel.updateBrrrrInputs(arv = it.toDouble()) },
                                    valueRange = 250000.0f..850000.0f,
                                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                                )
                            }
                        }
                    }
                }

                InvestmentStrategyType.FIX_AND_FLIP -> {
                    val flip = state.fixAndFlipResult
                    val inputs = state.fixAndFlipInputs

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("FIX & FLIP PROFITABILITY STATEMENT", style = MaterialTheme.typography.labelMedium, color = Slate400, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Net Projected Profit", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "$${String.format("%,.0f", flip.netProfit)}",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (flip.isProfitable) EmeraldGain else CrimsonAlert
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Return on Investment (ROI)", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "${String.format("%.1f", flip.returnOnInvestmentPct)}%",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CyanPrimary
                                        )
                                    }
                                }

                                Divider(color = Slate800, modifier = Modifier.padding(vertical = 14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    KeyMetricColumn(label = "Annualized ROI", value = "${String.format("%.1f", flip.annualizedRoiPct)}%")
                                    KeyMetricColumn(label = "70% Rule MAO", value = "$${String.format("%,.0f", flip.seventyPercentRuleMao)}")
                                    KeyMetricColumn(label = "Financing Fees", value = "$${String.format("%,.0f", flip.financingFees)}")
                                    KeyMetricColumn(label = "Selling Fees", value = "$${String.format("%,.0f", flip.sellingCosts)}")
                                }
                            }
                        }
                    }

                    // Flip Sliders
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("FLIP PARAMETERS ADJUSTMENT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate400)
                                Spacer(modifier = Modifier.height(10.dp))

                                Text("Rehab Budget: $${String.format("%,.0f", inputs.rehabBudget)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.rehabBudget.toFloat(),
                                    onValueChange = { viewModel.updateFlipInputs(rehabBudget = it.toDouble()) },
                                    valueRange = 10000.0f..120000.0f,
                                    colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Expected Sale Price (ARV): $${String.format("%,.0f", inputs.expectedSalePrice)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.expectedSalePrice.toFloat(),
                                    onValueChange = { viewModel.updateFlipInputs(salePrice = it.toDouble()) },
                                    valueRange = 250000.0f..800000.0f,
                                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                                )
                            }
                        }
                    }
                }

                InvestmentStrategyType.WHOLESALE -> {
                    val ws = state.wholesaleResult
                    val inputs = state.wholesaleInputs

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("WHOLESALE MAXIMUM ALLOWABLE OFFER (MAO)", style = MaterialTheme.typography.labelMedium, color = Slate400, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text("Maximum Allowable Offer (MAO)", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "$${String.format("%,.0f", ws.maxAllowableOffer)}",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EmeraldGain
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Your Assignment Fee", fontSize = 11.sp, color = Slate400)
                                        Text(
                                            text = "$${String.format("%,.0f", ws.assignmentFee)}",
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = AmberAccent
                                        )
                                    }
                                }

                                Divider(color = Slate800, modifier = Modifier.padding(vertical = 14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    KeyMetricColumn(label = "End-Buyer Price", value = "$${String.format("%,.0f", ws.buyerPurchasePrice)}")
                                    KeyMetricColumn(label = "Investor Spread", value = "$${String.format("%,.0f", ws.targetInvestorEquity)}")
                                    KeyMetricColumn(label = "Low Offer Target", value = "$${String.format("%,.0f", ws.recommendedOfferRangeLow)}")
                                    KeyMetricColumn(label = "High Offer Cap", value = "$${String.format("%,.0f", ws.recommendedOfferRangeHigh)}")
                                }
                            }
                        }
                    }

                    // Wholesale Sliders
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text("ASSIGNMENT FEE & REPAIR ESTIMATES", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Slate400)
                                Spacer(modifier = Modifier.height(10.dp))

                                Text("Desired Wholesale Fee: $${String.format("%,.0f", inputs.desiredAssignmentFee)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.desiredAssignmentFee.toFloat(),
                                    onValueChange = { viewModel.updateWholesaleInputs(fee = it.toDouble()) },
                                    valueRange = 5000.0f..35000.0f,
                                    colors = SliderDefaults.colors(thumbColor = AmberAccent, activeTrackColor = AmberAccent)
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text("Estimated Repairs: $${String.format("%,.0f", inputs.estimatedRepairs)}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Slider(
                                    value = inputs.estimatedRepairs.toFloat(),
                                    onValueChange = { viewModel.updateWholesaleInputs(repairs = it.toDouble()) },
                                    valueRange = 10000.0f..100000.0f,
                                    colors = SliderDefaults.colors(thumbColor = CyanPrimary, activeTrackColor = CyanPrimary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCopyFeedback) {
        AlertDialog(
            onDismissRequest = { showCopyFeedback = false },
            title = { Text("Deal Memo Copied", fontWeight = FontWeight.Bold) },
            text = { Text("The full institutional financial deal memo has been copied to your clipboard, ready to send via Email or WhatsApp.") },
            confirmButton = {
                Button(onClick = { showCopyFeedback = false }, colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary)) {
                    Text("Done", color = Slate950)
                }
            }
        )
    }
}

@Composable
private fun KeyMetricColumn(label: String, value: String) {
    Column {
        Text(label, fontSize = 10.sp, color = Slate400)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Slate200)
    }
}

private fun generateDealMemo(state: AnalyzerUiState): String {
    val prop = state.property
    val bnh = state.buyAndHoldResult
    return """
        ====================================================
        REAL ESTATE AI - INSTITUTIONAL DEAL MEMO
        ====================================================
        Property: ${prop?.address ?: "Target Property"}
        Location: ${prop?.city ?: ""}, ${prop?.state ?: ""}
        Purchase Price: $${String.format("%,.0f", state.buyAndHoldInputs.purchasePrice)}
        Estimated Rent: $${String.format("%,.0f", state.buyAndHoldInputs.monthlyRent)}/month

        KEY FINANCIAL RETURNS (BUY & HOLD):
        ----------------------------------------------------
        * Monthly Net Cash Flow: $${String.format("%,.0f", bnh.monthlyCashFlow)}/mo
        * Annual Net Cash Flow:  $${String.format("%,.0f", bnh.annualCashFlow)}/yr
        * Cap Rate:              ${String.format("%.2f", bnh.capRate)}%
        * Cash-on-Cash Return:   ${String.format("%.2f", bnh.cashOnCashReturn)}%
        * Debt Coverage (DSCR):  ${String.format("%.2f", bnh.dscr)}
        * Gross Rent Multiplier: ${String.format("%.1f", bnh.grossRentMultiplier)}x

        BRRRR / VALUE-ADD METRICS:
        ----------------------------------------------------
        * Target ARV:            $${String.format("%,.0f", state.brrrrInputs.arv)}
        * Post-Refi Cash Flow:   $${String.format("%,.0f", state.brrrrResult.postRefiMonthlyCashFlow)}/mo
        * Instant Equity:        $${String.format("%,.0f", state.brrrrResult.instantEquityCreated)}

        WHOLESALE MAO:
        ----------------------------------------------------
        * Max Allowable Offer:   $${String.format("%,.0f", state.wholesaleResult.maxAllowableOffer)}
        ====================================================
    """.trimIndent()
}
