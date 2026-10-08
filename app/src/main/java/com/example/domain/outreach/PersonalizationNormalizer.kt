package com.example.domain.outreach

/**
 * Turns caller personalization into the only values templates and AI prompts may see.
 *
 * Drops credentials, links, prompt injection, and financial material. Property facts that already
 * contain motivation language or unsupported claims are dropped so they cannot be restated as
 * record facts. A seller note is kept only when it is attributed to the seller, owner, or listing
 * agent and does not itself contain secrets or financial terms.
 */
object PersonalizationNormalizer {
    data class Result(
        val fields: PersonalizationFields,
        val droppedFields: List<String>
    )

    private val statePattern = Regex("[A-Za-z]{2}")
    private val postalPattern = Regex("\\d{5}(?:-\\d{4})?")
    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val allowedNoteSources = mapOf(
        "seller" to "seller",
        "owner" to "owner",
        "listing agent" to "listing agent",
        "listing_agent" to "listing agent"
    )

    fun normalize(raw: PersonalizationFields): Result {
        val dropped = mutableListOf<String>()
        val seller = line(raw.sellerDisplayName, OutreachLimits.NAME, "sellerDisplayName", dropped)
        val sender = line(raw.senderName, OutreachLimits.NAME, "senderName", dropped).orEmpty()
        val company = line(raw.senderCompany, OutreachLimits.COMPANY, "senderCompany", dropped)
        val address = line(raw.propertyAddress, OutreachLimits.ADDRESS, "propertyAddress", dropped)
        val city = line(raw.propertyCity, OutreachLimits.CITY, "propertyCity", dropped)
        val state = normalizeState(raw.propertyState, dropped)
        val postal = normalizePostal(raw.propertyPostalCode, dropped)
        val email = normalizeEmail(raw.replyEmail, dropped)
        val phone = normalizePhone(raw.callbackPhone, dropped)
        val facts = normalizeFacts(raw.verifiedFacts, dropped)
        val note = normalizeNote(raw.sellerStatedNote, raw.sellerStatedNoteSource, dropped)
        return Result(
            fields = PersonalizationFields(
                sellerDisplayName = seller,
                senderName = sender,
                senderCompany = company,
                propertyAddress = address,
                propertyCity = city,
                propertyState = state,
                propertyPostalCode = postal,
                replyEmail = email,
                callbackPhone = phone,
                verifiedFacts = facts,
                sellerStatedNote = note.text,
                sellerStatedNoteSource = note.source
            ),
            droppedFields = dropped
        )
    }

    fun normalizeRecipient(raw: OutreachRecipient): OutreachRecipient {
        val dropped = mutableListOf<String>()
        return OutreachRecipient(
            displayName = line(raw.displayName, OutreachLimits.NAME, "recipientName", dropped),
            email = normalizeEmail(raw.email, dropped),
            phone = normalizePhone(raw.phone, dropped),
            smsConsentRecorded = raw.smsConsentRecorded,
            emailConsentRecorded = raw.emailConsentRecorded,
            callConsentRecorded = raw.callConsentRecorded
        )
    }

    fun opaqueReference(raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.length > 80) return null
        return cleaned.takeIf { Regex("[A-Za-z0-9_.:\\-]+").matches(it) }
    }

    private data class Note(val text: String?, val source: String?)

    private fun normalizeNote(text: String?, source: String?, dropped: MutableList<String>): Note {
        val cleaned = line(text, OutreachLimits.NOTE, "sellerStatedNote", dropped, allowMotivation = true)
        if (cleaned == null) return Note(null, null)
        val sourceKey = source?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")
        val canonical = allowedNoteSources[sourceKey]
        if (canonical == null) {
            dropped += "sellerStatedNote"
            dropped += "sellerStatedNoteSource"
            return Note(null, null)
        }
        return Note(cleaned, canonical)
    }

    private fun normalizeFacts(
        facts: List<VerifiedPropertyFact>,
        dropped: MutableList<String>
    ): List<VerifiedPropertyFact> {
        val kept = mutableListOf<VerifiedPropertyFact>()
        facts.forEachIndexed { index, fact ->
            if (kept.size >= OutreachLimits.MAX_FACTS) {
                dropped += "verifiedFacts[$index]"
                return@forEachIndexed
            }
            val value = line(fact.value, OutreachLimits.FACT_VALUE, "verifiedFacts[$index]", dropped)
            val source = line(fact.sourceLabel, OutreachLimits.SOURCE_LABEL, "verifiedFacts[$index].source", dropped)
            if (value == null || source == null) return@forEachIndexed
            if (OutreachSafetyRules.containsMotivationPhrase(value) ||
                OutreachSafetyRules.containsUnsupportedClaim(value)
            ) {
                dropped += "verifiedFacts[$index]"
                return@forEachIndexed
            }
            kept += VerifiedPropertyFact(fact.key, value, source)
        }
        return kept
    }

    private fun line(
        raw: String?,
        maxLength: Int,
        field: String,
        dropped: MutableList<String>,
        allowMotivation: Boolean = true
    ): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = OutreachTextSanitizer.clean(raw, maxLength, multiline = false)
        if (cleaned.rejected || cleaned.value == null || OutreachSafetyRules.containsFinancialMaterial(cleaned.value)) {
            dropped += field
            return null
        }
        // Names and addresses may legally contain words like "motivated". Facts are filtered separately.
        if (!allowMotivation && OutreachSafetyRules.containsMotivationPhrase(cleaned.value)) {
            dropped += field
            return null
        }
        val withoutQuotes = cleaned.value.replace("\"", "'").trim()
        return withoutQuotes.takeIf { it.isNotEmpty() } ?: run {
            dropped += field
            null
        }
    }

    private fun normalizeState(raw: String?, dropped: MutableList<String>): String? {
        val cleaned = line(raw, 8, "propertyState", dropped) ?: return null
        val match = statePattern.matchEntire(cleaned.uppercase())
        if (match == null) {
            dropped += "propertyState"
            return null
        }
        return match.value
    }

    private fun normalizePostal(raw: String?, dropped: MutableList<String>): String? {
        val cleaned = line(raw, 12, "propertyPostalCode", dropped) ?: return null
        if (!postalPattern.matches(cleaned)) {
            dropped += "propertyPostalCode"
            return null
        }
        return cleaned
    }

    private fun normalizeEmail(raw: String?, dropped: MutableList<String>): String? {
        val cleaned = line(raw, 120, "email", dropped) ?: return null
        if (!emailPattern.matches(cleaned)) {
            dropped += "email"
            return null
        }
        return cleaned
    }

    private fun normalizePhone(raw: String?, dropped: MutableList<String>): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = OutreachTextSanitizer.clean(raw, 32, multiline = false)
        if (cleaned.rejected || cleaned.value == null) {
            dropped += "phone"
            return null
        }
        val digits = cleaned.value.filter { it.isDigit() }
        val plus = cleaned.value.trim().startsWith("+")
        if (digits.length !in 10..15) {
            dropped += "phone"
            return null
        }
        return (if (plus) "+" else "") + digits
    }
}
