package com.example.domain.crm

/**
 * A free-form note attached to a lead.
 *
 * Notes are the only place in the model for prose about a deal ("seller wants to keep the fridge,
 * closing must be after the 15th"), and they are immutable and time-stamped: an edited note would
 * destroy the record of what was known when an offer was made. Corrections are new notes that
 * [supersedesId] the old one.
 */
data class LeadNote(
    val id: String,
    val leadId: String,
    val body: String,
    val author: String,
    val createdAtEpochMillis: Long,
    val pinned: Boolean = false,
    /** Tags on the note itself; normalized with the same rules as lead tags. */
    val tags: Set<String> = emptySet(),
    /** Note this one corrects/replaces. */
    val supersedesId: String? = null,
    /** Set when the note was produced by an automated process rather than a person. */
    val isSystemGenerated: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "Note id must not be blank" }
        require(leadId.isNotBlank()) { "Note leadId must not be blank" }
        require(body.isNotBlank()) { "Note body must not be blank" }
        require(body.length <= MAX_BODY_LENGTH) { "Note body must be at most $MAX_BODY_LENGTH characters" }
        require(author.isNotBlank()) { "Note must record an author" }
        require(createdAtEpochMillis > 0L) { "Note createdAtEpochMillis must be positive" }
        require(supersedesId == null || supersedesId != id) { "A note cannot supersede itself" }
        require(tags.all { LeadTags.isCanonical(it) }) { "Note tags must be normalized with LeadTags.normalizeAll" }
    }

    fun describe(): String = buildString {
        append(if (pinned) "[pinned] " else "")
        append(author).append(": ")
        append(if (body.length <= PREVIEW_LENGTH) body else body.take(PREVIEW_LENGTH) + "…")
    }

    companion object {
        const val MAX_BODY_LENGTH = 4_000
        const val PREVIEW_LENGTH = 120
    }
}
