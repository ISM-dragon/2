package com.example.data.scrape

import android.content.Context
import com.example.domain.intelligence.scrape.ListingSearchQuery
import com.example.domain.intelligence.scrape.PortalScrapeSettings
import com.example.domain.intelligence.scrape.RobotsCompliance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-installation settings for live portal search.
 *
 * Kept in [android.content.SharedPreferences] rather than Room on purpose: these are device policy
 * switches, not user data, and adding a table would mean a schema migration for two booleans. Every
 * default here is the restrictive one — a fresh install has portal retrieval switched off.
 */
class PortalScrapeSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<PortalScrapeSettings> = _settings.asStateFlow()

    fun current(): PortalScrapeSettings = _settings.value

    /** The user's explicit go-ahead for direct portal retrieval. Off by default. */
    fun setConsent(consented: Boolean) = write { copy(userConsented = consented) }

    /**
     * `true` = read robots.txt, warn, proceed anyway. This is a legal decision, so the UI states what
     * it means before letting anyone flip it.
     */
    fun setRobotsAdvisory(advisory: Boolean) = write {
        copy(robotsCompliance = if (advisory) RobotsCompliance.ADVISORY else RobotsCompliance.ENFORCE)
    }

    fun setMaxPages(pages: Int) = write {
        copy(maxPagesPerSearch = pages.coerceIn(1, ListingSearchQuery.MAX_PAGES_LIMIT))
    }

    fun setRequestInterval(millis: Long) = write { copy(minRequestIntervalMillis = millis.coerceAtLeast(0)) }

    private fun write(transform: PortalScrapeSettings.() -> PortalScrapeSettings) {
        val updated = _settings.value.transform()
        prefs.edit()
            .putBoolean(KEY_CONSENT, updated.userConsented)
            .putBoolean(KEY_ROBOTS_ADVISORY, updated.robotsCompliance == RobotsCompliance.ADVISORY)
            .putInt(KEY_MAX_PAGES, updated.maxPagesPerSearch)
            .putLong(KEY_INTERVAL, updated.minRequestIntervalMillis)
            .apply()
        _settings.value = updated
    }

    private fun read(): PortalScrapeSettings {
        val defaults = PortalScrapeSettings()
        return PortalScrapeSettings(
            userConsented = prefs.getBoolean(KEY_CONSENT, defaults.userConsented),
            robotsCompliance = if (prefs.getBoolean(KEY_ROBOTS_ADVISORY, false)) {
                RobotsCompliance.ADVISORY
            } else {
                RobotsCompliance.ENFORCE
            },
            allowedHosts = defaults.allowedHosts,
            maxPagesPerSearch = prefs.getInt(KEY_MAX_PAGES, defaults.maxPagesPerSearch)
                .coerceIn(1, ListingSearchQuery.MAX_PAGES_LIMIT),
            minRequestIntervalMillis = prefs.getLong(KEY_INTERVAL, defaults.minRequestIntervalMillis)
                .coerceAtLeast(0),
            userAgent = defaults.userAgent,
            maxResponseBytes = defaults.maxResponseBytes
        )
    }

    private companion object {
        const val PREFS_NAME = "portal_scrape_settings"
        const val KEY_CONSENT = "user_consented"
        const val KEY_ROBOTS_ADVISORY = "robots_advisory"
        const val KEY_MAX_PAGES = "max_pages"
        const val KEY_INTERVAL = "min_request_interval_millis"
    }
}
