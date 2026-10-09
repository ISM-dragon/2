package com.example.domain.intelligence.scrape

/**
 * Turns a scraped listing into the routes a user can actually use to reach the seller's side.
 *
 * The uncomfortable truth this class encodes: portals deliberately do **not** publish direct numbers
 * on search cards. Agent phones appear only inside the listing payload's attribution block, and only
 * when the brokerage chose to expose them. So the honest product behaviour is:
 *
 *  * publish what the page gave us (agent name, brokerage, phone when present),
 *  * always offer the portal's own contact route plus the listing page — those exist for every
 *    listing and are the sanctioned way to start a conversation,
 *  * state plainly what is missing instead of showing a dead button.
 *
 * Nothing here sends anything. Actions are URIs; the UI hands them to an `Intent` and the user
 * confirms the call/text/email in their own app.
 */
class ListingContactExtractor {

    /** Builds the contact card for every listing. [pageHtml] is used only for unambiguous fallbacks. */
    fun contactsFor(listings: List<ScrapedListing>, pageHtml: String? = null): List<ListingContact> {
        // A single `tel:` link on the whole page can be attributed safely; two or more cannot, and
        // guessing which agent owns which number is how a user ends up calling a stranger.
        val pageFallbackPhone = pageHtml?.let { html -> pagePhones(html).distinct().singleOrNull() }

        return listings.map { listing -> contactFor(listing, pageFallbackPhone) }
    }

    fun contactFor(listing: ScrapedListing, fallbackPhone: String? = null): ListingContact {
        val phone = normalizePhone(listing.listingAgentPhone)
            ?: normalizePhone(listing.listingOfficePhone)
            ?: fallbackPhone

        val person = listing.listingAgentName?.takeIf { it.isNotBlank() }
        val brokerage = listing.brokerName?.takeIf { it.isNotBlank() }
            ?: listing.listingOfficeName?.takeIf { it.isNotBlank() }

        val missing = ArrayList<String>()
        if (phone == null) {
            missing += "No direct phone in the listing payload — the portal routes messages through its contact form."
        }
        if (person == null) missing += "No agent name published for this listing."

        val target = listOfNotNull(person, brokerage).filter { it.isNotBlank() }.joinToString(" · ")
            .ifBlank { brokerage ?: person }

        val actions = ArrayList<ContactAction>()
        if (phone != null) {
            actions += ContactAction(ContactChannel.CALL, "tel:$phone", target)
            actions += ContactAction(ContactChannel.SMS, "sms:$phone", target)
            actions += ContactAction(ContactChannel.WHATSAPP, whatsappUri(phone), target)
        } else {
            actions += ContactAction(
                channel = ContactChannel.CALL,
                uri = "",
                target = target,
                unavailableReason = "No number published on the listing card"
            )
        }
        actions += ContactAction(
            channel = ContactChannel.PORTAL_MESSAGE,
            uri = listing.detailUrl,
            target = target,
            unavailableReason = listing.detailUrl.takeIf { it.isBlank() }?.let { "Listing URL missing" }
        )
        actions += ContactAction(
            channel = ContactChannel.LISTING_PAGE,
            uri = listing.detailUrl,
            target = null,
            unavailableReason = listing.detailUrl.takeIf { it.isBlank() }?.let { "Listing URL missing" }
        )

        return ListingContact(
            listingExternalId = listing.externalId,
            listingUrl = listing.detailUrl,
            personName = person,
            role = if (person != null) "Listing agent" else null,
            phone = phone,
            email = null,
            brokerage = brokerage,
            listingOffice = listing.listingOfficeName?.takeIf { it.isNotBlank() },
            actions = actions,
            missing = missing,
            provenance = provenanceFor(listing, fallbackPhone)
        )
    }

    private fun provenanceFor(listing: ScrapedListing, fallbackPhone: String?): String = when {
        listing.listingAgentPhone != null -> "payload:attributionInfo.agentPhoneNumber"
        listing.listingOfficePhone != null -> "payload:attributionInfo.officePhoneNumber"
        fallbackPhone != null -> "html:tel-link (single unambiguous number on page)"
        else -> "payload:none"
    }

    /**
     * Every phone number the page publishes as a `tel:` link or writes out next to a contact label.
     * Ordered by first appearance; callers decide whether the set is unambiguous enough to use.
     */
    fun pagePhones(html: String): List<String> {
        val found = ArrayList<String>()
        for (match in TEL_HREF.findIterated(html, 40)) {
            normalizePhone(match.groupValues[1])?.let { found += it }
        }
        for (match in WRITTEN_PHONE.findIterated(html, 40)) {
            normalizePhone(match.groupValues[0])?.let { found += it }
        }
        return found.distinct()
    }

    /**
     * `https://wa.me/15125550134` — digits only, country code included.
     *
     * The input is normalized first: WhatsApp needs a full international number, and a formatted
     * `(512) 555-0134` would otherwise produce a link that silently fails to resolve a contact.
     */
    fun whatsappUri(raw: String): String {
        val normalized = normalizePhone(raw)?.filter { it.isDigit() } ?: raw.filter { it.isDigit() }
        return "https://wa.me/$normalized"
    }

    private fun normalizePhone(raw: String?): String? =
        raw?.let { ListingPortalSearchParser.normalizePhone(it) }

    private companion object {
        val TEL_HREF = Regex("""href\s*=\s*["']tel:([^"']{7,25})["']""", RegexOption.IGNORE_CASE)
        val WRITTEN_PHONE = Regex("""(?<!\d)(?:\+1[\s.-]?)?\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4}(?!\d)""")
    }
}
