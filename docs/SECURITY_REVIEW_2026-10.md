# Security and release-readiness review (2026-10)

Branch: `arena/8dcbec6f-2` (base `43ac4cd`, `main`).
Companion to `SECURITY_AUDIT.md`. This document re-validates the earlier claims against the code, records
verified fixes with their regression tests, and lists what is still open. It does not replace a penetration
test or a signed-off release build.

---

## 1. Verification status (read this first)

| Area | Status in this review |
|---|---|
| `:urlintelligence` module (pure JVM, no Android) | **Executed.** 235 JUnit tests compiled with Kotlin 2.2.10 and run on a JVM. 234 pass. 1 known failure, documented in F-19. See section 4. |
| Pure-Kotlin app units (`ValueGuards`, `OfferRecipientPolicy`, `BackupRetention`, restore-path image guard) | **Executed.** 13 of 13 pass. |
| App Android/Robolectric unit tests, instrumented tests, Gradle build, `assembleRelease`, R8, lint | **Not executed.** The sandbox cannot reach Maven Central, Google Maven, or the Gradle distribution. These must run in CI before merge. |
| `ImageFetchPolicy`, `RealEstateAiApp` (Coil wiring), `BackupRestoreManager` and `OfferRepository` edits | **Syntax-checked and API-checked only.** They depend on OkHttp, Coil and Android classes that could not be compiled here. The OkHttp 4.10 and Coil 2.7.0 signatures were checked against their source trees. |
| `app/build.gradle.kts` dependency edits | **Not built.** Only comments were added; one dependency was removed from each of the three locations and its absence from source was confirmed by search. |

The Gradle/JUnit/Android toolchain was not available. Runs used a Temurin JRE, the Kotlin 2.2.10 compiler, and a
small JUnit-compatible shim. The shim exists only in the sandbox and is not part of the repository.

---

## 2. Threat model

### 2.1 Deployment models

**A. Single-device personal app (the shipped shape today).** Assets: the Gmail refresh/access tokens, the user's
Gemini API key, offers and their PDFs, email send history, property and financial data, backup exports.
Trust is local. The only remote parties are Google (Gemini, Gmail, OAuth) and the listing sites the user chooses to import.

**B. Multi-user or cloud-hosted service (not present).** This would require server-side token and key custody,
tenant isolation, per-user rate limits, and a backend in front of Gemini and Gmail. None of that exists in the repository,
so the current architecture **must not be distributed as a multi-tenant service** without backend mediation (see F-11).

### 2.2 Adversaries considered

| # | Adversary | Capability | Main controls |
|---|---|---|---|
| T1 | Author of a listing page or URL the user imports | Controls HTML/JSON, image URLs, redirects, DNS answers of the hosts it controls | URL validator, response guard, public-only DNS, robots gate, image fetch policy (F-02) |
| T2 | Author of a backup file the user imports or pastes | Controls every field of the JSON restore format | Property-ID and PDF-path validation, and now image-URL screening on restore (F-01) |
| T3 | Another app on the device | Can send intents and read exported content | No exported components besides the launcher activity; FileProvider limited to `files/offers/`; `allowBackup=false`; backup and extraction rules exclude all app data |
| T4 | Network attacker (on-path) | Can observe and modify traffic | TLS only (`usesCleartextTraffic=false`); Gemini and Gmail clients with redirects, cookies and proxies disabled |
| T5 | Device owner or rooted attacker | Full filesystem access | Keystore-backed AES-GCM for Gmail tokens and API keys (`CryptoManager`); Room data is not encrypted (accepted, see section 6) |
| T6 | Malicious or buggy code in a dependency | Runs inside the app | Dependency pruning (F-09); R8 recommended (F-10) |
| T7 | Attacker who obtains the release APK | Static analysis of the shipped bytes | No secrets compiled in (section 9); R8 would add obfuscation but is not a control on its own |

### 2.3 Trust boundaries

1. Network → URL validator → fetch policy → response guard → parser → normalizer → Room. Untrusted at the network edge.
2. Restored backup file → `BackupRestoreManager` → Room and filesystem. Untrusted input, validated on the way in.
3. Room → Coil image loader → network. Stored image URLs are untrusted because they may predate the ingestion guards. The loader enforces the policy again (F-02).
4. App → Gemini and Gmail over HTTPS, with the user's key or OAuth token.
5. App → FileProvider → other apps. Only `files/offers/` is reachable, read-only, via a grant.

---

## 3. Ranked findings

