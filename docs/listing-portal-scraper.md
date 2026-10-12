# Live portal search (Zillow scraper)

The Discover screen can search a market on Zillow directly and show what is listed there *right now*,
together with the contact routes each listing publishes. This document is the map of that feature:
what each piece does, what it deliberately refuses to do, and what to do when Zillow changes.

```
DiscoverScreen ──▶ PortalSearchSheet (Compose)
                        │  location, intent, price, beds
                        ▼
                 DiscoverViewModel ──▶ ListingScrapeRepository          (data layer, Android)
                                            │
                        settings ───────────┤  PortalScrapeSettingsStore (SharedPreferences)
                                            ▼
                                     ListingPortalScraper               (domain, pure JVM)
                        ┌───────────────────┼───────────────────────┐
                        ▼                   ▼                       ▼
              ListingScrapePolicy   ZillowSearchUrlBuilder   HttpListingPageFetcher (OkHttp)
              consent · robots ·           │                        │
              host allowlist               ▼                        ▼
                                    ListingPortalSearchParser ◀── HTML page
                                    embedded-state → json-ld → html-cards
                                            │
                                            ▼
                                   ListingContactExtractor ──▶ call / sms / whatsapp / portal / listing
```

## Pieces

| File | Role |
| --- | --- |
| `domain/intelligence/scrape/ListingSearchModels.kt` | Query, listing, contact, result and failure types. Everything nullable stays nullable — nothing is estimated. |
| `domain/intelligence/scrape/ZillowSearchUrlBuilder.kt` | Builds `https://www.zillow.com/homes/for_sale/austin-tx_rb/?searchQueryState=…`. The blob is base64(JSON) percent-encoded; hand-rolled base64 because `java.util.Base64` needs API 26 and this app is `minSdk 24`. |
| `domain/intelligence/scrape/ListingPortalSearchParser.kt` | Three readers, tried in order: `embedded-state` (`searchPageState`), `json-ld` (schema.org `ItemList`), `html-cards` (rendered markup). Reports which one answered. |
| `domain/intelligence/scrape/ListingContactExtractor.kt` | Turns attribution data into `tel:` / `sms:` / `wa.me` / portal-message routes, and states what the page did not publish. |
| `domain/intelligence/scrape/PortalAntiBotDetector.kt` | Recognises PerimeterX/HUMAN, DataDome, Cloudflare, Imperva, reCAPTCHA, hCaptcha interstitials. |
| `domain/intelligence/scrape/ListingScrapePolicy.kt` | Fail-closed gate: consent → host allowlist → live robots.txt. Plus the inter-page rate limiter. |
| `domain/intelligence/scrape/ListingPortalScraper.kt` | Orchestrates one search. `search()` touches the world; `interpretPage()` is pure and is what the tests drive. |
| `data/scrape/HttpListingPageFetcher.kt` | OkHttp transport: HTTPS only, public DNS only, no cookies/proxy/authenticator, capped body size. |
| `data/scrape/PortalScrapeSettingsStore.kt` | Per-device switches (consent, robots mode, page count, pacing). Defaults are the restrictive ones. |
| `data/repository/ListingScrapeRepository.kt` | Screen state + writing a chosen listing into Room through `PropertyRepository`. |
| `ui/screens/discover/PortalSearchSheet.kt` | The sheet: consent notice, filters, results, contact chips, failure card. |

## What the user sees

1. Discover → globe icon → **Live portal search**.
2. The consent card explains that this sends requests straight to Zillow and is restricted by Zillow's
   terms and robots.txt. Nothing is fetched until it is switched on.
3. Type a market (`Austin, TX`, `78704`), pick for-sale/for-rent, optional price and beds, **Search Zillow**.
4. Every card shows price, beds/baths/sqft, address, status, photo, and the contact block:
   agent · brokerage · phone when the portal published one, plus chips for **Call / Text / WhatsApp /
   Portal message / Listing page**. Chips without a destination are disabled with the reason shown,
   not silently hidden.
5. **Save to my deals** writes the listing into Room (`prop-zil-<zpid>`), so it joins deduplication,
   underwriting and offers like any other property. Valuation fields are written as zeros — a portal
   card carries no valuation, and inventing one would leak into the underwriting screen as data.

## Failure model

A portal never "fails" silently. Every outcome is a typed failure with a remediation string that the
UI prints verbatim:

