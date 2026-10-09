package com.example.domain.intelligence.scrape

/** Which wall answered instead of the portal. Named after the vendor, so the log is actionable. */
data class BotWall(val vendor: String, val marker: String)

/**
 * Detects anti-bot interstitials.
 *
 * This is not a defensive nicety — it is the difference between "0 homes for sale in Austin" (which
 * the user would believe) and "the portal served us a captcha" (which they can act on). Every
 * detection here is a marker that only appears on an interstitial page, never on a real results page.
 */
object PortalAntiBotDetector {

    /** Vendor markers, ordered roughly by how common they are in front of real-estate portals. */
    private val MARKERS = listOf(
        "px-captcha" to "PerimeterX/HUMAN",
        "window._pxAppId" to "PerimeterX/HUMAN",
        "access to this page has been denied" to "PerimeterX/HUMAN",
        "captcha-delivery.com" to "DataDome",
        "geo.captcha-delivery.com" to "DataDome",
        "cf-chl-" to "Cloudflare",
        "cf-browser-verification" to "Cloudflare",
        "attention required! | cloudflare" to "Cloudflare",
        "just a moment..." to "Cloudflare",
        "_incapsula_resource" to "Imperva/Incapsula",
        "request unsuccessful. incapsula" to "Imperva/Incapsula",
        "g-recaptcha" to "reCAPTCHA",
        "recaptcha/api.js" to "reCAPTCHA",
        "h-captcha" to "hCaptcha",
        "are you a robot" to "generic-bot-check",
        "verify you are a human" to "generic-bot-check",
        "unusual traffic" to "generic-bot-check",
        "please enable js and disable any ad blockers" to "generic-bot-check"
    )

    /** @return the wall that answered, or null when the body looks like a real page. */
    fun detect(fetch: ListingPageFetch): BotWall? {
        val body = fetch.body?.take(SCAN_LIMIT)?.lowercase()
        if (body == null) return if (fetch.status in INTERSTITIAL_STATUSES) BotWall("http-status", "HTTP ${fetch.status}") else null
        for ((marker, vendor) in MARKERS) {
            if (marker in body) return BotWall(vendor, marker)
        }
        // A 403 with no recognisable payload is still a wall: portals answer 403 to blocked clients
        // with a near-empty body, and reporting it as a normal HTTP error hides the real cause.
        return if (fetch.status in INTERSTITIAL_STATUSES && body.length < INTERSTITIAL_MAX_BODY) {
            BotWall("http-status", "HTTP ${fetch.status} with ${body.length}-byte body")
        } else {
            null
        }
    }

    private const val SCAN_LIMIT = 200_000
    private const val INTERSTITIAL_MAX_BODY = 20_000
    private val INTERSTITIAL_STATUSES = setOf(401, 403, 406, 503)
}
