package com.example.domain.crm

import java.util.Locale
import java.util.UUID

/**
 * Identifier factory for CRM records.
 *
 * Ids are prefixed by type (`LEAD-`, `SELLER-`, `COMM-`, …) so a raw id in a log, a bug report or a
 * database dump explains what it points at without a join. The random source is injectable, which is
 * what lets tests assert exact ids instead of pattern-matching them.
 */
class LeadIdFactory(private val uuidSupplier: () -> UUID = { UUID.randomUUID() }) {

    fun newLeadId(): String = "LEAD-" + token()

    fun newSellerId(): String = "SELLER-" + token()

    fun newCommunicationId(): String = "COMM-" + token()

    fun newNoteId(): String = "NOTE-" + token()

    fun newMotivationSignalId(): String = "SIG-" + token()

    fun newContractId(): String = "CTR-" + token()

    fun newFollowUpId(): String = "FU-" + token()

    private fun token(): String = uuidSupplier().toString()
        .replace("-", "")
        .take(TOKEN_LENGTH)
        .uppercase(Locale.US)

    companion object {
        const val TOKEN_LENGTH = 12
        val DEFAULT = LeadIdFactory()

        /** True when the id was produced by this factory (used by tests and import validation). */
        fun isLeadId(value: String): Boolean = value.startsWith("LEAD-") && value.length > "LEAD-".length
    }
}
