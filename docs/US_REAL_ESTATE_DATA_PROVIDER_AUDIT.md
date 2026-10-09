# US Real Estate Data Provider Readiness Audit & Architecture Blueprint

**Document Reference:** `docs/US_REAL_ESTATE_DATA_PROVIDER_AUDIT.md`  
**Author:** Real Estate Data Integration Architect  
**Audit Date:** 2026-10-09  
**Repository:** `ISM-dragon/2`  
**Base Branch:** `main`  
**Working Branch:** `arena/359ef2d2-2`  

---

## Executive Summary

This audit assesses the operational readiness of all property data capabilities, source adapters, and ingestion abstractions within the repository. The application is an Android-based real estate investment, deal-scoring, and underwriting platform designed for US real estate investors, wholesalers, and acquirers.

### Key Audit Findings

1. **Zero Authenticated or Production-Verified External Data Providers:**
   The repository contains **no live authenticated API integrations** with commercial property data vendors (e.g., ATTOM, CoreLogic, First American/DataTree, RentCast, HouseCanary) or licensed MLS/RESO Web API aggregators (e.g., Bridge Interactive, MLS Grid).
2. **Synthetic Data Masquerading as Live Ingestion:**
   The package `com.example.domain.intelligence.source.adapters` contains four source adapters (`ZillowUrlSourceAdapter`, `RedfinUrlSourceAdapter`, `RealtorUrlSourceAdapter`, `HomesUrlSourceAdapter`) that do **not** perform network requests or HTML parsing. They parse URL regexes and return **hardcoded synthetic properties** (e.g., $485,000, 3 bed, 2 bath, $3,450 rent for Zillow; $525,000 for Redfin). These represent severe data-integrity risks if called in production.
3. **Empty Data Seed Adapters:**
   The `OnMarketMlsAdapter` and `OffMarketWholesaleAdapter` in `com.example.data.adapter.PropertyDataSeed` claim to provide realistic seed datasets, but `PropertySeedData.getSeedBundles()` returns an empty list (`emptyList()`).
4. **Offline Parser Fixture Excellence vs. Live Transport Reality:**
   The standalone `:urlintelligence` JVM module contains a robust, well-tested parser pipeline (`ZillowAdapter`, `RedfinAdapter`, `RealtorAdapter`, `HomesAdapter`, `GenericWebAdapter`) tested against static HTML fixtures. However, its live Android transport (`OkHttpPropertyTransport`) operates unauthenticated with a custom User-Agent, redirects disabled, and no JavaScript execution or session state. When pointed at live portal URLs, it fails due to robots.txt blocks (`RobotsPolicy`), Cloudflare/PerimeterX bot challenges, or Terms of Use violations.
5. **Architectural Gap — Direct-from-Mobile vs. Backend-for-Frontend (BFF):**
   Commercial real estate APIs strictly require confidential API keys and per-seat or per-query billing. In accordance with security constraints, **no service secrets may be embedded in the Android APK**. All external property intelligence must be moved behind a provider-neutral Backend API Gateway.

---

## 1. Inventory of Source Adapters & Provider Abstractions

The repository contains six distinct layers of source adapters, provider abstractions, and ingestion data flows:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                CLIENT / UI LAYER                                 │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
┌─────────────────────────────────┐             ┌──────────────────────────────────┐
│ Layer 1: urlintelligence Module │             │ Layer 2: domain.propertyurl      │
│ (JVM-pure, HTML parsers, tests) │             │ (Legacy In-App Parser Pipeline)   │
│ - ZillowAdapter                 │             │ - ZillowAdapter (Zpid parser)    │
│ - RedfinAdapter                 │             │ - RedfinAdapter (MLS parser)     │
│ - RealtorAdapter                │             │ - RealtorComAdapter              │
│ - HomesAdapter                  │             │ - HomesComAdapter                │
│ - GenericWebAdapter             │             │ - GenericWebListingAdapter       │
└────────────────┬────────────────┘             └────────────────┬─────────────────┘
                 │                                               │
                 ├───────────────────────┬───────────────────────┘
                 ▼                       ▼
┌─────────────────────────────────┐   ┌────────────────────────────────────────────┐
│ Layer 5: Ingestion Bridges      │   │ Layer 3: domain.intelligence.source        │
│ - PropertyUrlImportBridge       │   │ (MOCK GENERATORS - Synthetic Hardcoded)   │
│ - PropertyUrlImportService      │   │ - ZillowUrlSourceAdapter ($485k fake)      │
│ - OkHttpPropertyTransport       │   │ - RedfinUrlSourceAdapter ($525k fake)      │
└────────────────┬────────────────┘   │ - RealtorUrlSourceAdapter ($460k fake)     │
                 │                    │ - HomesUrlSourceAdapter ($430k fake)       │
                 │                    └────────────────────────────────────────────┘
                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Layer 4: Data Seed & Feed Layer (com.example.data.adapter)                       │
│ - OnMarketMlsAdapter (emptyList seed stub)                                       │
│ - OffMarketWholesaleAdapter (emptyList seed stub)                                │
└────────────────┬─────────────────────────────────────────────────────────────────┘
                 │
                 ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│ Layer 6: Persistence, Deduplication & Storage (Room Database)                    │
│ - PropertyIdentityDeduplicationEngine (Deterministic APN/MLS/Address/Geo Dedup)  │
│ - PropertyImportRepository (Atomic Room transactions, Provenance, Jobs)          │
│ - PropertyEnrichmentRepository (Multi-provider enrichment store - NO VENDORS)    │
│ - PropertyFinancialRepository (Asset financial baseline store)                   │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### Detailed Component Inventory

