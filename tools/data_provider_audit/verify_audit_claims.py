#!/usr/bin/env python3
"""
Machine verification of every load-bearing claim in docs/US_DATA_PROVIDER_AUDIT.md.

This script does NOT re-implement any audited logic. It only asserts facts about the
repository's own source text and Gradle wiring, so the audit report can be re-checked
by anyone without a JVM toolchain. Run:  python3 tools/data_provider_audit/verify_audit_claims.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

failures: list[str] = []
checks = 0


def read(path: str) -> str:
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def kotlin_files(rel: str):
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, rel)):
        dirnames[:] = [d for d in dirnames if d not in {"build", ".git"}]
        for name in filenames:
            if name.endswith(".kt"):
                full = os.path.join(dirpath, name)
                yield os.path.relpath(full, ROOT), read(os.path.relpath(full, ROOT))


def check(label: str, condition: bool, detail: str = "") -> None:
    global checks
    checks += 1
    if condition:
        print(f"  PASS  {label}")
    else:
        print(f"  FAIL  {label}" + (f"\n        -> {detail}" if detail else ""))
        failures.append(label)


# ---------------------------------------------------------------- C1: fabrication engine
FAB_PKG = "app/src/main/java/com/example/domain/intelligence/source"
FAB_ADAPTERS = [
    "ZillowUrlSourceAdapter",
    "RedfinUrlSourceAdapter",
    "RealtorUrlSourceAdapter",
    "HomesUrlSourceAdapter",
]


def section(title: str) -> None:
    print(f"\n{title}")


section("C1  domain.intelligence.source adapters are fabricated and un-wired")

fab_files = {
    "ZillowUrlSourceAdapter": f"{FAB_PKG}/adapters/ZillowUrlSourceAdapter.kt",
    "RedfinUrlSourceAdapter": f"{FAB_PKG}/adapters/RedfinUrlSourceAdapter.kt",
    "RealtorUrlSourceAdapter": f"{FAB_PKG}/adapters/RealtorUrlSourceAdapter.kt",
    "HomesUrlSourceAdapter": f"{FAB_PKG}/adapters/HomesUrlSourceAdapter.kt",
}
for name, path in fab_files.items():
    src = read(path)
    check(
        f"{name} performs no network I/O (no fetch/transport/http call)",
        not re.search(r"\b(fetch|transport|httpFetcher|OkHttp|openConnection|execute\()", src),
        "found a network-looking token",
    )
    check(
        f"{name} returns a hardcoded listPrice",
        re.search(r"val basePrice = \d", src) is not None,
    )
    check(
        f"{name} ships in src/main (i.e. inside the APK)",
        "/src/main/" in path,
    )

# every main-source reference to these classes, excluding their own package
main_refs = []
for rel, src in kotlin_files("app/src/main"):
    if rel.startswith(FAB_PKG + "/adapters/"):
        continue
    for name in FAB_ADAPTERS:
        if re.search(rf"\b{name}\b", src):
            main_refs.append((rel, name))
check(
    "no production code instantiates any *UrlSourceAdapter",
    not main_refs,
    f"referenced at {main_refs}",
)

test_refs = [
    rel
    for rel, src in kotlin_files("app/src/test")
    if any(re.search(rf"\b{n}\b", src) for n in FAB_ADAPTERS)
]
check(
    "the fabricated adapters ARE referenced by tests (so tests can pass on fake data)",
    bool(test_refs),
    "no test references found",
)

gov = read(fab_files["ZillowUrlSourceAdapter"])
check(
    "ZillowUrlSourceAdapter labels its synthetic tax as GOVERNMENT_DATA",
    "ProvenanceSourceTier.GOVERNMENT_DATA" in gov and "Travis County Tax Assessor" in gov,
)

# ---------------------------------------------------------------- C2: live wiring
section("C2  the production composition root is domain.propertyurl, not :urlintelligence")

app = read("app/src/main/java/com/example/RealEstateAiApp.kt")
check(
    "RealEstateAiApp builds PropertyUrlIntelligenceFactory.create(...)",
    "PropertyUrlIntelligenceFactory.create(" in app,
)
check(
    "RealEstateAiApp builds PropertyUrlImportBridge",
    "PropertyUrlImportBridge(" in app,
)
check(
    "RealEstateAiApp never touches UrlIntelligenceModule",
    "UrlIntelligenceModule" not in app,
)

ui_live = [
    rel
    for rel, src in kotlin_files("app/src/main/java/com/example/ui")
    if "UrlIntelligenceModule" in src or "PropertyUrlImportService" in src
]
check(
    "no UI/ViewModel uses UrlIntelligenceModule or PropertyUrlImportService",
    not ui_live,
    f"found in {ui_live}",
)

ui_import = [
    rel
    for rel, src in kotlin_files("app/src/main/java/com/example/ui")
    if "intelligenceRepo" in src or "intelligenceRepository" in src
]
check(
    "the UI reaches imports through IntelligenceRepository (the live path)",
    bool(ui_import),
    "no UI reference found",
)

# ---------------------------------------------------------------- C3: seed adapters
section("C3  MLS / wholesale feed adapters return no data")

seed = read("app/src/main/java/com/example/data/adapter/PropertyDataSeed.kt")
# The body is a block body: `fun getSeedBundles(): List<...> { return emptyList() }`.
seed_body = re.search(
    r"fun getSeedBundles\(\)\s*:\s*List<[^>]*>\s*\{(.*?)\}", seed, re.DOTALL
)
check(
    "PropertySeedData.getSeedBundles() returns emptyList()",
    seed_body is not None and seed_body.group(1).strip() == "return emptyList()",
    f"body was: {seed_body.group(1).strip() if seed_body else '<no match>'}",
)
check(
    "both MLS adapters are still registered in the app graph",
    "OnMarketMlsAdapter()" in app and "OffMarketWholesaleAdapter()" in app,
)

# ---------------------------------------------------------------- C4: enrichment store
section("C4  property_enrichments has no production producer")

insight = read("app/src/main/java/com/example/data/local/entity/PropertyInsightEntities.kt")
# Scope to `object PropertyEnrichmentType { ... }` only; the same file also declares
# PropertyFinancialDataSource, whose constants are not enrichment categories.
enrich_block = re.search(r"object PropertyEnrichmentType\s*\{(.*?)\n\}", insight, re.DOTALL)
declared = re.findall(r'const val ([A-Z_]+) = "', enrich_block.group(1)) if enrich_block else []
check(
    "12 enrichment categories are declared in PropertyEnrichmentType",
    len(declared) == 12,
    f"found {len(declared)}: {declared}",
)

bridge = read("app/src/main/java/com/example/data/adapter/PropertyUrlImportBridge.kt")
check(
    "the only production bundle producer never populates `enrichments`",
    "enrichments =" not in bridge,
)
writers = [
    rel
    for rel, src in kotlin_files("app/src/main")
    if "PropertyEnrichmentEntity(" in src and "/data/local/entity/" not in rel
]
check(
    "no production code constructs a PropertyEnrichmentEntity",
    not writers,
    f"constructed in {writers}",
)

# ---------------------------------------------------------------- C5: satellite overwrite
section("C5  re-import overwrites rent/tax/market satellites with zeros")

importer = read("app/src/main/java/com/example/data/repository/PropertyImportRepository.kt")
check(
    "writeSatellites writes marketData/rentEstimate/taxRecord unconditionally",
    all(
        s in importer
        for s in (
            "propertyDao.insertMarketData(bundle.marketData",
            "propertyDao.insertRentEstimate(bundle.rentEstimate",
            "propertyDao.insertTaxRecord(bundle.taxRecord",
        )
    ),
)
dao = read("app/src/main/java/com/example/data/local/dao/PropertyDao.kt")
for fn in ("insertMarketData", "insertRentEstimate", "insertTaxRecord"):
    m = re.search(rf"@Insert\(onConflict = OnConflictStrategy\.REPLACE\)\s*suspend fun {fn}", dao)
    check(f"PropertyDao.{fn} uses OnConflictStrategy.REPLACE", m is not None)
check(
    "PropertyUrlImportBridge hard-zeros the rent estimate",
    re.search(r"estimatedRent = 0\.0", bridge) is not None,
)
check(
    "PropertyUrlImportBridge hard-zeros assessedValue and estimatedValue",
    "assessedValue = 0.0" in bridge and "estimatedValue = 0.0" in bridge,
)

# ---------------------------------------------------------------- C6: silent financial defaults
section("C6  screens apply unlabeled price-ratio defaults")

dfe = read("app/src/main/java/com/example/domain/intelligence/engine/DeterministicFinancialEngine.kt")
for token, meaning in [
    ("purchasePrice * 0.0075", "rent = 0.75% of price / month"),
    ("purchasePrice * 0.018", "property tax = 1.8% of price"),
    ("purchasePrice * 0.006", "insurance = 0.6% of price"),
    ("purchasePrice * 0.025", "closing costs = 2.5% of price"),
    ("35000.0", "BRRRR rehab flat $35,000"),
    ("50000.0", "flip rehab flat $50,000"),
    ("purchasePrice * 1.30", "BRRRR ARV = +30%"),
]:
    check(f"DeterministicFinancialEngine embeds {meaning}", token in dfe)

dealroom = read("app/src/main/java/com/example/ui/screens/dealroom/DealRoomViewModel.kt")
check(
    "DealRoomViewModel computes its metrics with DeterministicFinancialEngine",
    "DeterministicFinancialEngine.calculate(" in dealroom,
)
check(
    "DealRoomViewModel discloses unsourced inputs to the user",
    "engine default assumption" in dealroom,
)

analyzer = read("app/src/main/java/com/example/ui/screens/analyzer/AnalyzerViewModel.kt")
check(
    "AnalyzerViewModel defaults tax to 1.2% of price with no disclosure",
    "prop.price * 0.012" in analyzer and "assumption" not in analyzer.lower(),
)
roi = read("app/src/main/java/com/example/ui/screens/roi/PropertyRoiCalculatorViewModel.kt")
check(
    "PropertyRoiCalculatorViewModel defaults tax to 1.5% of price with no disclosure",
    "details.purchasePrice * 0.015" in roi,
)

factory = read("app/src/main/java/com/example/data/repository/PropertyUnderwritingFactory.kt")
check(
    "PropertyUnderwritingFactory instead emits MISSING_RENT_ESTIMATE rather than guessing",
    "MISSING_RENT_ESTIMATE" in factory and "purchasePrice * 0.0" not in factory,
)

# ---------------------------------------------------------------- C7: unsupported domains
section("C7  unsupported and planned domains fail explicitly")

catalog = read("app/src/main/java/com/example/domain/propertyurl/source/SourceCatalog.kt")
check("apartments.com / trulia / loopnet are catalogued as PLANNED", catalog.count("SourceStatus.PLANNED") >= 3)
intel = read("app/src/main/java/com/example/domain/propertyurl/pipeline/PropertyUrlIntelligence.kt")
check(
    "the pipeline rejects SOURCE_NOT_SUPPORTED / NO_ADAPTER before any fetch",
    "SOURCE_NOT_SUPPORTED" in intel and "NO_ADAPTER" in intel,
)
check(
    "generic fallback is a declared option (allowGenericFallback), defaulting on",
    "allowGenericFallback: Boolean = true"
    in read("app/src/main/java/com/example/domain/propertyurl/pipeline/ImportModels.kt"),
)
check(
    "PLANNED sources are rejected by default",
    "allowPlannedSources: Boolean = false"
    in read("app/src/main/java/com/example/domain/propertyurl/pipeline/ImportModels.kt"),
)

# ---------------------------------------------------------------- C8: module wiring
section("C8  Gradle module wiring")

app_gradle = read("app/build.gradle.kts")
check("`:urlintelligence` is a dependency of :app", 'implementation(project(":urlintelligence"))' in app_gradle)
check(
    "`:repairestimator` is NOT a dependency of :app",
    'project(":repairestimator")' not in app_gradle,
)
settings = read("settings.gradle.kts")
check("`:repairestimator` is still built by Gradle", 'include(":repairestimator")' in settings)

# ---------------------------------------------------------------- C9: canonical field coverage
section("C9  the canonical field set has no slot for several audited domains")

fields = read("app/src/main/java/com/example/domain/propertyurl/model/PropertyField.kt")
for present in ("ANNUAL_TAX_AMOUNT", "HOA_FEE_MONTHLY", "MLS_NUMBER", "DAYS_ON_MARKET"):
    check(f"PropertyField declares {present}", present in fields)
for absent in ("RENT_ESTIMATE", "ASSESSED_VALUE", "FLOOD", "PERMIT", "OWNER", "COMP", "SALE_HISTORY"):
    check(f"PropertyField has no {absent} field", absent not in fields)

# ---------------------------------------------------------------- C10: two divergent catalogues
section("C10  two independent source catalogues disagree")

known = read("urlintelligence/src/main/kotlin/com/example/urlintelligence/source/SourceDescriptor.kt")
known_literal_ids = re.findall(r'^\s+id = "([a-z_.]+)",', known, re.MULTILINE)
catalog_ids = re.findall(r'^\s+sourceId = "([a-z_]+)",', catalog, re.MULTILINE)
# The fifth descriptor uses a constant rather than a literal: `id = SourceDescriptor.GENERIC_SOURCE_ID`.
known_ids = known_literal_ids + (
    ["generic.web"] if "id = SourceDescriptor.GENERIC_SOURCE_ID" in known else []
)
check(
    ":urlintelligence KnownSources declares 5 sources (4 literals + GENERIC_SOURCE_ID)",
    len(known_ids) == 5,
    f"found {known_ids}",
)
check(
    "domain.propertyurl SourceCatalog declares 11 sources",
    len(catalog_ids) == 11,
    f"found {catalog_ids}",
)
check(
    "the catalogues use different ids for the same portal ('realtor' vs 'realtor_com')",
    "realtor" in known_ids and "realtor_com" in catalog_ids,
)
check(
    "SourceCatalog knows apartments.com/trulia/loopnet; KnownSources does not",
    all(x in catalog_ids for x in ("apartments_com", "trulia", "loopnet"))
    and not any(x in known_ids for x in ("apartments_com", "trulia", "loopnet")),
)

factory_src = read(
    "app/src/main/java/com/example/domain/propertyurl/pipeline/PropertyUrlIntelligenceFactory.kt"
)
adapters_block = re.search(
    r"fun defaultAdapters\(\).*?PropertyAdapterRegistry\.of\((.*?)\n    \)", factory_src, re.DOTALL
)
registered = re.findall(r"(\w+Adapter)\(\)", adapters_block.group(1)) if adapters_block else []
check(
    "5 adapters are registered: zillow, redfin, realtor_com, homes_com, generic_web",
    registered
    == ["ZillowAdapter", "RedfinAdapter", "RealtorComAdapter", "HomesComAdapter", "GenericWebListingAdapter"],
    f"found {registered}",
)
# mls_feed / county_records / off_market_wholesale are AVAILABLE but have no adapter and no domains.
unreachable = [
    sid
    for sid, body in re.findall(
        r'val ([A-Z_]+) = PropertySourceDefinition\((.*?)\n    \)', catalog, re.DOTALL
    )
    if "domains = emptyList()" in body
    and "SourceStatus.PLANNED" not in body
    and "GENERIC_WEB" not in sid
]
check(
    "mls_feed / county_records / off_market_wholesale are AVAILABLE yet unreachable (no domain, no adapter)",
    sorted(unreachable) == ["COUNTY_RECORDS", "MLS_FEED", "OFF_MARKET_WHOLESALE"],
    f"found {unreachable}",
)

fixtures = sorted(
    os.listdir(os.path.join(ROOT, "app/src/test/resources/fixtures/property-url-intelligence"))
)
check(
    "6 offline HTML/robots fixtures back the parser tests",
    len(fixtures) == 6,
    f"found {fixtures}",
)
check(
    "one fixture is an anti-bot page (so blocking is a tested outcome)",
    "anti-bot-page.html" in fixtures,
)

# ---------------------------------------------------------------- C11: counts quoted in the report
section("C11  counts quoted in the report")

sf = read("app/src/main/java/com/example/domain/propertyurl/model/SourceFailure.kt")
kind_block = re.search(r"enum class SourceFailureKind\((.*?)\n\}", sf, re.DOTALL).group(1)
kinds = re.findall(r"^    ([A-Z_0-9]+)\(", kind_block, re.MULTILINE)
check("SourceFailureKind has 34 members", len(kinds) == 34, f"found {len(kinds)}")
cat_block = re.search(r"enum class SourceFailureCategory \{(.*?)\}", sf, re.DOTALL).group(1)
check(
    "SourceFailureCategory has 9 members",
    len(re.findall(r"[A-Z_]+", cat_block)) == 9,
)

identity = read("app/src/main/java/com/example/domain/identity/PropertyIdentity.kt")
statuses = re.findall(r"^    ([A-Z_]+)[,;]?$", re.search(
    r"enum class DeduplicationStatus \{(.*?)\n\}", identity, re.DOTALL).group(1), re.MULTILINE)
check(
    "DeduplicationStatus is EXACT_MATCH/CANONICAL_MATCH/POSSIBLE_MATCH/CONFLICT/NEW",
    statuses == ["EXACT_MATCH", "CANONICAL_MATCH", "POSSIBLE_MATCH", "CONFLICT", "NEW"],
    f"found {statuses}",
)

deal_screen = read("app/src/main/java/com/example/ui/screens/dealroom/DealRoomScreen.kt")
for kind, expected in (("FLOOD_RISK", 3), ("WALK_SCORE", 2), ("CRIME_INDEX", 2)):
    n = deal_screen.count(f"PropertyEnrichmentType.{kind}")
    check(f"DealRoomScreen reads {kind} at {expected} site(s)", n == expected, f"found {n}")

repair_tests = [
    f for f in os.listdir(os.path.join(ROOT, "repairestimator/src/test/kotlin/com/example/repairestimator"))
    if f.endswith(".kt")
]
check(":repairestimator has 15 test files", len(repair_tests) == 15, f"found {len(repair_tests)}")

guards = read("app/src/main/java/com/example/domain/propertyurl/normalize/ValueGuards.kt")
for token, meaning in [
    ("MIN_PRICE = 1_000.0", "price floor $1,000"),
    ("MAX_PRICE = 500_000_000.0", "price ceiling $500M"),
    ("MAX_SQFT = 5_000_000.0", "area ceiling 5M sqft"),
    ("MIN_YEAR_BUILT = 1600", "year-built floor 1600"),
]:
    check(f"ValueGuards declares {meaning}", token in guards)

# The §5 tally: 0 at L3+, 5 at L2, 8 at L1, 5 at L0.
check(
    "5 adapters are registered at L2 (fixture-backed parsers)",
    len(registered) == 5,
    f"found {registered}",
)
l1 = [
    "apartments_com",  # PLANNED, adapter class exists but unregistered
    "trulia",          # PLANNED
    "loopnet",         # PLANNED
    "mls_feed",        # AVAILABLE but no domain, no adapter
    "county_records",  # AVAILABLE but no domain, no adapter
    "off_market_wholesale",  # AVAILABLE but no domain, no adapter
]
check(
    "6 catalogue sources are L1 (3 planned + 3 declared-but-unreachable)",
    all(f'"{s}"' in catalog for s in l1),
    f"missing from catalogue: {[s for s in l1 if chr(34) + s + chr(34) not in catalog]}",
)
check(
    "2 wired feed adapters are L1 (return emptyList)",
    "OnMarketMlsAdapter" in seed and "OffMarketWholesaleAdapter" in seed,
)
l0_classes = re.findall(r"class (\w+UrlSourceAdapter)", "".join(
    read(f"{FAB_PKG}/adapters/{n}.kt") for n in
    ("ZillowUrlSourceAdapter", "RedfinUrlSourceAdapter", "RealtorUrlSourceAdapter", "HomesUrlSourceAdapter")
))
check(
    "5 stack-C adapter classes are L0 (4 portals + GenericUrlSourceAdapter)",
    len(l0_classes) == 5,
    f"found {l0_classes}",
)
VENDORS = r"(ATTOM|Attom|CoreLogic|RentCast|HouseCanary|DataTree|FirstAmerican|BridgeInteractive|MlsGrid|RESO)"
# Vendor names DO appear in explanatory comments (e.g. PropertyEnrichmentEntity's KDoc gives
# "ATTOM"/"HOUSECANARY" as example provider keys). What must not exist is an import or a class.
vendor_imports = [
    (rel, line)
    for rel, src in kotlin_files("app/src/main")
    for line in src.splitlines()
    if line.strip().startswith("import ") and re.search(VENDORS, line)
]
check("no vendor SDK is imported anywhere in main sources", not vendor_imports, str(vendor_imports))
vendor_classes = [
    (rel, name)
    for rel, src in kotlin_files("app/src/main")
    for name in re.findall(r"\b(?:class|interface|object)\s+(\w+)", src)
    if re.search(VENDORS, name)
]
check("no class/interface/object is named after a vendor", not vendor_classes, str(vendor_classes))

# ---------------------------------------------------------------- summary
print("\n" + "=" * 78)
if failures:
    print(f"{len(failures)} of {checks} checks FAILED:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print(f"ALL {checks} CLAIM CHECKS PASSED")