Severity reflects impact if exploited. Exploitability says what an adversary needs. Status is at the end of this review.

| ID | Severity | Exploitability | Status | Finding |
|---|---|---|---|---|
| F-03 | **High** | No attacker needed. Triggered by the user tapping Send, or by automation with `autoSendOffers` on. | Fixed (fail-closed). UX gap remains, see RB-1 | Offers were addressed to the hard-coded placeholder `agent@realestateteam.com`. The manual and automated send paths both reached it. Offer terms (price, earnest money, dates) would reach an address the user never chose. |
| F-01 | Medium (High if backup files are shared) | Requires the user to import a crafted backup. | Fixed | Backup restore wrote `primaryImageUrl` without the ingestion guard. Coil then fetched private, metadata or credentialed hosts. This bypassed SEC-08. |
| F-02 | Medium | Requires the user to open a crafted listing. | Fixed | Coil used its default OkHttp client. It followed redirects and resolved any hostname, so a public image URL could bounce the request to a LAN or metadata address. Pre-existing stored URLs were also not re-screened. |
| F-04 | Medium in the module, Low in the app | Module only (not reachable in the shipped app). | Fixed in the module | `urlintelligence` persisted the raw user URL, including userinfo and token-like query values, to its job store before validation. The app's own path already redacts. |
| F-05 | Medium in the module, Low in the app | Module only. | Fixed in the module | The module resolver did not apply `ResponseGuard` to third-party adapters. Size, content-type, redirect and soft-block checks were skipped. |
| F-06 | Low | Any listing that serves a challenge page. | Fixed in the module | Cloudflare-style challenge pages served with HTTP 200 were parsed as ordinary documents, not treated as policy stops. This conflicts with the module's stated rule of never proceeding past CAPTCHA. |
| F-07 | Low | Local only. | Fixed | Plaintext backup exports (offers, email history) accumulated without limit in app-private storage. |
| F-08 | Low (hygiene) | Accidental commit. | Fixed | `my-upload-key.jks` (the release keystore default path) and other keystore types were not git-ignored. |
| F-09 | Low | Attack surface and supply chain. | Fixed by removal from the build | Three release dependencies had no references in source. `firebase-appcheck-recaptcha` was a leftover App Check provider with App Check never initialised. `firebase-ai` pulled in an unused SDK and network stack. `logging-interceptor` was a one-line path to logging tokens. |
| F-10 | Medium (hardening) | Not exploitable by itself. | Open, recommendation in section 8 | `isMinifyEnabled = false` on release. The APK is readable and unobfuscated. |
| F-11 | Medium (architecture) | Requires a device-level attacker or a multi-user deployment. | Open. Blocker for any non-personal distribution | The Gemini key is user-entered and held on device (Keystore-encrypted). Calls go directly from the client. `metadata.json` says `SERVER_SIDE_GEMINI_API`, which is inaccurate. |
| F-12 | Functional blocker | n/a | Open | No in-app OAuth sign-in. `SettingsViewModel.applyVerifiedOAuthTokens` and `updateGmailConfig` have no callers. Gmail cannot be connected from the UI. |
| F-13 | Medium (process) | n/a | Partly fixed | CI runs only filtered app test sets (AI-analyst and seller-outreach jobs). It does not run the full app suite, `:urlintelligence:test`, or a release build. At base, 13 of the module's 228 tests failed unnoticed. |
| F-14 | Low | Requires a compromised or misconfigured Google endpoint. | Open | `response.body?.string()` is unbounded for Gemini and Gmail responses. |
| F-15 | Low | Dormant. Only tests wire `data/urlintelligence/OkHttpPropertyTransport`. | Open | That transport returns the unredacted `finalUrl`. The production path (domain fetcher) redacts it. Re-check before wiring. |
| F-16 | Low | Dead code. | Open | `GmailService.createGmailIntent` has no callers and skips the approved-PDF check. It is confined by FileProvider. |
| F-17 | Low | Requires code change to exploit. | Open (accepted) | `OffersViewModel.openPdf` skips the approved-PDF check. FileProvider canonicalises the path and is limited to `files/offers/`, so traversal out of that directory was not found. |
| F-18 | Info | n/a | Open | `AutomationAuditSecurityTest` contains synthetic `ya29.`/`AIza`-shaped strings. Test-only, but they can trigger secret-scanning push protection. |
| F-19 | Info (functional) | n/a | Open | `PropertyUrlValidatorTest.canonical form is stable for idempotency` expects `www.` and non-`www.` URLs to share an idempotency key. The implementation does not do that. Changing it would alter persisted keys, which needs a migration decision. |

