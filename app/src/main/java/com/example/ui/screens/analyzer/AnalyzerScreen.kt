package com.example.ui.screens.analyzer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
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
import com.example.domain.finance.FinancialResult
import com.example.ui.components.CashFlowBreakdownBar
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
    val res = state.result
    val input = state.input

    var savedFeedback by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Deterministic Financial Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(state.property?.address ?: "Property Underwriting", fontSize = 11.sp, color = Slate400)
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
                            viewModel.saveAnalysis()
                            savedFeedback = true
                        }
                    ) {
                        Icon(if (savedFeedback) Icons.Filled.Check else Icons.Filled.Bookmark, contentDescription = "Save Analysis", tint = CyanPrimary)
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
            // Core Returns Highlight Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PROJECTED ANNUAL RETURNS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Slate400,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Monthly Cash Flow", fontSize = 11.sp, color = Slate400)
                                Text(
                                    text = "$${String.format("%,.0f", res.monthlyCashFlow)}",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (res.monthlyCashFlow > 0) EmeraldGain else CrimsonAlert
                                )
                                Text(
                                    text = "$${String.format("%,.0f", res.annualCashFlow)}/yr",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Cap Rate", fontSize = 11.sp, color = Slate400)
                                Text(
                                    text = "${String.format("%.2f", res.capRate)}%",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyanPrimary
                                )
                                Text(
                                    text = "NOI: $${String.format("%,.0f", res.noiAnnual)}/yr",
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Slate800)
                        Spacer(modifier = Modifier.height(12.dp))

                        // Secondary Metrics Grid
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            ReturnItem(label = "Cash-on-Cash", value = "${String.format("%.2f", res.cashOnCashReturn)}%", color = EmeraldGain)
                            ReturnItem(label = "DSCR Coverage", value = String.format("%.2f", res.dscr), color = AmberAccent)
                            ReturnItem(label = "Break-Even", value = "${String.format("%.1f", res.breakEvenOccupancyPct)}%", color = Slate200)
                            ReturnItem(label = "Cash Needed", value = "$${String.format("%,.0f", res.totalCashRequired)}", color = Slate200)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        CashFlowBreakdownBar(result = res)
                    }
                }
            }

            // Financing Scenarios Comparison Carousel
            item {
                Column {
                    Text(
                        text = "FINANCING SCENARIO COMPARISON",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Slate400,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.comparisonScenarios) { (scenarioName, sRes) ->
                            ScenarioComparisonCard(
                                name = scenarioName,
                                result = sRes,
                                isCurrent = input.downPaymentPct == sRes.input.downPaymentPct
                            )
                        }
                    }
                }
            }

            // Section 1: Purchase & Renovation
            item {
                FinancialInputSection(title = "1. PURCHASE & ACQUISITION") {
                    NumberField(
                        label = "Purchase Price ($)",
                        value = input.purchasePrice,
                        onValueChange = { viewModel.updateInput(input.copy(purchasePrice = it)) }
                    )
                    NumberField(
                        label = "Closing Costs ($)",
                        value = input.closingCosts,
                        onValueChange = { viewModel.updateInput(input.copy(closingCosts = it)) }
                    )
                    NumberField(
                        label = "Renovation / Rehab ($)",
                        value = input.renovationCost,
                        onValueChange = { viewModel.updateInput(input.copy(renovationCost = it)) }
                    )
                }
            }

            // Section 2: Income
            item {
                FinancialInputSection(title = "2. RENTAL INCOME") {
                    NumberField(
                        label = "Monthly Rent ($)",
                        value = input.monthlyRent,
                        onValueChange = { viewModel.updateInput(input.copy(monthlyRent = it)) }
                    )
                    NumberField(
                        label = "Other Monthly Income ($)",
                        value = input.otherMonthlyIncome,
                        onValueChange = { viewModel.updateInput(input.copy(otherMonthlyIncome = it)) }
                    )
                    NumberField(
                        label = "Vacancy Rate (%)",
                        value = input.vacancyRatePct,
                        onValueChange = { viewModel.updateInput(input.copy(vacancyRatePct = it)) }
                    )
                }
            }

            // Section 3: Operating Expenses
            item {
                FinancialInputSection(title = "3. OPERATING EXPENSES") {
                    NumberField(
                        label = "Annual Property Tax ($)",
                        value = input.propertyTaxAnnual,
                        onValueChange = { viewModel.updateInput(input.copy(propertyTaxAnnual = it)) }
                    )
                    NumberField(
                        label = "Annual Insurance ($)",
                        value = input.insuranceAnnual,
                        onValueChange = { viewModel.updateInput(input.copy(insuranceAnnual = it)) }
                    )
                    NumberField(
                        label = "Maintenance (% of Rent)",
                        value = input.maintenancePct,
                        onValueChange = { viewModel.updateInput(input.copy(maintenancePct = it)) }
                    )
                    NumberField(
                        label = "Property Management (%)",
                        value = input.managementPct,
                        onValueChange = { viewModel.updateInput(input.copy(managementPct = it)) }
                    )
                    NumberField(
                        label = "Utilities / Common ($/mo)",
                        value = input.utilitiesMonthly,
                        onValueChange = { viewModel.updateInput(input.copy(utilitiesMonthly = it)) }
                    )
                }
            }

            // Section 4: Financing Details
            item {
                FinancialInputSection(title = "4. FINANCING & DEBT STRUCTURE") {
                    NumberField(
                        label = "Down Payment (%)",
                        value = input.downPaymentPct,
                        onValueChange = { viewModel.updateInput(input.copy(downPaymentPct = it)) }
                    )
                    NumberField(
                        label = "Interest Rate (% APR)",
                        value = input.interestRatePct,
                        onValueChange = { viewModel.updateInput(input.copy(interestRatePct = it)) }
                    )
                    NumberField(
                        label = "Loan Term (Years)",
                        value = input.loanTermYears.toDouble(),
                        onValueChange = { viewModel.updateInput(input.copy(loanTermYears = it.toInt())) }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate900)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Calculated Monthly P&I Debt:", fontSize = 12.sp, color = Slate400)
                        Text("$${String.format("%,.0f", res.monthlyDebtService)}/mo", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyanPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReturnItem(label: String, value: String, color: Color) {
    Column {
        Text(text = label, fontSize = 10.sp, color = Slate400)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun FinancialInputSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CyanPrimary)
            content()
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit
) {
    var textVal by remember(value) { mutableStateOf(String.format(if (value % 1.0 == 0.0) "%.0f" else "%.2f", value)) }

    OutlinedTextField(
        value = textVal,
        onValueChange = {
            textVal = it
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
private fun ScenarioComparisonCard(
    name: String,
    result: FinancialResult,
    isCurrent: Boolean
) {
    Card(
        modifier = Modifier
            .width(180.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) CyanDark.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (isCurrent) CyanPrimary else Slate200)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Cash Flow: $${result.monthlyCashFlow.toInt()}/mo", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (result.monthlyCashFlow > 0) EmeraldGain else CrimsonAlert)
            Text("Cap Rate: ${String.format("%.1f", result.capRate)}%", fontSize = 11.sp, color = Slate300)
            Text("Cash-on-Cash: ${String.format("%.1f", result.cashOnCashReturn)}%", fontSize = 11.sp, color = Slate300)
            Text("Cash Needed: $${String.format("%,.0f", result.totalCashRequired)}", fontSize = 11.sp, color = Slate400)
        }
    }
}