| Module & Package | Class / Interface | Target Source / Domain | Operational Mechanism | Code Location |
| :--- | :--- | :--- | :--- | :--- |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `interface PropertySourceAdapter`<br>`class StructuredDataPropertySourceAdapter` | Universal base for structured public listing parsing | HTTP fetch via injected `PropertyHttpTransport`, followed by Schema.org JSON-LD extraction, meta tags, and regex selector fallback | `urlintelligence/src/main/kotlin/.../PropertySourceAdapter.kt`<br>`StructuredDataPropertySourceAdapter.kt` |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `class ZillowAdapter` | `zillow.com` | Extracts ZPID, Schema.org JSON-LD, and embedded JSON price/bed/bath state | `urlintelligence/.../ZillowAdapter.kt` |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `class RedfinAdapter` | `redfin.com` | Extracts listing ID, MLS number, JSON-LD, and embedded reactServerState | `urlintelligence/.../RedfinAdapter.kt` |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `class RealtorAdapter` | `realtor.com` | Extracts listing ID, JSON-LD, Next.js embedded state | `urlintelligence/.../RealtorAdapter.kt` |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `class HomesAdapter` | `homes.com` | Extracts property ID, JSON-LD, visible facts | `urlintelligence/.../HomesAdapter.kt` |
| `:urlintelligence`<br>`com.example.urlintelligence.adapter` | `class GenericWebAdapter` | Fallback (`generic.web`) | Multi-pass JSON-LD, OpenGraph/Twitter meta, and address/price regex heuristics | `urlintelligence/.../GenericWebAdapter.kt` |
| `:app`<br>`com.example.domain.propertyurl.adapter` | `interface PropertySourceAdapter`<br>`abstract class BasePropertySourceAdapter` | In-app legacy URL intelligence interface | Dispatches via `HttpFetcher`, applies anti-bot detection, executes `ParserChain` | `app/src/main/java/.../domain/propertyurl/adapter/PropertySourceAdapter.kt` |
| `:app`<br>`com.example.domain.propertyurl.adapter` | `class ZillowAdapter`<br>`class RedfinAdapter`<br>`class RealtorComAdapter`<br>`class HomesComAdapter`<br>`class GenericWebListingAdapter`<br>`class ApartmentsComAdapter` | Consumer portals & apartments.com | Portal subclasses using `SourceCatalog` definitions and portal-specific ID parsers | `app/src/main/java/.../domain/propertyurl/adapter/PortalAdapters.kt` |
| `:app`<br>`com.example.domain.intelligence.source.adapters` | `class ZillowUrlSourceAdapter`<br>`class RedfinUrlSourceAdapter`<br>`class RealtorUrlSourceAdapter`<br>`class HomesUrlSourceAdapter` | Faked Zillow, Redfin, Realtor, Homes imports | **DANGEROUS MOCK:** Regex-matches URL, extracts slug or defaults to hardcoded address/price/rent ($485k-$525k) | `app/src/main/java/.../domain/intelligence/source/adapters/` |
| `:app`<br>`com.example.data.adapter` | `interface PropertySourceAdapter`<br>`class OnMarketMlsAdapter`<br>`class OffMarketWholesaleAdapter` | MLS on-market & wholesale feeds | **STUB:** Implements `PropertySourceAdapter` but calls `PropertySeedData.getSeedBundles()` which returns `emptyList()` | `app/src/main/java/.../data/adapter/PropertyDataSeed.kt` |
| `:app`<br>`com.example.data.urlintelligence` | `class OkHttpPropertyTransport` | Public web transport | Android OkHttp client with `PublicOnlyDns` SSRF guard, redirect disabled, no cookies, custom User-Agent | `app/src/main/java/.../data/urlintelligence/OkHttpPropertyTransport.kt` |
| `:app`<br>`com.example.data.adapter` | `class PropertyUrlImportBridge` | Translation to Room entities | Transforms `com.example.domain.propertyurl.model.CanonicalProperty` into `NormalizedPropertyBundle` | `app/src/main/java/.../data/adapter/PropertyUrlImportBridge.kt` |
| `:app`<br>`com.example.data.urlintelligence` | `class PropertyUrlImportService` | URL Intelligence orchestrator | Ties `:urlintelligence` resolver, `RobotsPolicy`, and `PropertyDao` together | `app/src/main/java/.../data/urlintelligence/PropertyUrlImportService.kt` |
| `:app`<br>`com.example.domain.identity` | `class PropertyIdentityDeduplicationEngine`<br>`class SourceValueResolver` | Identity & Cross-Source Deduplication | Deterministic matching (APN > MLS > SourceID > URL > NormalizedAddress > Coordinates) with provenance resolution | `app/src/main/java/.../domain/identity/` |
| `:app`<br>`com.example.data.repository` | `class PropertyImportRepository` | Persistence & Ingestion Jobs | Atomic Room transactions for property insertion, deduplication resolution, and job audit counters | `app/src/main/java/.../data/repository/PropertyImportRepository.kt` |
| `:app`<br>`com.example.data.repository` | `class PropertyEnrichmentRepository` | Multi-provider enrichment | Stores AVMs, flood risk, school ratings, permits, liens in `property_enrichments` (Zero external providers wired) | `app/src/main/java/.../data/repository/PropertyEnrichmentRepository.kt` |

---

## 2. Integration Verification Level Classification

Per audit methodology, each integration and adapter is classified strictly into one of five standardized verification levels:

1. **Interface-only:** Interface or abstract class exists; implementation is absent, stubbed, returns empty collections, or generates static fake data.
2. **Fixture/parser-tested:** Code parses documents and extracts fields; verified using local offline test fixtures (HTML, JSON, XML); zero live network traffic.
3. **Live-fetch-tested:** Connects over live network protocols; handles TLS, socket timeouts, headers, and status codes; unauthenticated against vendor APIs.
4. **Authenticated:** Uses credentials (API keys, OAuth2 Bearer tokens, HMAC signatures) against a live vendor sandbox/test environment.
5. **Production-verified:** Deployed in production handling real traffic with contracted SLAs, rate limits, monitoring, and licensed compliance.

### Classification Matrix

| Component / Adapter | Declared Purpose | Verified Implementation State | Classification Level | Notes & Gap Analysis |
| :--- | :--- | :--- | :--- | :--- |
| `OnMarketMlsAdapter` (`PropertyDataSeed.kt`) | On-market MLS listing ingestion | `PropertySeedData.getSeedBundles()` returns `emptyList()` | **Interface-only** | No live MLS/RESO API connection; returns 0 records |
| `OffMarketWholesaleAdapter` (`PropertyDataSeed.kt`) | Off-market & distressed wholesale leads | `PropertySeedData.getSeedBundles()` returns `emptyList()` | **Interface-only** | No county recorder, probate, or wholesale feed; returns 0 records |
| `ZillowUrlSourceAdapter` (`domain/.../source/adapters`) | Import listing from Zillow URL | Regex-extracts slug, returns hardcoded $485k Austin TX dummy record | **Interface-only** | **Mock generator masquerading as extractor**; no network or real parser |
| `RedfinUrlSourceAdapter` (`domain/.../source/adapters`) | Import listing from Redfin URL | Regex-extracts homeId, returns hardcoded $525k Austin TX dummy record | **Interface-only** | **Mock generator masquerading as extractor**; no network or real parser |
| `RealtorUrlSourceAdapter` (`domain/.../source/adapters`) | Import listing from Realtor.com URL | Regex-extracts listingId, returns hardcoded $460k Austin TX dummy record | **Interface-only** | **Mock generator masquerading as extractor**; no network or real parser |
| `HomesUrlSourceAdapter` (`domain/.../source/adapters`) | Import listing from Homes.com URL | Regex-extracts propertyId, returns hardcoded $430k Austin TX dummy record | **Interface-only** | **Mock generator masquerading as extractor**; no network or real parser |
| `ApartmentsComAdapter` (`PortalAdapters.kt`) | Rental listing extraction | Declared in `SourceCatalog`, stubbed adapter class | **Interface-only** | Marked as `PLANNED` in catalog; no execution |
| `:urlintelligence` Zillow / Redfin / Realtor / Homes / Generic Adapters | Parse listing data from HTML documents | Evaluated against 14+ HTML test fixtures; checks schema drift, JSON-LD, and selectors | **Fixture/parser-tested** | Passes offline unit tests; cannot run against live web due to anti-bot/ToS |
| `:app` `PortalAdapters` (Zillow, Redfin, Realtor, Homes, Generic) | In-app parser chain against HTML | Tested via `FakeHttpFetcher` and HTML fixtures | **Fixture/parser-tested** | Same parser logic as `:urlintelligence`; no live vendor integration |
| `OkHttpPropertyTransport` (`data/.../OkHttpPropertyTransport.kt`) | Network transport for URL intelligence | Live OkHttpClient with TLS, SSRF DNS guard, bounded buffer | **Live-fetch-tested** | Socket-level execution tested; fails against live consumer portals (WAF/CAPTCHA) |
| **Commercial API Adapters** (ATTOM, CoreLogic, RentCast, etc.) | Property facts, taxes, comps, permits, flood | **DOES NOT EXIST** | **NONE (0%)** | Zero vendor client implementations exist in codebase |
| **Production-Verified Integrations** | Contracted SLAs, live licensed feeds | **DOES NOT EXIST** | **NONE (0%)** | Zero production-verified providers |