No finding in this review is a direct credential leak from the shipped APK. No hard-coded signing or service credentials were found.

---

## 4. Verified fixes and regression tests

### F-03 Offers never sent to the placeholder recipient
- Code: `domain/gmail/OfferRecipientPolicy.kt` (new). `OfferRepository.validateOfferPreSend` returns a
  blocked result for the placeholder, before any Gmail call. `validateForSend` delegates to it, so automation is covered.
- Test: `app/src/test/.../domain/gmail/OfferRecipientPolicyTest.kt`. **Ran here: 5/5 pass.**
- Existing tests were checked: none send to the placeholder address.

### F-01 Restored backups cannot carry unsafe image URLs
- Code: `ValueGuards.imageUrlOrNull` (new). `BackupRestoreManager` applies it to `primaryImageUrl` on restore.
- Test: `app/src/test/.../domain/propertyurl/ImageUrlRestoreGuardTest.kt`. **Ran here: 3/3 pass.**
  Limitation: this test covers the guard function. The restore wiring needs a Robolectric test, which I have not run.

### F-02 Image loading follows no redirects and resolves only public addresses
- Code: `domain/image/ImageFetchPolicy.kt` (new). It builds an OkHttp client with redirects off, no cookies,
  no proxy, no authenticator, `PublicOnlyDns`, and an interceptor that refuses any URL failing `ValueGuards`.
  `RealEstateAiApp` implements `coil.ImageLoaderFactory` and returns a loader built on that client.
- API checked against sources: OkHttp 4.10.0 (`followRedirects`, `followSslRedirects`, `cookieJar`, `dns`,
  `proxy(Proxy?)`, `addInterceptor`, `Authenticator.NONE`, `CookieJar.NO_COOKIES`, `Dns.SYSTEM`, `fun interface Interceptor`);
  Coil 2.7.0 (`ImageLoaderFactory`, `ImageLoader.Builder.okHttpClient(OkHttpClient)`, default `callFactory = OkHttpClient()`).
  `io.coil-kt:coil-compose` includes the singleton module, so no build change was needed.
- Test: `app/src/test/.../domain/image/ImageFetchPolicyTest.kt`. **Written, not executed here.**

### F-04 The urlintelligence job store never keeps credentials
- Code: `url/JobUrlRedactor.kt` (new). `PropertyUrlResolver.resolve` stores the redacted form. It reuses
  `SensitiveUrlParameters`, and it never throws.
- Test: `JobUrlRedactorTest.kt`, 7 tests. Two resolver tests that failed at base now pass:
  `embedded URL credentials are redacted before invalid jobs are persisted`, and
  `credential-like query values are omitted from fetched and persisted job URLs`.

### F-05 The module's response guard covers every adapter
- Code: `PropertyUrlResolver.resolve` runs `ResponseGuard.inspect` before `adapter.parse`. A rejection is a
  parse failure and the parser never sees the document.
- Tests: `a third party adapter cannot bypass the response guard`, `resolver options propagate fetch limits`,
  and `the resolver rejects a hostile response before any adapter sees it`. All three failed at base and pass now.

### F-06 Challenge pages are policy stops in the module
- Code: `SourceFailureClassifier.detectSoftBlock` adds Cloudflare interstitial markers.
- Tests: `captcha consent and login walls are classified and never parsed`, and the two `FixtureManifestTest` captcha
  cases. All failed at base and pass now.

### F-07 Backup exports are pruned
- Code: `data/repository/BackupRetention.kt` (new). Keeps the newest 5 exports. `exportBackup` applies it after writing.
- Test: `BackupRetentionTest.kt`, 5 tests. **Ran here: 5/5 pass.**

### F-08 Release keystores are ignored
- `.gitignore` adds `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`, `my-upload-key.jks`.
- Checked with `git check-ignore`: all three probe files are ignored.

### F-09 Unused release dependencies removed
- `app/build.gradle.kts`: `firebase.ai`, `firebase.appcheck.recaptcha` and `logging.interceptor` are commented out
  with reasons. A search of `app/src`, `urlintelligence/src` and `repairestimator/src` found no reference to any of them.
  Retrofit stays, because `FailureClassifierTest` imports `retrofit2`.
- Needs a CI build to confirm.

