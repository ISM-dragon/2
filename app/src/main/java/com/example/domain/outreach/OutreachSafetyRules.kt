package com.example.domain.outreach

/**
 * Stable denylists for seller-facing outreach text.
 *
 * Phrases are matched case-insensitively. Templates must not contain them except where a supplied
 * seller note is quoted verbatim. Identifiers such as a legal name are removed before the scan so a
 * company named "Guaranteed Rate" does not block a greeting.
 */
object OutreachSafetyRules {
    val motivationPhrases: List<String> = listOf(
        "motivated seller",
        "motivated to sell",
        "highly motivated",
        "you are motivated",
        "you seem motivated",
        "seem motivated",
        "seller is motivated",
        "owner is motivated",
        "your motivation",
        "seller motivation",
        "why you are selling",
        "why you're selling",
        "reason for selling",
        "reason you are selling",
        "need to sell",
        "needs to sell",
        "must sell",
        "have to sell",
        "eager to sell",
        "anxious to sell",
        "ready to sell",
        "quick sale",
        "sell quickly",
        "sell fast",
        "distressed seller",
        "distressed owner",
        "you are distressed",
        "foreclosure",
        "pre-foreclosure",
        "preforeclosure",
        "divorce",
        "bankruptcy",
        "behind on payments",
        "financial hardship",
        "tired landlord",
        "tired of being a landlord",
        "job loss",
        "desperate",
        "underwater",
        "tax lien",
        "relocation"
    )

    val unsupportedClaimPhrases: List<String> = listOf(
        "guarantee",
        "risk-free",
        "risk free",
        "no risk",
        "will appreciate",
        "certain to appreciate",
        "sure to appreciate",
        "approved financing",
        "financing is approved",
        "we will close",
        "close for sure",
        "inspection waived",
        "waive the inspection",
        "waive inspection",
        "highest and best",
        "above market",
        "below market",
        "can't miss",
        "cant miss",
        "act now",
        "limited time",
        "once in a lifetime",
        "no contingencies",
        "sure thing",
        "promise you",
        "i promise",
        "we promise"
    )

    val conditionPhrases: List<String> = listOf(
        "recently renovated",
        "newly renovated",
        "move-in ready",
        "move in ready",
        "new roof",
        "new hvac",
        "updated kitchen",
        "hardwood floors",
        "swimming pool",
        "in-ground pool",
        "water damage",
        "foundation issues",
        "tear down",
        "teardown",
        "fixer upper",
        "fixer-upper",
        "needs work",
        "fully remodeled",
        "granite counters"
    )

    val financialKeywords: List<Regex> = listOf(
        Regex("(?i)\\b(?:cap rate|cash flow|cash-on-cash|net operating income|earnest money|interest rate|offer price|purchase price|cash offer|wholesale fee)\\b"),
        Regex("(?i)\\b(?:noi|dscr|arv)\\b"),
        Regex("(?i)\\b(?:offer|pay|paying|priced at|worth)\\s+\\$?\\d"),
        Regex("(?i)\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:dollars|usd)\\b"),
        Regex("(?i)\\b(?:dollars|usd)\\s*\\d")
    )

    val moneyAmount: Regex = Regex("[\\$€£]\\s?\\d")
    val percentAmount: Regex = Regex("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:%|percent)\\b")

    val bedroomClaim: Regex = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:bed|beds|bedroom|bedrooms|br)\\b")
    val bathroomClaim: Regex = Regex("(?i)\\b(\\d+(?:\\.\\d+)?)\\s*(?:bath|baths|bathroom|bathrooms|ba)\\b")
    val livingAreaClaim: Regex = Regex(
        "(?i)\\b(\\d{1,3}(?:,\\d{3})+|\\d{3,7})\\s*(?:sq\\.?\\s*ft|square feet|sqft)\\b"
    )
    val yearBuiltClaim: Regex = Regex(
        "(?i)\\b(?:built|build|constructed|construction)\\s*(?:in\\s*)?((?:18|19|20)\\d{2})\\b|" +
            "\\b((?:18|19|20)\\d{2})\\s*(?:build|built|construction)\\b"
    )
    val streetClaim: Regex = Regex(
        "(?i)\\b\\d{1,6}\\s+(?:[A-Za-z.'-]+|\\d{1,3}(?:st|nd|rd|th))(?:\\s+(?:[A-Za-z.'-]+|\\d{1,3}(?:st|nd|rd|th))){0,3}\\s+" +
            "(?:street|st|avenue|ave|road|rd|drive|dr|lane|ln|boulevard|blvd|way|court|ct|place|pl)\\b"
    )

    val priceClaim: Regex = Regex(
        "(?i)\\b(?:listing\\s+price|list\\s+price|asking\\s+price|sale\\s+price|purchase\\s+price|priced\\s+at)\\s*(?:is|was|of|at)?\\s*[$]?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\s*(?:dollars|usd))?\\b"
    )
    val occupancyClaim: Regex = Regex(
        "(?i)\\b(?:vacant|occupied|tenant[ -]occupied|owner[ -]occupied|squatter[s]?)\\b"
    )
    val ownershipClaim: Regex = Regex(
        "(?i)\\b(?:absentee\\s+owner|out[ -]of[ -]state\s+owner|owned\\s+for\\s+\\d+|owned\\s+since\\s+\\d{4}|free\\s+and\\s+clear|no\\s+mortgage)\\b"
    )
    val taxClaim: Regex = Regex(
        "(?i)\\b(?:property\\s+taxes?|annual\\s+taxes?|tax\\s+assessment|delinquent\\s+taxes?|tax\\s+delinquent|tax\\s+lien)\\b"
    )
    val repairClaim: Regex = Regex(
        "(?i)\\b(?:needs\\s+repairs?|repair\\s+needed|repairs?\\s+needed|foundation\\s+repair|roof\\s+repair|plumbing\\s+repair|electrical\\s+repair|major\\s+repairs?)\\b"
    )
    val historyClaim: Regex = Regex(
        "(?i)\\b(?:previously\\s+sold|foreclosure\\s+history|listed\\s+in\\s+\\d{4}|sold\\s+in\\s+\\d{4}|on\\s+the\\s+market\\s+for\\s+\\d+)\\b"
    )

    private val untrustedSourceKeywords: List<String> = listOf(
        "model", "ai", "generated", "inferred", "prediction", "guess", "untrusted", "llm", "unverified", "synthesized"
    )

    fun isTrustedSource(sourceLabel: String): Boolean {
        val s = sourceLabel.trim().lowercase()
        if (s.isBlank()) return false
        return untrustedSourceKeywords.none { s.contains(it) }
    }

    val numberToken: Regex = Regex("\\d+(?:\\.\\d+)?")

    fun containsFinancialMaterial(text: String): Boolean {
        if (moneyAmount.containsMatchIn(text) || percentAmount.containsMatchIn(text)) return true
        return financialKeywords.any { it.containsMatchIn(text) }
    }

    fun containsUnsupportedClaim(text: String): Boolean =
        unsupportedClaimPhrases.any { text.contains(it, ignoreCase = true) }

    fun containsMotivationPhrase(text: String): Boolean =
        motivationPhrases.any { text.contains(it, ignoreCase = true) }

    fun normalizeNumber(raw: String): String? {
        val n = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (!n.isFinite()) return null
        return if (n % 1.0 == 0.0) n.toLong().toString() else n.toString().trimEnd('0').trimEnd('.')
    }

    fun numbersIn(value: String): Set<String> =
        numberToken.findAll(value).mapNotNull { normalizeNumber(it.value) }.toSet()

    fun normalizeLoose(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
}