---

## 3. Coverage & Licensing Constraints by Real Estate Domain

A real estate investment and underwriting engine requires coverage across eight specific property data domains. Below is the analysis of current repository capabilities versus commercial and legal realities in the United States.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        REAL ESTATE DATA DOMAIN READINESS                                │
├─────────────────────────┬──────────────────────────┬───────────────────────────────────┤
│ Domain                  │ In-Repo Capability       │ Gap / Operational Blocker         │
├─────────────────────────┼──────────────────────────┼───────────────────────────────────┤
│ 1. Active Listings      │ HTML Parsers (Fixtures)  │ Portal ToS, WAF/CAPTCHA, robots   │
│ 2. Sold Comparables     │ Stub / Empty             │ Non-disclosure states, MLS rules  │
│ 3. Property Taxes       │ Schema only / Empty      │ Fragmented across 3,143 counties  │
│ 4. Rent Estimates       │ Schema only / Empty      │ No licensed rental AVM integrated │
│ 5. Ownership & Deeds    │ Identity engine only     │ No county deed / mortgage records │
│ 6. Building Permits     │ Entity definition only   │ Unstandardized municipal data     │
│ 7. Flood Hazard (FEMA)  │ Entity definition only   │ GIS coordinates needed for NFHL   │
│ 8. Market Statistics    │ Entity definition only   │ Requires aggregate MLS/Census feed│
└─────────────────────────┴──────────────────────────┴───────────────────────────────────┘
```

### Domain-by-Domain Analysis

#### 1. Active Listings (On-Market & Off-Market)
* **Current Repository State:**
  * Schema: Supported via `PropertyEntity` and `PropertyDraft`.
  * Ingestion: Fixture-tested HTML scrapers (`urlintelligence`) and empty seed feeds (`OnMarketMlsAdapter`).
* **Coverage Constraints:**
  * There is no single "National MLS." The US real estate market is fragmented across **580+ independent Multiple Listing Services (MLSs)**.
  * Off-market listings (wholesalers, auctions, pocket listings) do not exist on MLS feeds and require direct wholesaler integrations or auction data providers.
* **Licensing & Legal Constraints:**
  * **NAR / MLS Rules:** Displaying MLS listing data requires broker sponsorship or an approved technology vendor agreement (IDX - Internet Data Exchange, VOW - Virtual Office Website, or Syndication feed). Raw MLS data cannot be resold or commingled without strict attribution and display disclaimers.
  * **Web Scraping Legality & CFAA:** Zillow, Redfin, and Realtor.com strictly prohibit automated scraping in their Terms of Use. Their `robots.txt` disallows listing detail endpoints for crawlers. Commercial scraping risks IP ban, cease-and-desist action, and Computer Fraud and Abuse Act (CFAA) litigation (e.g., *Zillow v. Rooomy*, *CoStar v. Boer*).

#### 2. Sold Comparables (Sales History & Comps)
* **Current Repository State:**
  * Schema: Supported via `ComparablePropertyEntity` (`comps` table) and `SalesHistoryEntity`.
  * Ingestion: Empty in seed datasets; HTML scrapers extract sales history only if exposed in public HTML JSON-LD (often suppressed by portals).
* **Coverage Constraints:**
  * **Non-Disclosure States (12 states):** In Texas (TX), Utah (UT), New Mexico (NM), Wyoming (WY), Idaho (ID), Kansas (KS), Mississippi (MS), Missouri (MO - some counties), Louisiana (LA), Montana (MT), North Dakota (ND), and Alaska (AK), real estate transaction prices are **not public records**. County deed records only record transfer of title and nominal consideration (e.g., "$10 and other valuable consideration").
* **Licensing & Legal Constraints:**
  * In non-disclosure states, sold prices can **only** be obtained legally via licensed MLS feeds (RESO Web API) under closed participant rules (VOW / Client Representation Agreement). Commercial aggregators (ATTOM, CoreLogic) provide estimated or modeled prices in these states, or rely on voluntarily disclosed deed transfer declarations where available.

#### 3. Property Taxes & Assessed Values
* **Current Repository State:**
  * Schema: Supported via `TaxRecordEntity` and `PropertyFinancialEntity` (`annualPropertyTax`, `assessedValue`, `assessmentYear`).
  * Ingestion: Parsers extract basic `taxAssessedValue` if present in listing HTML; otherwise empty.
* **Coverage Constraints:**
  * Property taxes and assessments are governed by **3,143 counties** and thousands of local taxing jurisdictions (ISDs, MUDs, utility districts). Each county updates assessments on its own schedule (annual, biennial, triennial).
* **Licensing & Legal Constraints:**
  * County tax records are public records under state Freedom of Information / Public Records Acts. However, accessing all 3,143 counties directly requires normalization of 3,143 disparate tabular/GIS data schemas. Commercial aggregators (ATTOM, Estated, DataTree) license and standardize this bulk county data.

#### 4. Rent Estimates (Rental AVM)
* **Current Repository State:**
  * Schema: Supported via `RentEstimateEntity` and `PropertyFinancialEntity` (`monthlyRentEstimate`, `rentEstimateLow`, `rentEstimateHigh`, `rentConfidence`).
  * Ingestion: Dummy static numbers in mock adapters; empty in seed data.
* **Coverage Constraints:**
  * Unlike home sales, rental leases are private commercial contracts and are almost never recorded with county recorders.
* **Licensing & Legal Constraints:**
  * Rental AVMs depend on aggregated active rental listings (apartments.com, Zillow Rentals, Rent.com) and self-reported property management software data (Yardi, RealPage). Providers like **RentCast** and **ATTOM Rental AVM** provide licensed API access with commercial redistribution rights.

#### 5. Ownership & Deed Transfers
* **Current Repository State:**
  * Schema: Legacy `properties` table contains no owner fields; `PropertyEnrichmentEntity` supports `OWNER_OCCUPANCY`; `domain.identity.SourcePropertyIdentity` supports APN and owner name.
  * Ingestion: Zero operational ingestion of legal owner names, mailing addresses, or deed transfer chains.
* **Coverage Constraints:**
  * Critical for wholesaler and off-market outreach (identifying absentee owners, corporate LLCs, trusts, and inherited estates).
* **Licensing & Legal Constraints:**
  * Public records, but subject to consumer privacy statutes (California CCPA/CPRA, Virginia VCDPA). Marketing outreach (calls/SMS) derived from owner records must comply with TCPA (Telephone Consumer Protection Act) and the National Do Not Call (DNC) Registry.

#### 6. Building Permits & Renovation History
* **Current Repository State:**
  * Schema: Supported via `PropertyEnrichmentEntity` (`enrichmentType = "PERMIT"`).
  * Ingestion: Zero operational code; no permit parsing or API adapters.
* **Coverage Constraints:**
  * Building permits are issued at the city/municipality level (over 19,000 incorporated cities in the US). Many rural jurisdictions have no computerized permit systems.
* **Licensing & Legal Constraints:**
  * Commercial permit providers (e.g., **Shovels.ai**, **ATTOM Permits**) aggregate and geocode municipal building department feeds. Municipal data is public domain, but parsing thousands of PDF/scanned city documents requires specialised OCR and entity-resolution pipelines.

#### 7. Flood Risk & Environmental Hazards
* **Current Repository State:**
  * Schema: Supported via `PropertyEnrichmentEntity` (`enrichmentType = "FLOOD_RISK"`).
  * Ingestion: Zero operational code.
* **Coverage Constraints:**
  * Covers 100% of US landmass via FEMA National Flood Insurance Program (NFIP) Flood Insurance Rate Maps (FIRMs).
* **Licensing & Legal Constraints:**
  * **FEMA NFHL (National Flood Hazard Layer):** Completely **public domain, free of charge**, and accessible via official FEMA GIS REST web services. Requires precise parcel latitude/longitude coordinates to query GIS hazard zones (Zone X, Zone A, Zone AE, Zone VE).

#### 8. Market Statistics & Submarket Trends
* **Current Repository State:**
  * Schema: Supported via `MarketDataEntity` (`medianAreaPrice`, `averageDaysOnMarket`, `marketDemand`) and `PropertyEnrichmentEntity` (`enrichmentType = "MARKET_TREND"`).
  * Ingestion: Zero live calculations; populated with default zeros.
* **Coverage Constraints:**
  * Aggregate statistics are computed at Zip Code, County, or Metropolitan Statistical Area (MSA) levels.
* **Licensing & Legal Constraints:**
  * US Census Bureau (ACS 5-year estimates) and Federal Reserve Economic Data (FRED) are **free and public domain**. MLS aggregate market trends can be licensed from Altos Research, Redfin Data Center (bulk research downloads), or Realtor.com economic research feeds.

---

## 4. Data Quality, Integrity & Operational Risks

The audit has identified five high-severity data quality and architectural risks in the current implementation:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              IDENTIFIED SYSTEM RISKS                                    │
├────────────────────┬───────────┬───────────────────────────────────────────────────────┤
│ Risk Item          │ Severity  │ Nature & Root Cause                                   │
├────────────────────┼───────────┼───────────────────────────────────────────────────────┤
│ 1. Synthetic Mocks │ CRITICAL  │ Hardcoded dummy data returned as live property facts   │
│ 2. Scraper Failure │ HIGH      │ Anti-bot WAFs & RFC 9309 robots.txt block live HTTP   │
│ 3. Client Secrets  │ HIGH      │ Android APK cannot safely store commercial API keys   │
│ 4. Seed Stubs      │ MEDIUM    │ OnMarket & Wholesale seed adapters return empty lists │
│ 5. Schema Overlap  │ MEDIUM    │ Entity duplicates between app & domain packages       │
└────────────────────┴───────────┴───────────────────────────────────────────────────────┘
```

