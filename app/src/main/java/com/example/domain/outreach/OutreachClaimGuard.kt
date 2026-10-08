package com.example.domain.outreach

/**
 * Semantic guard for seller-facing outreach prose.
 *
 * Prevents credentials, unsupported claims, fabricated seller motivation, and invented property
 * facts. Price and other financial terms are rejected even when a model phrases them as text.
 * Identifier strings (names, company, address, city) and a verbatim seller note are removed before
 * motivation, hype, and financial-keyword scans so a legal name is not mistaken for a claim.
 */
class OutreachClaimGuard(
    private val sanitizer: OutreachTextSanitizer = OutreachTextSanitizer
) {
    fun inspect(text: String, context: GuardContext): List<GuardFinding> {
        if (text.isBlank()) return emptyList()
        val findings = mutableListOf<GuardFinding>()
        val invisibleStripped = sanitizer.stripInvisible(text)
        if (sanitizer.containsCredential(invisibleStripped)) {
            findings += GuardFinding(GuardCode.CREDENTIAL, "Credential-like material is not allowed.")
        }
        if (sanitizer.containsInjection(invisibleStripped)) {
            findings += GuardFinding(GuardCode.PROMPT_INJECTION, "Instruction-like text is not allowed.")
        }
        if (sanitizer.containsLink(invisibleStripped)) {
            findings += GuardFinding(GuardCode.UNSUPPORTED_CLAIM, "Links are not allowed in outreach text.")
        }
        if (OutreachSafetyRules.moneyAmount.containsMatchIn(text) ||
            OutreachSafetyRules.percentAmount.containsMatchIn(text) ||
            OutreachSafetyRules.financialKeywords.any { it.containsMatchIn(stripIdentifiers(text, context)) }
        ) {
            findings += GuardFinding(
                GuardCode.FINANCIAL_TERMS,
                "Price or financial terms are not allowed in outreach text."
            )
        }
        if (OutreachSafetyRules.containsUnsupportedClaim(stripIdentifiers(text, context))) {
            findings += GuardFinding(GuardCode.UNSUPPORTED_CLAIM, "Unsupported claim language is not allowed.")
        }
        if (OutreachSafetyRules.containsMotivationPhrase(stripIdentifiers(text, context))) {
            findings += GuardFinding(
                GuardCode.FABRICATED_SELLER_MOTIVATION,
                "Seller motivation was not supplied and must not be invented."
            )
        }
        findings += ungroundedPropertyFacts(text, context)
        return findings
    }

    fun primaryReason(findings: List<GuardFinding>): AiFallbackReason? {
        val codes = findings.map { it.code }.toSet()
        return when {
            GuardCode.CREDENTIAL in codes -> AiFallbackReason.CREDENTIALS_DETECTED
            GuardCode.PROMPT_INJECTION in codes -> AiFallbackReason.PROMPT_INJECTION
            GuardCode.FINANCIAL_TERMS in codes -> AiFallbackReason.FINANCIAL_TERMS_DETECTED
            GuardCode.FABRICATED_SELLER_MOTIVATION in codes -> AiFallbackReason.FABRICATED_SELLER_MOTIVATION
            GuardCode.INVENTED_PROPERTY_FACT in codes -> AiFallbackReason.INVENTED_PROPERTY_FACT
            GuardCode.UNSUPPORTED_CLAIM in codes -> AiFallbackReason.UNSUPPORTED_CLAIM
            findings.isNotEmpty() -> AiFallbackReason.UNSAFE_CONTENT
            else -> null
        }
    }

    private fun ungroundedPropertyFacts(text: String, context: GuardContext): List<GuardFinding> {
        val findings = mutableListOf<GuardFinding>()
        fun checkNumbers(regex: Regex, key: PropertyFactKey) {
            val allowed = context.allowedFacts
                .filter { it.key == key }
                .flatMap { OutreachSafetyRules.numbersIn(it.value) }
                .toSet()
            regex.findAll(text).forEach { match ->
                val raw = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
                val number = OutreachSafetyRules.normalizeNumber(raw) ?: return@forEach
                if (number !in allowed) {
                    findings += GuardFinding(
                        GuardCode.INVENTED_PROPERTY_FACT,
                        "A ${key.name.lowercase()} claim is not in the supplied record."
                    )
                }
            }
        }
        checkNumbers(OutreachSafetyRules.bedroomClaim, PropertyFactKey.BEDROOMS)
        checkNumbers(OutreachSafetyRules.bathroomClaim, PropertyFactKey.BATHROOMS)
        checkNumbers(OutreachSafetyRules.livingAreaClaim, PropertyFactKey.LIVING_AREA_SQFT)
        checkNumbers(OutreachSafetyRules.yearBuiltClaim, PropertyFactKey.YEAR_BUILT)

        val allowedAddress = context.allowedAddress?.let(OutreachSafetyRules::normalizeLoose).orEmpty()
        OutreachSafetyRules.streetClaim.findAll(text).forEach { match ->
            val street = OutreachSafetyRules.normalizeLoose(match.value)
            if (street.isBlank() || allowedAddress.isBlank() || street !in allowedAddress) {
                findings += GuardFinding(
                    GuardCode.INVENTED_PROPERTY_FACT,
                    "A street address is not in the supplied record."
                )
            }
        }

        val groundedBlob = buildString {
            context.allowedFacts.forEach { append(' ').append(it.value) }
            context.sellerStatedNote?.let { append(' ').append(it) }
        }.lowercase()
        OutreachSafetyRules.conditionPhrases.forEach { phrase ->
            if (text.contains(phrase, ignoreCase = true) && !groundedBlob.contains(phrase.lowercase())) {
                findings += GuardFinding(
                    GuardCode.INVENTED_PROPERTY_FACT,
                    "A property condition claim is not in the supplied record."
                )
            }
        }
        return findings
    }

    /**
     * Removes the verbatim seller note and long identifiers before phrase scans. Two-letter state
     * codes are not removed, so "IN" cannot eat the word "writing".
     */
    fun stripIdentifiers(text: String, context: GuardContext): String {
        var result = text
        val note = context.sellerStatedNote?.trim().orEmpty()
        if (note.length >= 2) {
            result = result.replace(note, " ", ignoreCase = true)
        }
        context.identifiers
            .map { it.trim() }
            .filter { it.length >= 4 }
            .sortedByDescending { it.length }
            .forEach { identifier ->
                result = Regex(Regex.escape(identifier), RegexOption.IGNORE_CASE).replace(result, " ")
            }
        return result
    }
}

enum class GuardCode {
    CREDENTIAL,
    UNSUPPORTED_CLAIM,
    FABRICATED_SELLER_MOTIVATION,
    INVENTED_PROPERTY_FACT,
    FINANCIAL_TERMS,
    PROMPT_INJECTION
}

data class GuardFinding(
    val code: GuardCode,
    val detail: String
)

data class GuardContext(
    val allowedFacts: List<VerifiedPropertyFact>,
    val sellerStatedNote: String?,
    val allowedAddress: String?,
    val identifiers: List<String>
) {
    companion object {
        fun from(personalization: PersonalizationFields): GuardContext = GuardContext(
            allowedFacts = personalization.verifiedFacts,
            sellerStatedNote = personalization.sellerStatedNote,
            allowedAddress = personalization.propertyAddress,
            identifiers = listOfNotNull(
                personalization.sellerDisplayName,
                personalization.senderName,
                personalization.senderCompany,
                personalization.propertyAddress,
                personalization.propertyCity
            )
        )
    }
}