### Test-hygiene fixes (not security fixes, required to get the suite green)
- `PropertyUrlValidatorTest`: the loopback and metadata host cases used `http://`, and the validator correctly
  reports `UnsupportedScheme` first. The cases now use `https://`, which is what the host check is meant to cover.
- `IdempotencyTest` and `IdempotencyLedgerTest`: fixtures used `createdAtEpochMillis = 0L`, which the 24-hour TTL
  correctly purged. They now use the test clock.
- `ArchitectureGuardsTest` (secret scanner): fixture variables named `secret` matched the scanner's own pattern.
  Renamed. The fixture values are fake.

### Verification log

```
urlintelligence (pure JVM, Kotlin 2.2.10):   baseline (base commit)  228 run, 13 failed
                                              after changes           235 run,  1 failed
app pure-Kotlin units (13 tests):              13 passed, 0 failed
git check-ignore (keystore probes):            all ignored
secret scan (tracked files + full history):    only synthetic test fixtures (see F-18)
```

---

## 5. Validation of the claims in `SECURITY_AUDIT.md`

| Claim | Result |
|---|---|
| SEC-01 BuildConfig holds no secrets | Confirmed. The only field is `GOOGLE_OAUTH_CLIENT_ID`, a public identifier. |
| SEC-02 backups disabled and rules exclude app data | Confirmed by reading the manifest and both rule files. Robolectric test not run here. |
| SEC-03 FileProvider scoped to `files/offers/` and not exported | Confirmed. |
| SEC-04 Keystore AES-GCM, randomised IV, fail-closed decrypt | Confirmed by reading `CryptoManager` and `ConfigRepository`. |
| SEC-05 no secrets in logs | Confirmed for the two `Log.w` calls, which log only the exception class name. Unbounded body reads are F-14. |
| SEC-06 PDF approval (canonical prefix, `%PDF-` magic, 20 MB) | Confirmed by reading `OfferPdfStorage` and `GmailMimeBuilder`. |
| SEC-07 App Check debug only | **Partly.** The debug provider is debug-only. The release recaptcha dependency was left in place. Removed under F-09. |
| SEC-08 image URL guard | **Partly.** The ingestion guard holds. The restore path bypassed it (F-01), and redirects and DNS were not checked (F-02). Both are fixed. |
| SEC-09 private host coverage | Confirmed. `UrlHosts.isPrivateNetwork` and `PropertyUrlValidator.isBlockedHost` cover the listed ranges and suffixes. Module tests pass. |
| SEC-10 blank `User-agent:` cannot shadow `*` | Confirmed by reading `RobotsTxt.isAgentMatch`. Module robots tests pass. |
| SEC-11 robots gate fails closed | Confirmed. `UnavailableBehavior.DENY` is the default. |
| SEC-12 credentials removed from canonical and stored URLs | Confirmed for the app path (`Redaction.url(rawInput)` in `PropertyUrlIntelligence`) and for the module canonical form. The module's job store did not comply (F-04), now fixed. |
| SEC-13 credentials attached only to sources that declare them | Confirmed. The gate is at `PropertyUrlIntelligence.kt` lines 801–804. |
| `metadata.json` says `SERVER_SIDE_GEMINI_API` | **Inaccurate.** Calls go direct from the client (F-11). Not edited here; the platform metadata should be corrected by its owner. |
| "urlintelligence tests pass" (implicit in the audit) | **Not true at base.** 13 of 228 failed (F-13). |

---

## 6. Accepted residual risks

- **Room data is not encrypted** (offers, email send history, property and financial data, AI chat). Mitigations:
  `allowBackup=false`, backup and extraction rules exclude the database, and no exported components. A rooted device
  or a compromised device owner can read it. SQLCipher was not added because it is not a reviewed decision here.
- **Plaintext backup exports** stay in app-private storage. Retention is now bounded (F-07), but the files are not encrypted.
- **Gemini and Gmail credentials are on device** (F-11, RB-3). The Gmail refresh token is Keystore-encrypted.
  Acceptable for a single-owner device; not for a shared or multi-user product.
- **Data sent to Gemini** (listing text, financials, chat content) leaves the device. This needs disclosure in the privacy
  policy and store data-safety forms. It is not a code defect.
- **Dormant code paths** (F-05, F-15, F-16) are not reachable in the shipped app. Fix or delete before wiring them in.
- **TLS trust** uses the system trust store (no pinning). This is a common, deliberate choice for consumer apps. Pinning would need a rotation plan.

---

## 7. Release blockers

