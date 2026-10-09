package com.example.domain.gmail

import java.util.Locale

/**
 * Recipient rules for outbound offer email.
 *
 * [PLACEHOLDER_EMAIL] is the recipient that offer generation assigns when no real recipient is known.
 * It is a hard-coded address the user did not choose. An offer carries the purchase price, earnest
 * money and terms, so it must never be transmitted to that address, whether the send is manual or
 * performed unattended by automation.
 */
object OfferRecipientPolicy {

    const val PLACEHOLDER_EMAIL: String = "agent@realestateteam.com"

    fun isPlaceholder(email: String?): Boolean =
        email?.trim()?.lowercase(Locale.US) == PLACEHOLDER_EMAIL
}