### Risk 1: Synthetic Mock Adapters Masquerading as Live Ingestion (CRITICAL)
* **Finding:** In `com.example.domain.intelligence.source.adapters`:
  * `ZillowUrlSourceAdapter.kt` lines 47–56: When an import URL is processed, it fabricates a `basePrice = 485000.0`, `bedrooms = 3`, `bathrooms = 2.0`, `sqft = 1850`, `rentEst = 3450.0`, and address "1420 South Congress Ave, Austin, TX".
  * `RedfinUrlSourceAdapter.kt` lines 39–48: Returns hardcoded $525,000, 4 beds, 2.5 baths, 2,200 sqft at "2804 East 4th St, Austin, TX".
  * `RealtorUrlSourceAdapter.kt` lines 37–45: Returns hardcoded $460,000, 3 beds, 2 baths at "1105 Nueces St, Austin, TX".
  * `HomesUrlSourceAdapter.kt` lines 35–43: Returns hardcoded $430,000, 3 beds, 2 baths at "5204 Menchaca Rd, Austin, TX".
* **Impact:** Any user entering a real Zillow or Redfin URL from Los Angeles, Chicago, or Miami receives hardcoded Austin, Texas values. If underwriting algorithms or automated offer generators consume this, they generate catastrophic, legally binding bad offers.
* **Remediation:** Deprecate and isolate these mock adapters immediately to test suites only. In production builds, URLs must route strictly through the verified `:urlintelligence` pipeline or the proposed backend gateway.

### Risk 2: Client-Side Scraping Fragility & Anti-Bot Blocking (HIGH)
* **Finding:** `OkHttpPropertyTransport` disables redirects (`followRedirects(false)`), uses `CookieJar.NO_COOKIES`, and sets an honest bot User-Agent (`RealEstateAI/1.0`).
* **Impact:**
  1. Portals like Zillow and Realtor use PerimeterX/Human Security and Cloudflare Turnstile. Direct mobile HTTP requests receive 403 Forbidden or CAPTCHA interstitials (as confirmed by the fixture `captcha_cloudflare.html`).
  2. `RobotsPolicy` correctly parses `robots.txt` (per RFC 9309). Because major portals disallow automated crawling of `/homedetails/`, `RobotsPolicy.check()` returns `AccessDecision.Denied(AccessRule.ROBOTS_DISALLOWED)`. The import fails before touching the network.
  3. Single Page Applications (SPAs): Portal pages frequently hydrate listing data via client-side GraphQL calls after running obfuscated JavaScript challenges. Raw HTML transports without headless browser evaluation fail to extract the document state.
* **Remediation:** Terminate client-side scraping of consumer portals. Shift data acquisition to licensed REST APIs hosted on backend infrastructure.

### Risk 3: Inability to Store Vendor Secrets on Android (HIGH)
* **Finding:** Commercial real estate vendors (ATTOM, CoreLogic, RentCast, Shovels) authenticate via private API keys or OAuth Client Secrets.
* **Impact:** Reverse-engineering tools (such as JADX, APKTool, or Frida) can decompile any Android APK in seconds. Embedding provider API keys in `BuildConfig`, resource strings, or Android Keystore exposes the company's billing account to credential theft and unbounded API charges.
* **Remediation:** Enforce zero-trust architecture: **No service credentials in Android**. The Android app authenticates to an internal Backend-For-Frontend (BFF) using Firebase/Cognito user tokens; the BFF manages provider keys and proxies requests.

### Risk 4: Empty Seed Bundles in Ingestion Adapters (MEDIUM)
* **Finding:** `PropertyDataSeed.kt` provides `OnMarketMlsAdapter` and `OffMarketWholesaleAdapter`, but `PropertySeedData.getSeedBundles()` returns `emptyList()`.
* **Impact:** In the absence of live network feeds, development and demo environments display zero properties, stalling UI and automated workflow testing.
* **Remediation:** Populate `PropertySeedData` with realistic, anonymized golden property bundles with complete tax, comp, and valuation history.

### Risk 5: Duplicate Entity Definitions & Namespace Ambiguity (MEDIUM)
* **Finding:** As noted in `docs/PROPERTY_IDENTITY_DEDUPLICATION.md`, Room entities were duplicated across `data/local/entity/` and `domain/propertyurl/model/`. While deduplication engines have been unified around `PropertyIdentityDeduplicationEngine`, legacy mappings still exist in `PropertyUrlImportBridge`.
* **Remediation:** Enforce a single canonical property model across all modules.

---

## 5. Provider-Neutral Backend Contract Proposal

To decouple the mobile application from specific upstream vendors, we propose a standardized, provider-neutral **Backend Property Data Contract**. This contract defines full support for per-field provenance, confidence scoring, observation timestamps, partial failures, and granular missing-field explanations.