| Kind | Meaning | Remediation shown |
| --- | --- | --- |
| `DENIED_BY_POLICY` | No consent, host not allowed, or robots.txt disallows the path | Where to switch it on / use a licensed feed / advisory mode |
| `BLOCKED_BY_ANTIBOT` | PerimeterX, DataDome, Cloudflare, … served an interstitial | Licensed feed or partner API, or an operator-supplied proxy |
| `RATE_LIMITED` | HTTP 429 | Wait, or lower the page count |
| `HTTP_ERROR` | 404/5xx | 404 → the location token is wrong |
| `NETWORK_ERROR` | DNS/TLS/timeout, no response | Check connectivity; the IP range may be blocked |
| `EMPTY_RESULTS` | Portal answered normally and matched nothing | Widen the filters |
| `PARSE_DRIFT` | Page fetched, no listing records recognised | Zillow changed its payload — parser update needed |

`EMPTY_RESULTS` and `PARSE_DRIFT` are deliberately different. Collapsing them is how a scraper tells a
user "there is nothing for sale in Austin" when in fact it could not read the page.

## Compliance

Automated retrieval from a consumer portal is a contractual decision, not a technical one. This
feature therefore defaults to the restrictive position:

* **Consent off.** A fresh install fetches nothing; the switch carries the notice.
* **robots.txt enforced.** The live file is read for every search. A `Disallow` for the search path
  stops the search *before* the listing request, and an unreadable robots.txt fails closed (RFC 9309).
  Zillow has historically disallowed its search paths, so on a default install the honest outcome is
  a refusal that names the rule — that is the feature working, not a bug.
* **Advisory mode** (read, warn, proceed) exists for operators who have cleared the origin themselves.
  It is opt-in per device and the UI says what it means.
* **Pacing.** Pages inside one search are spaced by `minRequestIntervalMillis` (default 2 s) and the
  page count is capped (default 3, hard cap 10).
* **Transport guard.** HTTPS only, public DNS only (`PublicOnlyDns`), no cookies, no proxy, no
  authenticator, response bodies capped.

The compliant route for production is a licensed feed or the portal's partner API. This scraper is the
right tool for a single user doing their own research on their own device; it is not a data pipeline,
and it should not become one.

## When Zillow changes (drift runbook)

Symptom: `PARSE_DRIFT` with `strategy=none`, or listings appear with missing fields.

1. Open the search URL (the failure card has a button) in a browser and confirm results exist.
2. Save the page as a new fixture under `app/src/test/resources/fixtures/listing-search/`.
3. Look for the state blob: search the HTML for `searchPageState`. If it moved, add the new key to
   `ListingPortalSearchParser.extractEmbeddedStates`. If the listing arrays moved, extend the paths in
   `listingNodes` (`cat1.searchList`, `cat1.searchResults.mapResults`, …).
4. Add assertions to `ListingPortalSearchParserTest` for the fields the UI depends on
   (`externalId`, `price`, `bedrooms`, `address`, `detailUrl`, attribution).
5. Run `gradle :app:testDebugUnitTest --tests "com.example.domain.intelligence.scrape.*"`.

The `html-cards` reader is the safety net: after a payload redesign it usually still returns listings
from the server-rendered cards, with `strategy=html-cards` so the UI can show lower fidelity.

## Tests

* `ZillowSearchUrlBuilderTest` — location tokens, filter encoding, pagination, percent-encoding, base64.
* `ListingPortalSearchParserTest` — all three readers against saved pages, plus brace/phone/money edge cases.
* `ListingContactExtractorTest` — routes when a number exists, honest gaps when it does not, page-phone ambiguity.
* `ListingScrapePolicyTest` — consent, host allowlist, robots enforce/advisory, unreadable robots, rate limiter.
* `ListingPortalScraperTest` — success, bot wall, 429, drift, empty, transport, 404, consent, robots, pagination, dedupe, caps.
* `HttpListingPageFetcherTest` — identity headers, body cap, robots status semantics, transport classification (MockWebServer).

Fixtures live in `app/src/test/resources/fixtures/listing-search/`.

## Known limits

* Search only; listing-detail enrichment still goes through `domain.propertyurl` (paste a link).
* One portal (Zillow). Redfin/Realtor.com need their own URL builder and state paths — the parser and
  scraper are source-agnostic, the builder and `allowedHosts` are not.
* Emails are rarely published on cards, so the email chip is normally absent rather than fabricated.
* Broker-paid ("Premier Agent") placements are flagged on the card, because their position in the
  result list is bought rather than ranked.
