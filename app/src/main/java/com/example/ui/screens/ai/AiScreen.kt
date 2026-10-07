package com.example.ui.screens.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.entity.AIMessageEntity
import com.example.domain.ai.analyst.AnalystClaim
import com.example.domain.ai.analyst.AnalystClaimType
import com.example.domain.ai.analyst.AnalystDueDiligenceQuestion
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    propertyId: String = "general",
    onBack: () -> Unit,
    viewModel: AiViewModel = viewModel()
) {
    LaunchedEffect(propertyId) {
        viewModel.loadContext(propertyId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var inputMessage by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Deal Intelligence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(state.property?.address ?: "Context: General Portfolio Advisor", fontSize = 11.sp, color = Slate400)
                    }
                },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab Selector: Property Analysis | Deal Intelligence | AI Chat
            TabRow(
                selectedTabIndex = state.selectedTab,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = CyanPrimary
            ) {
                Tab(
                    selected = state.selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    text = { Text("Underwriting AI", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = state.selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    text = { Text("Deal Context", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Analytics, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = state.selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    text = { Text("AI Chat", fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Filled.Forum, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            when (state.selectedTab) {
                0 -> {
                    if (state.isAnalyzing) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = CyanPrimary)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("Validating source-backed analysis...", fontSize = 12.sp, color = Slate400)
                            }
                        }
                    } else {
                        val analysis = state.analysisResult
                        if (analysis != null) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item {
                                    AiSectionCard(title = "INVESTMENT THESIS", icon = Icons.Filled.Summarize, color = CyanPrimary) {
                                        AnalystClaimContent(analysis.investmentThesis)
                                    }
                                }

                                item {
                                    AiSectionCard(title = "SUPPORTED STRENGTHS", icon = Icons.Filled.CheckCircle, color = EmeraldGain) {
                                        if (analysis.strengths.isEmpty()) {
                                            Text("No strengths could be established from the supplied evidence.", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                        } else {
                                            analysis.strengths.forEach { claim -> AnalystClaimContent(claim) }
                                        }
                                    }
                                }

                                item {
                                    AiSectionCard(title = "RISKS", icon = Icons.Filled.Warning, color = CrimsonAlert) {
                                        if (analysis.risks.isEmpty()) {
                                            Text("No evidence-backed risks were returned.", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                        } else {
                                            analysis.risks.forEach { claim -> AnalystClaimContent(claim) }
                                        }
                                    }
                                }

                                item {
                                    AiSectionCard(title = "RED FLAGS", icon = Icons.Filled.ReportProblem, color = CrimsonAlert) {
                                        if (analysis.redFlags.isEmpty()) {
                                            Text("No red flag was established from the supplied evidence.", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                        } else {
                                            analysis.redFlags.forEach { claim -> AnalystClaimContent(claim) }
                                        }
                                    }
                                }

                                item {
                                    AiSectionCard(title = "UNKNOWN / NOT PROVIDED", icon = Icons.Filled.HelpOutline, color = AmberAccent) {
                                        if (analysis.unknowns.isEmpty()) {
                                            Text("No explicit data gaps were reported.", style = MaterialTheme.typography.bodySmall, color = Slate400)
                                        } else {
                                            analysis.unknowns.forEach { claim -> AnalystClaimContent(claim) }
                                        }
                                    }
                                }

                                item {
                                    AiSectionCard(title = "RECOMMENDED STRATEGY", icon = Icons.Filled.Lightbulb, color = PurpleAccent) {
                                        AnalystClaimContent(analysis.recommendedStrategy)
                                    }
                                }

                                item {
                                    AiSectionCard(title = "DUE DILIGENCE QUESTIONS", icon = Icons.Filled.FactCheck, color = AmberAccent) {
                                        analysis.dueDiligenceQuestions.forEach { question -> AnalystQuestionContent(question) }
                                    }
                                }

                                item {
                                    Text(
                                        "Qualitative AI analysis only. Financial calculations remain with the deterministic local engine.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Slate400
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    state.analysisError ?: "No validated analysis is available.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Slate200
                                )
                                if (state.property != null) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(onClick = { viewModel.runStructuredAnalysis() }) {
                                        Text("Retry analysis")
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // Deal Context Tab
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        state.property?.let { p ->
                            item {
                                AiSectionCard(title = "FULL DEAL CONTEXT", icon = Icons.Filled.HomeWork, color = CyanPrimary) {
                                    Text("Address: ${p.address}, ${p.city}, ${p.state} ${p.zipCode}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("List Price: $${String.format("%,.0f", p.price)} • Type: ${p.propertyType}", fontSize = 12.sp, color = Slate300)
                                    Text("Specifications: ${p.bedrooms} Beds, ${p.bathrooms} Baths, ${p.squareFeet} SqFt (Year Built: ${p.yearBuilt})", fontSize = 12.sp, color = Slate400)
                                }
                            }
                        }

                        item {
                            AiSectionCard(title = "MARKET CONTEXT", icon = Icons.Filled.Public, color = AmberAccent) {
                                Text("Market claims are shown only when supported by the selected property's supplied evidence. Missing market data is treated as unknown.", fontSize = 12.sp, color = Slate200)
                            }
                        }

                        item {
                            AiSectionCard(title = "FINANCIAL ENGINE CONTEXT", icon = Icons.Filled.AccountBalance, color = EmeraldGain) {
                                Text("Rule Engine: Pure deterministic financial logic. AI is applied strictly for qualitative reasoning, risk synthesis, and contract wording generation.", fontSize = 12.sp, color = Slate200)
                            }
                        }
                    }
                }

                2 -> {
                    // AI Chat Tab
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Quick prompt suggestions
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Slate900)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.quickPrompts) { prompt ->
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Slate800,
                                    modifier = Modifier.clickable {
                                        viewModel.sendMessage(prompt)
                                    }
                                ) {
                                    Text(
                                        text = prompt,
                                        fontSize = 11.sp,
                                        color = CyanPrimary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Messages
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (state.messages.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(36.dp))
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text("Ask anything about this property or deal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text("Tap a quick prompt above or type your question below.", fontSize = 11.sp, color = Slate400)
                                        }
                                    }
                                }
                            } else {
                                items(state.messages) { msg ->
                                    ChatBubble(message = msg)
                                }
                            }

                            if (state.isSendingMessage) {
                                item {
                                    Row(
                                        modifier = Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CyanPrimary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("AI is reasoning...", fontSize = 11.sp, color = Slate400)
                                    }
                                }
                            }
                        }

                        // Input Bar
                        Surface(
                            color = Slate900,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = inputMessage,
                                    onValueChange = { inputMessage = it },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("ai_chat_input"),
                                    placeholder = { Text("Ask underwriting question...", fontSize = 13.sp) },
                                    maxLines = 3,
                                    shape = RoundedCornerShape(20.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = Slate800,
                                        unfocusedContainerColor = Slate800
                                    )
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = {
                                        val text = inputMessage.trim()
                                        if (text.isNotBlank()) {
                                            viewModel.sendMessage(text)
                                            inputMessage = ""
                                        }
                                    },
                                    enabled = inputMessage.isNotBlank() && !state.isSendingMessage,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(if (inputMessage.isNotBlank()) CyanPrimary else Slate800)
                                        .testTag("send_chat_button")
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "Send",
                                        tint = if (inputMessage.isNotBlank()) Slate950 else Slate600
                                    )
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
private fun AnalystClaimContent(claim: AnalystClaim) {
    val classificationColor = when (claim.classification) {
        AnalystClaimType.FACT -> EmeraldGain
        AnalystClaimType.ESTIMATE -> AmberAccent
        AnalystClaimType.INFERENCE -> CyanPrimary
        AnalystClaimType.UNKNOWN -> Slate400
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(color = classificationColor.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp)) {
                Text(
                    claim.classification.name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = classificationColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                String.format(java.util.Locale.US, "%.0f%% confidence", claim.confidence * 100.0),
                style = MaterialTheme.typography.labelSmall,
                color = Slate400
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(claim.statement, style = MaterialTheme.typography.bodySmall, color = Slate200)
        if (claim.evidenceRefs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                "Evidence: ${claim.evidenceRefs.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
                color = Slate400
            )
        }
    }
}

@Composable
private fun AnalystQuestionContent(question: AnalystDueDiligenceQuestion) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (question.priority.name == "HIGH") CrimsonAlert.copy(alpha = 0.16f) else AmberAccent.copy(alpha = 0.16f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    question.priority.name,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    color = if (question.priority.name == "HIGH") CrimsonAlert else AmberAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(question.question, style = MaterialTheme.typography.bodySmall, color = Slate200)
        AnalystClaimContent(question.basis)
    }
}

@Composable
private fun ChatBubble(message: AIMessageEntity) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(if (isUser) CyanDark else Slate800)
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun AiSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