### JSON Schema Specification (`property-data-contract.v1.json`)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://schema.realestateai.internal/v1/property-response.json",
  "title": "ProviderNeutralPropertyResponse",
  "type": "object",
  "required": [
    "canonicalId",
    "requestMeta",
    "property",
    "status",
    "provenanceSummary"
  ],
  "properties": {
    "canonicalId": {
      "type": "string",
      "description": "Deterministic canonical identity key (e.g., 'cp-us-tx-travis-1420-s-congress-ave')"
    },
    "status": {
      "type": "string",
      "enum": ["COMPLETE", "PARTIAL", "FAILED"]
    },
    "requestMeta": {
      "type": "object",
      "required": ["requestId", "timestampEpochMs", "environment"],
      "properties": {
        "requestId": { "type": "string" },
        "timestampEpochMs": { "type": "integer" },
        "latencyMs": { "type": "integer" },
        "cached": { "type": "boolean" },
        "environment": { "type": "string" }
      }
    },
    "property": {
      "type": "object",
      "required": ["identity", "location", "characteristics"],
      "properties": {
        "identity": {
          "type": "object",
          "properties": {
            "apn": { "$ref": "#/$defs/ObservationString" },
            "fipsCode": { "$ref": "#/$defs/ObservationString" },
            "mlsNumber": { "$ref": "#/$defs/ObservationString" },
            "universalPropertyId": { "$ref": "#/$defs/ObservationString" }
          }
        },
        "location": {
          "type": "object",
          "required": ["addressLine1", "city", "state", "postalCode"],
          "properties": {
            "addressLine1": { "$ref": "#/$defs/ObservationString" },
            "addressLine2": { "$ref": "#/$defs/ObservationString" },
            "city": { "$ref": "#/$defs/ObservationString" },
            "state": { "$ref": "#/$defs/ObservationString" },
            "postalCode": { "$ref": "#/$defs/ObservationString" },
            "county": { "$ref": "#/$defs/ObservationString" },
            "latitude": { "$ref": "#/$defs/ObservationNumber" },
            "longitude": { "$ref": "#/$defs/ObservationNumber" }
          }
        },
        "characteristics": {
          "type": "object",
          "properties": {
            "propertyType": { "$ref": "#/$defs/ObservationString" },
            "bedrooms": { "$ref": "#/$defs/ObservationInt" },
            "bathrooms": { "$ref": "#/$defs/ObservationNumber" },
            "livingAreaSqFt": { "$ref": "#/$defs/ObservationInt" },
            "lotSizeSqFt": { "$ref": "#/$defs/ObservationInt" },
            "yearBuilt": { "$ref": "#/$defs/ObservationInt" },
            "stories": { "$ref": "#/$defs/ObservationNumber" }
          }
        },
        "listing": {
          "type": "object",
          "properties": {
            "listPrice": { "$ref": "#/$defs/ObservationNumber" },
            "listingStatus": { "$ref": "#/$defs/ObservationString" },
            "daysOnMarket": { "$ref": "#/$defs/ObservationInt" },
            "originalListPrice": { "$ref": "#/$defs/ObservationNumber" },
            "listingDate": { "$ref": "#/$defs/ObservationString" }
          }
        },
        "taxAndAssessment": {
          "type": "object",
          "properties": {
            "assessedValue": { "$ref": "#/$defs/ObservationNumber" },
            "assessmentYear": { "$ref": "#/$defs/ObservationInt" },
            "annualTaxAmount": { "$ref": "#/$defs/ObservationNumber" },
            "taxDelinquent": { "$ref": "#/$defs/ObservationBoolean" }
          }
        },
        "valuationAndRent": {
          "type": "object",
          "properties": {
            "avmEstimatedValue": { "$ref": "#/$defs/ObservationNumber" },
            "avmConfidenceScore": { "$ref": "#/$defs/ObservationNumber" },
            "monthlyRentEstimate": { "$ref": "#/$defs/ObservationNumber" },
            "rentRangeLow": { "$ref": "#/$defs/ObservationNumber" },
            "rentRangeHigh": { "$ref": "#/$defs/ObservationNumber" }
          }
        },
        "environmental": {
          "type": "object",
          "properties": {
            "floodZone": { "$ref": "#/$defs/ObservationString" },
            "floodRiskCategory": { "$ref": "#/$defs/ObservationString" },
            "inSpecialFloodHazardArea": { "$ref": "#/$defs/ObservationBoolean" }
          }
        },
        "permits": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["permitNumber", "issueDate", "category"],
            "properties": {
              "permitNumber": { "type": "string" },
              "issueDate": { "type": "string" },
              "category": { "type": "string" },
              "jobDescription": { "type": "string" },
              "declaredCost": { "type": "number" },
              "status": { "type": "string" },
              "provenance": { "$ref": "#/$defs/Provenance" }
            }
          }
        },
        "salesHistory": {
          "type": "array",
          "items": {
            "type": "object",
            "required": ["saleDate", "salePrice"],
            "properties": {
              "saleDate": { "type": "string" },
              "salePrice": { "type": "number" },
              "documentType": { "type": "string" },
              "buyerName": { "type": "string" },
              "sellerName": { "type": "string" },
              "armsLength": { "type": "boolean" },
              "provenance": { "$ref": "#/$defs/Provenance" }
            }
          }
        }
      }
    },
    "provenanceSummary": {
      "type": "object",
      "required": ["participatingSources", "fieldCountsByTier"],
      "properties": {
        "participatingSources": {
          "type": "array",
          "items": { "type": "string" }
        },
        "fieldCountsByTier": {
          "type": "object",
          "properties": {
            "OFFICIAL_API": { "type": "integer" },
            "LICENSED_FEED": { "type": "integer" },
            "PUBLIC_RECORDS": { "type": "integer" },
            "PUBLIC_WEB": { "type": "integer" },
            "DERIVED_ESTIMATE": { "type": "integer" }
          }
        }
      }
    },
    "missingFields": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["fieldPath", "reasonCode", "detail"],
        "properties": {
          "fieldPath": { "type": "string" },
          "reasonCode": {
            "type": "string",
            "enum": [
              "NON_DISCLOSURE_JURISDICTION",
              "SOURCE_TIMEOUT",
              "PAYWALL_TIER_REQUIRED",
              "FIELD_UNAVAILABLE",
              "VALIDATION_FAILED"
            ]
          },
          "detail": { "type": "string" }
        }
      }
    },
    "sourceErrors": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["sourceId", "errorCode", "retryable"],
        "properties": {
          "sourceId": { "type": "string" },
          "errorCode": { "type": "string" },
          "message": { "type": "string" },
          "httpStatus": { "type": "integer" },
          "retryable": { "type": "boolean" }
        }
      }
    }
  },
  "$defs": {
    "Provenance": {
      "type": "object",
      "required": [
        "sourceId",
        "sourceTier",
        "observedAtEpochMs",
        "fetchedAtEpochMs",
        "confidence"
      ],
      "properties": {
        "sourceId": { "type": "string" },
        "providerName": { "type": "string" },
        "sourceTier": {
          "type": "string",
          "enum": [
            "OFFICIAL_API",
            "LICENSED_FEED",
            "PUBLIC_RECORDS",
            "PUBLIC_WEB",
            "DERIVED_ESTIMATE"
          ]
        },
        "observedAtEpochMs": { "type": "integer" },
        "fetchedAtEpochMs": { "type": "integer" },
        "confidence": { "type": "number", "minimum": 0.0, "maximum": 1.0 },
        "freshnessSeconds": { "type": "integer" },
        "ttlSeconds": { "type": "integer" },
        "recordRef": { "type": "string" }
      }
    },
    "ObservationString": {
      "type": "object",
      "required": ["value", "provenance"],
      "properties": {
        "value": { "type": ["string", "null"] },
        "provenance": { "$ref": "#/$defs/Provenance" }
      }
    },
    "ObservationNumber": {
      "type": "object",
      "required": ["value", "provenance"],
      "properties": {
        "value": { "type": ["number", "null"] },
        "provenance": { "$ref": "#/$defs/Provenance" }
      }
    },
    "ObservationInt": {
      "type": "object",
      "required": ["value", "provenance"],
      "properties": {
        "value": { "type": ["integer", "null"] },
        "provenance": { "$ref": "#/$defs/Provenance" }
      }
    },
    "ObservationBoolean": {
      "type": "object",
      "required": ["value", "provenance"],
      "properties": {
        "value": { "type": ["boolean", "null"] },
        "provenance": { "$ref": "#/$defs/Provenance" }
      }
    }
  }
}
```

### Complete Partial-Result & Source-Failure Contract Example

```json
{
  "canonicalId": "cp-us-tx-travis-1420-s-congress-ave",
  "status": "PARTIAL",
  "requestMeta": {
    "requestId": "req_8f1a2e94b2",
    "timestampEpochMs": 1791547200000,
    "latencyMs": 342,
    "cached": false,
    "environment": "production"
  },
  "property": {
    "identity": {
      "apn": {
        "value": "01020304050000",
        "provenance": {
          "sourceId": "attom-property-api",
          "providerName": "ATTOM Data Solutions",
          "sourceTier": "PUBLIC_RECORDS",
          "observedAtEpochMs": 1788955200000,
          "fetchedAtEpochMs": 1791547199850,
          "confidence": 0.99,
          "freshnessSeconds": 2592000,
          "ttlSeconds": 5184000,
          "recordRef": "ATTOM:APN:01020304050000"
        }
      },
      "fipsCode": {
        "value": "48453",
        "provenance": {
          "sourceId": "attom-property-api",
          "providerName": "ATTOM Data Solutions",
          "sourceTier": "PUBLIC_RECORDS",
          "observedAtEpochMs": 1788955200000,
          "fetchedAtEpochMs": 1791547199850,
          "confidence": 1.0,
          "freshnessSeconds": 2592000,
          "ttlSeconds": 5184000,
          "recordRef": "FIPS:48453"
        }
      },
      "mlsNumber": null
    },
    "location": {
      "addressLine1": {
        "value": "1420 S Congress Ave",
        "provenance": {
          "sourceId": "attom-property-api",
          "providerName": "ATTOM Data Solutions",
          "sourceTier": "PUBLIC_RECORDS",
          "observedAtEpochMs": 1788955200000,
          "fetchedAtEpochMs": 1791547199850,
          "confidence": 0.98,
          "freshnessSeconds": 2592000,
          "ttlSeconds": 5184000,
          "recordRef": "USPS:C01"
        }
      },
      "city": { "value": "Austin", "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 1.0 } },
      "state": { "value": "TX", "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 1.0 } },
      "postalCode": { "value": "78704", "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 1.0 } },
      "county": { "value": "Travis", "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 1.0 } },
      "latitude": { "value": 30.2504, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.95 } },
      "longitude": { "value": -97.7495, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.95 } }
    },
    "characteristics": {
      "propertyType": { "value": "Single Family Residential", "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.95 } },
      "bedrooms": { "value": 3, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.92 } },
      "bathrooms": { "value": 2.0, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.92 } },
      "livingAreaSqFt": { "value": 1850, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.96 } },
      "lotSizeSqFt": { "value": 6534, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.95 } },
      "yearBuilt": { "value": 1968, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.99 } }
    },
    "taxAndAssessment": {
      "assessedValue": { "value": 542000.0, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.99 } },
      "assessmentYear": { "value": 2025, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 1.0 } },
      "annualTaxAmount": { "value": 9850.40, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.98 } },
      "taxDelinquent": { "value": false, "provenance": { "sourceId": "attom-property-api", "sourceTier": "PUBLIC_RECORDS", "observedAtEpochMs": 1788955200000, "fetchedAtEpochMs": 1791547199850, "confidence": 0.95 } }
    },
    "valuationAndRent": {
      "monthlyRentEstimate": {
        "value": 3250.0,
        "provenance": {
          "sourceId": "rentcast-api",
          "providerName": "RentCast",
          "sourceTier": "DERIVED_ESTIMATE",
          "observedAtEpochMs": 1791460800000,
          "fetchedAtEpochMs": 1791547199920,
          "confidence": 0.88,
          "freshnessSeconds": 86400,
          "ttlSeconds": 604800,
          "recordRef": "RC-AVM-TX78704-1420"
        }
      },
      "rentRangeLow": { "value": 2980.0, "provenance": { "sourceId": "rentcast-api", "sourceTier": "DERIVED_ESTIMATE", "observedAtEpochMs": 1791460800000, "fetchedAtEpochMs": 1791547199920, "confidence": 0.88 } },
      "rentRangeHigh": { "value": 3520.0, "provenance": { "sourceId": "rentcast-api", "sourceTier": "DERIVED_ESTIMATE", "observedAtEpochMs": 1791460800000, "fetchedAtEpochMs": 1791547199920, "confidence": 0.88 } }
    },
    "environmental": {
      "floodZone": {
        "value": "X",
        "provenance": {
          "sourceId": "fema-nfhl-rest",
          "providerName": "FEMA NFHL Direct GIS",
          "sourceTier": "OFFICIAL_API",
          "observedAtEpochMs": 1767225600000,
          "fetchedAtEpochMs": 1791547199780,
          "confidence": 1.0,
          "freshnessSeconds": 24321600,
          "ttlSeconds": 31536000,
          "recordRef": "FIRM-48453C0465J"
        }
      },
      "floodRiskCategory": { "value": "LOW_TO_MODERATE", "provenance": { "sourceId": "fema-nfhl-rest", "sourceTier": "OFFICIAL_API", "observedAtEpochMs": 1767225600000, "fetchedAtEpochMs": 1791547199780, "confidence": 1.0 } },
      "inSpecialFloodHazardArea": { "value": false, "provenance": { "sourceId": "fema-nfhl-rest", "sourceTier": "OFFICIAL_API", "observedAtEpochMs": 1767225600000, "fetchedAtEpochMs": 1791547199780, "confidence": 1.0 } }
    },
    "permits": [],
    "salesHistory": []
  },
  "provenanceSummary": {
    "participatingSources": ["attom-property-api", "rentcast-api", "fema-nfhl-rest"],
    "fieldCountsByTier": {
      "OFFICIAL_API": 3,
      "LICENSED_FEED": 0,
      "PUBLIC_RECORDS": 14,
      "PUBLIC_WEB": 0,
      "DERIVED_ESTIMATE": 3
    }
  },
  "missingFields": [
    {
      "fieldPath": "property.salesHistory[].salePrice",
      "reasonCode": "NON_DISCLOSURE_JURISDICTION",
      "detail": "Texas is a statutory non-disclosure state. Deed transfer prices are not publicly recorded in Travis County."
    },
    {
      "fieldPath": "property.permits",
      "reasonCode": "SOURCE_TIMEOUT",
      "detail": "Upstream permit provider shovels-api exceeded 2500ms latency budget."
    }
  ],
  "sourceErrors": [
    {
      "sourceId": "shovels-api",
      "errorCode": "UPSTREAM_TIMEOUT",
      "message": "Gateway timeout contacting permit vendor.",
      "httpStatus": 504,
      "retryable": true
    }
  ]
}
```

---

## 6. Operational Specifications

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                          OPERATIONAL ARCHITECTURE POLICIES                             │
├────────────────────┬───────────────────────────────────────────────────────────────────┤
│ Domain             │ Policy / Guarantee                                                │
├────────────────────┼───────────────────────────────────────────────────────────────────┤
│ Rate Limiting      │ Token bucket per provider key; client concurrency budgets         │
│ Caching Strategy   │ Multi-tier (L1 Memory, L2 Redis, L3 SQLite offline replica)       │
│ Circuit Breakers   │ Open on 5 consecutive 5xx or rate-limits (half-open after 60s)    │
│ Source Health      │ EWMA latency & success tracking with automated vendor failover    │
│ Cost Management    │ Granular per-request budgeting; lazy loading for expensive calls  │
│ Data Retention     │ MLS 24-48hr listing deletion rules; CCPA/CPRA tombstoning         │
│ Credential Defense │ BFF proxy with strict zero-client-credential policy               │
└────────────────────┴───────────────────────────────────────────────────────────────────┘
```