| ID | Blocker | Owner action |
|---|---|---|
| RB-1 | **No recipient entry.** The manual UI has no recipient field, and generated offers use the placeholder. After F-03, offers cannot be sent to a real recipient until one is entered. | Product decision and UI work. Do not relax the placeholder check to unblock sends. |
| RB-2 | **Gmail cannot be connected.** There is no in-app OAuth flow (F-12). | Implement an authorization-code flow with PKCE using a platform-appropriate OAuth library. Do not embed a client secret in the app. |
| RB-3 | **Gemini key on device** (F-11). Acceptable only for single-owner sideloading. | For any store or multi-user release: backend proxy holding the key, with auth, rate limiting and per-user quotas. |
| RB-4 | **Build and tests not run on this change.** | CI must pass `:app:testDebugUnitTest`, `:app:assembleRelease`, `:urlintelligence:test`, and lint before merge. Add `:urlintelligence:test` to CI (F-13). |
| RB-5 | **Privacy disclosure** for data sent to Gemini and Gmail. | Policy and data-safety form. Not a code change. |

RB-4 is the only one that blocks merging this branch. RB-1 through RB-3 and RB-5 block releasing the send and Gmail features.

---

## 8. R8 and minification recommendation

**Recommendation:** enable R8 on release in a separate PR, after the CI build below. Do not treat it as a security control.

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("release")
}
```

Keep rules, only where a build failure or a runtime test shows they are needed:
- Moshi codegen adapters: follow Moshi's R8 guidance for `@JsonClass(generateAdapter = true)`.
- WorkManager workers: the worker class name is persisted, so keep worker class names. Otherwise work enqueued by an
  older build will not resolve after an update.
- Room, OkHttp and Coil ship consumer rules. Verify rather than assume.

**Testing caveats (must be done before enabling):**
1. Build `assembleRelease` in CI. This sandbox cannot run Gradle.
2. Run instrumented or manual smoke tests on a device: the Gemini analysis call, backup export and import round trip,
   offer PDF generation, automation work surviving a process restart, and image loading through the new loader.
3. Upgrade test: install the previous release, enqueue automation work, then install the R8 build and confirm the work still runs.
4. Store `mapping.txt` privately and upload it to crash reporting. Do not commit it.
5. R8 removes dead code and renames symbols. It does not protect secrets. The APK contains none (section 9).

---

## 9. Secrets in the APK

- **Build config:** `BuildConfig` exposes only `GOOGLE_OAUTH_CLIENT_ID`, a public client identifier. Restrict it in Google
  Cloud to the package name and signing certificate.
- **Gemini key:** entered by the user at runtime, stored encrypted with a Keystore key. Not present in source, resources or `BuildConfig`.
- **Release signing:** read from environment variables (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`). No credentials are hard-coded.
  The debug configuration uses the standard Android debug keystore and applies only to debug builds.
- **Search:** no service-key patterns (Google API keys, OAuth client secrets, private keys, cloud access keys, bearer tokens)
  in the working tree or in the full git history, except the synthetic test strings in F-18.
- **Not verified here:** the built APK itself (no build). Confirm with `apkanalyzer` or by unzipping `classes.dex` after CI builds it.

---

## 10. Dependency posture

- Unused and removed from release (F-09): `firebase-ai`, `firebase-appcheck-recaptcha`, `logging-interceptor`.
- Kept, used by tests: `retrofit`.
- Candidates for review: `converter-moshi` (no direct reference found in `app/src/main`).
- `firebase-appcheck-debug` stays `debugImplementation`.
- Dependency vulnerability scanning was not run (no outbound access to the advisory feeds from the sandbox). Run it in CI.

---

## 11. Recommendations (in priority order)

1. Merge after CI passes (RB-4). Fix the one remaining urlintelligence failure (F-19) by deciding the identity-key rule explicitly.
2. Add `:urlintelligence:test` to CI so module regressions are caught (F-13).
3. Build the recipient entry UI before enabling send in any build (RB-1).
4. Decide on backend mediation for Gemini before any non-personal distribution (F-11, RB-3). Correct `metadata.json`.
5. Implement Gmail OAuth with PKCE, or remove the Gmail send path from the product until it exists (RB-2, F-12).
6. Enable R8 per section 8 in a separate PR.
7. Bound the Gemini and Gmail response reads (F-14). Delete `createGmailIntent` (F-16). Route `OffersViewModel.openPdf` through the approved-PDF check (F-17).
8. Rename the synthetic token strings in test sources (F-18).