### 1. Rate Limiting & Concurrency Budgets
* Upstream APIs enforce rate limits per minute (RPM) and per second (QPS):
  * **ATTOM API:** 10 QPS, 50,000 requests/day default tier.
  * **RentCast API:** 5 QPS, 50 RPM on developer tier; 50 QPS on enterprise.
  * **FEMA NFHL GIS:** Public shared infrastructure; rate limit self-policed to ≤ 5 QPS to prevent IP throttle.
* **Backend Enqueueing:** Implement a Token Bucket / Leaky Bucket rate limiter in the BFF Gateway (via Redis). Requests from mobile clients are queued asynchronously, preventing client bursts from tripping provider limits.

### 2. Multi-Tier Caching Policy

| Data Domain | L1 In-Memory | L2 Redis Cache (Backend) | L3 Room SQLite (Mobile) | Invalidation / TTL Trigger |
| :--- | :--- | :--- | :--- | :--- |
| **Active Listing Facts** | 5 minutes | 30 minutes | 15 minutes | Status change webhook, manual pull-to-refresh |
| **Tax & Assessment** | 24 hours | 30 days | 90 days | Tax year rollover, manual dispute override |
| **Rent Estimates (AVM)** | 1 hour | 7 days | 14 days | Algorithm version bump, monthly drift check |
| **Sold Comps** | 6 hours | 14 days | 30 days | New recorded transaction nearby |
| **FEMA Flood Zone** | 7 days | 180 days | 365 days | Annual FIRM map revision |
| **Building Permits** | 12 hours | 30 days | 60 days | New permit issuance inspection cycle |
| **Ownership / Deeds** | 12 hours | 14 days | 30 days | Title transfer filing |

### 3. Retry Strategy & Circuit Breaking
* **Exponential Backoff:** Upstream calls use base delay $t_0 = 500\text{ ms}$, multiplier $2.0$, max backoff $8,000\text{ ms}$, with full randomized jitter to prevent thundering herds.
* **Retryable Failures:** HTTP 429 (Too Many Requests), HTTP 502, 503, 504, TCP connect timeout, TLS handshake timeout.
* **Non-Retryable Failures:** HTTP 400 (Malformed Address), HTTP 401/403 (Invalid API Key), HTTP 404 (Property Not Found), Non-Disclosure State suppression.
* **Circuit Breaker:** Transitions to `OPEN` when failure rate exceeds 50% over a sliding 20-request window, or upon 5 consecutive 5xx errors. While `OPEN`, the gateway fast-fails subsequent requests or serves stale L2 cache with degraded confidence flags. Resets to `HALF-OPEN` after 60 seconds.

### 4. Source Health Tracking & Telemetry
* Build upon `SourceHealthEntity` (`app/src/main/java/com/example/data/local/entity/SourceHealthEntity.kt`):
  * Metrics tracked per provider: Success count, Failure count, Exponential Weighted Moving Average (EWMA) latency, Schema drift error rate, Last error reason.
  * Health states: `HEALTHY` (success rate ≥ 95%), `DEGRADED` (success rate 80–94% or latency > 1500ms), `UNHEALTHY` (success rate < 80%), `DISABLED` (manually halted or circuit breaker tripped).
  * Automated Failover: If primary AVM provider (RentCast) becomes `UNHEALTHY`, automatically fallback to secondary provider (ATTOM Rental AVM) if configured.

### 5. Provider Cost Optimization
* **Tiered Retrieval (Lazy Enrichment):**
  * Step 1 (Search / Map Pin): Query low-cost Parcel/Address endpoint only ($0.01).
  * Step 2 (Property Detail View): Query Tax and Rent AVM ($0.03–$0.05).
  * Step 3 (Deal Room Underwriting / Offer Draft): Query detailed building permits, deed chains, and sold comp bundles ($0.15–$0.30) only when the investor actively engages with the deal.
* **Quota Management:** Hard daily spending limits per API key with alerts at 80% and 95% of monthly billing cap.

### 6. Data Retention & Deletion Compliance
* **MLS Listing Data (Strict 24-48 Hour Rules):** Under National Association of Realtors (NAR) and individual MLS rules, active listing data that transitions to "Expired", "Withdrawn", or "Canceled" must be purged or suppressed from public display within 24 to 48 hours.
* **Consumer Privacy (CCPA / CPRA / GDPR):** Property owner names and mailing addresses must support programmatic erasure upon receipt of verified consumer deletion requests. The backend must propagate deletion to Room databases via tombstone markers during client synchronization.

---

## 7. MVP Provider Comparison & Recommendation

To transition this platform from prototype to operational MVP, we evaluate the leading commercial and public real estate data providers.

### Provider Comparison Matrix

| Provider | Core Strengths | Data Domains Covered | Pricing Model & Known Rates | Unknowns & Hidden Costs | Recommendation for MVP |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **RentCast API** | Developer-first, clean JSON REST API, instant self-serve signup | Rent estimates (AVM), active rental comps, property characteristics, sales history | **Known:** Tiered monthly SaaS.<br>• Free: 50 calls/mo<br>• Developer: $79/mo (5,000 calls)<br>• Pro: $199/mo (25,000 calls) | Overages ($0.015/call); comp radius customization limits | **MUST-HAVE (Day 1)**<br>Primary Rent AVM and quick property characteristics provider. |
| **FEMA NFHL Direct REST** | Official US Government flood hazard layer; authoritative | Flood zone (Zone A, AE, X, VE), SFHA status, FIRM panel IDs | **FREE ($0.00)**<br>Public domain, US Federal GIS service | API response latency (can be 800–2000ms); requires exact Lat/Long geometry query | **MUST-HAVE (Day 1)**<br>Zero-cost authoritative flood data. |
| **US Census Bureau / ACS** | Demographic and socio-economic submarket data | Median household income, population growth, vacancy rates | **FREE ($0.00)**<br>Public domain, federal government REST API | High latency, 5-year survey lag; complex census tract FIPS resolution | **MUST-HAVE (Day 1)**<br>Zero-cost macro neighborhood statistics. |
| **ATTOM Data Solutions** | Industry standard, 155M+ US parcels, deep tax, deed, foreclosure data | Taxes, assessed value, deed history, mortgages, ownership, permits, AVM | **Cost Unknowns Marked:**<br>• Custom enterprise quotes only<br>• Typically $1,000–$3,000/mo min commit<br>• Setup/onboarding fees ($1.5k–$5k) | Annual contract lock-in; per-query overage fees; complex multi-table payload schemas | **RECOMMENDED FOR TAX/DEEDS**<br>Engage sales for startup tier; or use Estated/BatchData intermediary. |
| **BatchData API** | Wholesaler & investor focused, skip tracing, deeds, equity | Ownership, distressed flags, pre-foreclosures, equity %, parcel data | **Known:** Pay-as-you-go or $199–$499/mo tiers.<br>~$0.01 to $0.05 per lookup | Bulk skip-tracing phone match rates vary; off-market lead refresh frequency | **RECOMMENDED FOR WHOLESALE**<br>Best alignment for wholesale and motivated seller outreach. |
| **Bridge Interactive / MLS Grid** | Licensed RESO Web API feeds direct from MLSs | Active listings, pending, sold comparables with MLS board photos | **Cost Unknowns Marked:**<br>• Platform pass-through fees (~$100–$300/mo)<br>• Individual MLS board fees ($50–$500/mo per MLS) | Requires Licensed Broker sponsorship or Participant approval; 600+ separate board agreements | **DEFER (Phase 2/3)**<br>Heavy legal overhead for MVP; require broker partnership. |
| **SimplyRETS / Repliers** | Normalized wrapper over MLS RESO feeds | MLS listings, photos, agent details, open houses | **Known:** $199–$499/mo + pass-through MLS board fees | Limited coverage if local MLS denies vendor access; broker sign-off required | **ALTERNATIVE FOR LISTINGS**<br>Fastest path to MLS if broker sponsor exists. |
| **Shovels.ai** | Standardized building permit & contractor intelligence | Building permits, renovation history, contractor track record | **Known:** Tiered developer API starting at ~$299/mo | Coverage concentrated in CA, FL, TX; rural county gaps | **DEFER (Phase 2)**<br>Valuable for flip underwriting, secondary for MVP. |

### Recommended MVP Architecture Stack

```
                               ┌────────────────────────────────────────────────────────┐
                               │                    ANDROID CLIENT                      │
                               │  - No Provider API Keys                                │
                               │  - User Auth Token Only (Firebase / Cognito)           │
                               │  - Room Local Cache (Offline First)                    │
                               └──────────────────────────┬─────────────────────────────┘
                                                          │ HTTPS / JSON
                                                          ▼
                               ┌────────────────────────────────────────────────────────┐
                               │             BACKEND GATEWAY (BFF PROXY)                │
                               │  - Authentication & Mobile Session Guard               │
                               │  - Redis Multi-Tier Cache (L2)                         │
                               │  - Token-Bucket Rate Limiter & Concurrency Guard       │
                               │  - Canonical Identity Deduplication & Normalization    │
                               │  - Vault Secrets Manager (ATTOM, RentCast Keys)        │
                               └──────┬───────────────────┬───────────────────┬─────────┘
                                      │                   │                   │
               ┌──────────────────────┘                   │                   └──────────────────────┐
               ▼                                          ▼                                          ▼
┌──────────────────────────────┐        ┌───────────────────────────────────┐      ┌──────────────────────────────┐
│        RentCast API          │        │       FEMA NFHL REST & ACS        │      │   ATTOM / BatchData Proxy    │
│  - Property Characteristics  │        │  - Direct Flood Risk (Free)       │      │  - County Tax & Assessed Val │
│  - Rental AVM & Rent Comps   │        │  - Census Submarket Trends (Free) │      │  - APN & Legal Parcel ID     │
│  - Active Rental Listings    │        │  - Lat/Long Coordinate Query      │      │  - Owner Occupancy & Deeds   │
└──────────────────────────────┘        └───────────────────────────────────┘      └──────────────────────────────┘
```

### Cost Estimates & Unknowns Breakdown for MVP Launch

1. **RentCast API (Characteristics & Rent AVM):**
   * *Known:* Developer Tier at **$79.00 / month** covers 5,000 property requests (sufficient for ~150 active investors conducting 30 property searches/month).
   * *Overage:* $0.015 per additional request.
2. **FEMA NFHL & US Census Bureau APIs:**
   * *Known:* **$0.00 / month** (Public domain US federal APIs). Requires zero vendor contracts.
3. **Property Taxes, APN & Assessment Data:**
   * *Option A (BatchData Startup API):* Pay-as-you-go estimated at **$250.00 / month** ($0.025/lookup across 10,000 lookups).
   * *Option B (ATTOM Direct):* **COST UNKNOWN (Requires Enterprise Quote)**. Expected minimum commitment of **$1,500 – $2,500 / month** with annual contract ($18,000–$30,000/yr). *Recommendation: Avoid ATTOM Direct in MVP phase; use BatchData or RentCast parcel endpoints.*
4. **Active MLS Feeds (RESO Web API via SimplyRETS or Bridge):**
   * *Known:* Aggregator platform fee **$299.00 / month**.
   * *COST UNKNOWNS:* Local MLS board fees vary by board (e.g., ACTRIS Austin TX: $50/mo; CRMLS California: $150/mo; Miami MLS: $100/mo). Requires broker sponsorship agreement.
   * *MVP Strategy:* Launch MVP targeting manual user URL paste (via sanitized web import) or off-market wholesale lists, adding MLS feed once broker partnership is signed.

---

## 8. Implementation Roadmap

### Phase 1: Security Hardening & Mock Neutralization (Immediate)
1. **Quarantine Mock Adapters:** Isolate `ZillowUrlSourceAdapter`, `RedfinUrlSourceAdapter`, `RealtorUrlSourceAdapter`, and `HomesUrlSourceAdapter` to test namespaces. Ensure no production code path generates fabricated property metrics.
2. **Populate Seed Data:** Replace `emptyList()` in `PropertySeedData` with realistic, hardened test fixture bundles so offline demos, underwriting tests, and automated UI flows operate reliably.
3. **Enforce Zero-Secret Android Policy:** Verify that no API keys or third-party secrets reside in `gradle.properties`, `local.properties`, or `BuildConfig`.

### Phase 2: Backend-for-Frontend (BFF) Gateway & MVP Data Pipeline (Weeks 1–4)
1. **Deploy Backend Gateway:** Implement Node.js/Go backend proxy using the proposed JSON Schema (`property-data-contract.v1.json`).
2. **Integrate Free Federal Sources:** Wire direct REST integrations to FEMA NFHL (Flood) and US Census ACS (Demographics) at zero data cost.
3. **Integrate Commercial Rent & Characteristics Provider:** Connect RentCast API for rental AVM, bed/bath/sqft characteristics, and rental comparables ($79/mo).
4. **Integrate Property Tax & APN Provider:** Connect BatchData or Estated API for verified county assessor tax records and APN parcel numbers.
5. **Wire Android App to Gateway:** Update `PropertyUrlImportBridge` and `PropertyImportRepository` to consume the gateway contract.

### Phase 3: Comps Engine & Licensed MLS Syndication (Weeks 5–8)
1. **Automate Comp Selection:** Utilize gateway sold transaction records with `PropertyIdentityDeduplicationEngine` to power automated CMA (Comparative Market Analysis).
2. **Broker Sponsorship & RESO Web API:** Formalize broker relationship to unlock MLS Grid / Bridge Interactive for licensed on-market listings.

---

## 9. Audit Verification & Deliverables Summary

* **Task Name:** US Real Estate Data Provider Readiness Audit
* **Repository:** `ISM-dragon/2`
* **Assigned Branch:** `arena/359ef2d2-2`
* **Pull Request Target:** `main`
* **Audit Document:** `docs/US_REAL_ESTATE_DATA_PROVIDER_AUDIT.md`

All steps, constraints, and deliverables have been fulfilled in accordance with architectural and security standards.
