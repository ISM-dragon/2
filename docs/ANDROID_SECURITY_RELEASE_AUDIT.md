# Android security and release audit (2026-10-09)

**Auditor role:** Senior Android application security engineer.
**Repository / branch:** `ISM-dragon/2`, working branch `arena/2e68db17-2`, base commit `712f9b28` (`main`).
**Companion documents:** `SECURITY_AUDIT.md` (round 1, Arabic), `docs/SECURITY_REVIEW_2026-10.md` (round 2).
This audit re-validates those claims against the current tree, adds evidence for the areas they left open,
and records exactly what was and was not executed.

**No secret values appear in this document.** Where a credential-shaped string is discussed it is referenced by
`file:line` only. Nothing in this audit required, requested, or stored a production credential.

---

## 1. Method and verification limits

| Activity | Status |
|---|---|
| Static reading of the manifest, resources, Gradle scripts and Kotlin sources | **Executed** on this commit |
| Pattern scan of the working tree and of the entire git history for credential shapes | **Executed** (V7, V8 below) |
| Caller/reachability analysis for dead and dormant code paths | **Executed** (V9 below) |
| Gradle build, unit tests (`:app:testDebugUnitTest`, `:urlintelligence:test`, `:repairestimator:test`), `assembleDebug`, `assembleRelease`, R8, lint, merged-manifest inspection, `apkanalyzer` on the APK | **Not executed in this sandbox.** No JDK, Android SDK or Gradle distribution is installed and the egress allowlist does not reach Maven Central, Google Maven or `services.gradle.org`. These run in GitHub Actions on the pull request (see §8). |

Every claim below is anchored to a `file:line` in this commit. Commands that produced the evidence are in §7 so a
reviewer can reproduce them without trusting this document.

---

## 2. Threat-model split (read before the findings)

### 2.1 The shipped shape: one app, one device, one owner

Assets on the device: the user-entered Gemini API key, the Gmail refresh/access token pair, offers and their PDFs,
email send history, property, seller/CRM and financial records, AI chat history, and plaintext JSON backup exports.

Controls that matter here are: no exported components, no OS backup, Keystore-backed encryption of credentials,
TLS-only transport to pinned Google endpoints, and sandbox isolation for everything else.

### 2.2 Hypothetical multi-user / cloud-hosted service — **not present, and not built by this audit**

The repository contains no server, no account system, no tenant concept, and no shared secret store. If one were
added, the following would become load-bearing and are **absent today**: server-side custody of the Gemini key and
Gmail refresh tokens, per-tenant isolation, per-user rate limiting and quotas, server-side authorisation of the
Gmail send scope, and an abuse/abuse-reporting pipeline for user-supplied URLs. Distributing this code as a
multi-tenant service without that backend would be unsafe; nothing in this audit should be read as clearance for it.

Related: `metadata.json` declares `MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`, but `GeminiManager` calls
`generativelanguage.googleapis.com` directly from the device (`GeminiManager.kt:253`). The metadata claim is
inaccurate and must not be treated as a control.

---

## 3. Prioritised findings

Severity = impact if exercised. Exploitability = what an adversary needs. Status is at the end of this audit.

| ID | Severity | Exploitability | Status | Finding |
|---|---|---|---|---|
| ASR-01 | Medium (release hardening) | Not exploitable by itself; aids reverse engineering of a shipped APK | **Open — documented, not changed here** | Release build is not minified or resource-shrunk (`isMinifyEnabled = false`). |
| ASR-02 | Low (defence in depth) | Requires a stale or hostile `pdfPath` in the local database or a restored backup | **Fixed in this PR** | The PDF *viewer* entry point handed out a `FileProvider` read grant without resolving the path through `OfferPdfStorage`. |
| ASR-03 | Low (dead code) | Requires a future caller | **Fixed in this PR (deleted)** | `GmailService.createGmailIntent` granted a read URI without the approved-PDF check. |
| ASR-04 | Low | Requires a hostile or malfunctioning Google endpoint, or an on-path attacker | **Open — documented** | Gemini and Gmail response bodies are read into memory with no size cap. |
| ASR-05 | Low (dormant) | Not reachable in the shipped app | **Open — documented** | `data/urlintelligence/OkHttpPropertyTransport` returns an unredacted `finalUrl`. |
| ASR-06 | Info (functional gap) | n/a | **Open — product decision** | No in-app OAuth authorisation flow exists; Gmail cannot be connected from the UI, so direct send is unreachable today. |
| ASR-07 | Info | n/a | **No action — verified synthetic** | Eight credential-shaped strings exist in test sources only; none is a real credential and none matches a scanner pattern. |
| ASR-08 | Medium (data at rest) | Requires a rooted device, a compromised device owner, or an ADB backup by the owner | **Accepted for single-device; blocker for 2.2** | Room is not encrypted. Only credentials are field-encrypted. |
| ASR-09 | Low | Requires physical access to a locked device | **Accepted trade-off** | The Keystore key is usable while the device is locked and is not StrongBox-backed. |
| ASR-10 | Info | Requires a future Network Security Config that permits cleartext | **No action — verified** | `ValueGuards.imageUrl` accepts `http://`; the manifest blocks it at connect time. |
| ASR-11 | Low (build hygiene) | Requires access to the build machine's Gradle cache | **Open — documented** | Release keystore passwords are read at configuration time with the configuration cache enabled. |

Nothing in this audit is a credential leak from the shipped APK. No hard-coded signing or service credentials were
found in the tree or in the history (V7, V8).

---

## 4. Findings in detail

### ASR-01 — Release build is not minified or resource-shrunk — Medium — Open

**Location:** `app/build.gradle.kts:52-58` — release block, `isMinifyEnabled = false` at `:55`

```kotlin
release {
    isCrunchPngs = false
    isMinifyEnabled = false
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("release")
}
```

**Evidence:** `isMinifyEnabled = false`; there is no `isShrinkResources`; `app/proguard-rules.pro` is the
unmodified Android Studio template (all rules commented out).

**Impact:** the shipped DEX keeps original class, method and field names, and unused dependency code is not removed.
This is a hardening and attack-surface issue, not a vulnerability: it makes static analysis and targeted patching of
the app cheaper for anyone holding the APK. It does **not** protect or expose secrets — the APK contains none (§6, FP-1).

**Why this audit does not flip the flag:** enabling R8 touches reflection surfaces that this app depends on
(Room, Moshi codegen adapters, WorkManager worker class names that are persisted across updates, Coil, Compose).
The change cannot be validated here — there is no JDK, no Gradle and no device in the sandbox — and the prior review
(`docs/SECURITY_REVIEW_2026-10.md` §8) already recorded the exact upgrade path and its required smoke tests.
Making an unverifiable change to the release build configuration would be the opposite of a narrow, high-confidence fix.

**Required before release (owner action):** apply the snippet in §8 of the prior review in its own PR, then run
`assembleRelease` in CI, smoke-test the Gemini call / backup round-trip / PDF generation / automation across a
process restart / image loading on a device, run an upgrade test, and archive `mapping.txt` privately.

---

### ASR-02 — PDF viewer granted a URI without resolving the path — Low — **Fixed**

**Location:** `app/src/main/java/com/example/ui/screens/offers/OffersViewModel.kt:95-110` at `HEAD`
(`fun openPdf`), now `:96-121` after this PR.
Call site: `app/src/main/java/com/example/ui/screens/offers/OffersScreen.kt:120`
(`onOpenPdf = { offer.pdfPath?.let { path -> viewModel.openPdf(context, path) } }`).

**Before:**

```kotlin
val file = File(pdfPath)
if (file.exists()) { /* FileProvider.getUriForFile(...); startActivity(ACTION_VIEW) + FLAG_GRANT_READ_URI_PERMISSION */ }
```

**Why it mattered:** the only gate was `File.exists()` plus whatever `FileProvider` does internally. `FileProvider`
does reject paths outside `files/offers/` — it throws — but that rejection was implicit, depended on a throw being
swallowed by the surrounding `catch`, and said nothing about file *type*, nested directories, or a symlink inside
`offers/` that resolves outside it. `pdfPath` is not fully trusted: it is restored from a user-supplied backup JSON
(`BackupRestoreManager.kt:360` already re-resolves it) and it lives in the Room row.

**Fix:** resolve through the same helper the send path uses, and return early when it rejects:

```kotlin
fun openPdf(context: Context, pdfPath: String) {
    val file = OfferPdfStorage.resolveExistingPdf(context, pdfPath) ?: return
    ...
}
```

`OfferPdfStorage.resolveExistingPdf` (`app/src/main/java/com/example/domain/pdf/OfferPdfStorage.kt:32`) requires a
canonicalised file that is a direct child of `filesDir/offers/` with a `.pdf` extension.

**Regression test:** `OfferPdfStorageSecurityTest.viewerGrantIsLimitedToRealPdfFilesDirectlyInsideOffers` asserts
that a nested PDF, a non-PDF inside `offers/`, and a symlink inside `offers/` that resolves outside it are all
unresolvable, and that a generated offer PDF still is.

---

### ASR-03 — Dead share-intent builder bypassed the attachment check — Low — **Fixed (deleted)**

**Location:** `app/src/main/java/com/example/domain/gmail/GmailService.kt:492-517` (deleted).

**Evidence of reachability:** zero references anywhere in `app/src` (V9). The method built an `ACTION_SEND` intent,
called `FileProvider.getUriForFile(...)` on an arbitrary `pdfFile`, added `FLAG_GRANT_READ_URI_PERMISSION`, and
targeted `com.google.android.gm` — without `GmailMimeBuilder.isApprovedOfferPdf`, which every live send path applies
(`GmailService.kt:115`, `OfferRepository.kt:154`).

**Fix:** deleted, together with the then-unused `Intent`, `Uri` and `FileProvider` imports. Nothing referenced it, so
no behaviour changes.

**Regression test:** `GmailOAuthSecurityTest.sendRefusesAttachmentsThatAreNotApprovedOfferPdfs` pins the invariant at
the service boundary: a real PDF outside `files/offers/`, a non-PDF inside it, a symlink escaping it, and a null
attachment are all rejected as `VALIDATION` before any network call.

---

### ASR-04 — Unbounded response bodies on the Gemini and Gmail paths — Low — Open

**Locations:** `app/src/main/java/com/example/domain/ai/GeminiManager.kt:271`
(`response.body?.string().orEmpty()`) and `app/src/main/java/com/example/domain/gmail/GmailService.kt:293`
(token refresh) and `:389` (message send), both `response.body?.string().orEmpty()`.

**Impact:** a hostile or malfunctioning endpoint (or an on-path attacker, if TLS were ever broken) can stream an
arbitrarily large body and exhaust the heap of the app process. The rest of the codebase bounds its reads — see
`urlintelligence/src/main/kotlin/com/example/urlintelligence/fetch/FetchLimits.kt` and `ResponseGuard.kt` — so these
two are the outliers.

**Why not changed here:** the fix means streaming the body with a byte cap on both hot paths. That is a small diff
but it changes error handling on the only two outbound credentialed requests in the app, and it cannot be compiled or
exercised in this sandbox. Recommended implementation: read through `response.body!!.source()` with an okio `Buffer`
capped at a few MiB, and treat an over-long body as a provider error using the existing
`geminiHttpErrorMessage` / `GmailFailureKind.DELIVERY_UNKNOWN` vocabulary. Land it with a MockWebServer test.

---

### ASR-05 — Dormant transport returns an unredacted URL — Low — Open

**Location:** `app/src/main/java/com/example/data/urlintelligence/OkHttpPropertyTransport.kt`
(`finalUrl` is returned unredacted; the production transport in
`app/src/main/java/com/example/domain/propertyurl/port/OkHttpHttpFetcher.kt` redacts it).

**Reachability:** the class is referenced only by `PropertyUrlImportService` in the same package and by its own unit
tests; `RealEstateAiApp.onCreate` wires the `domain/propertyurl` pipeline instead. Not reachable in the shipped app.
**Action:** re-check before wiring it, or delete it.

---

### ASR-06 — No in-app OAuth authorisation flow — Info — Open (product decision)

**Locations:** `app/src/main/java/com/example/ui/screens/settings/SettingsViewModel.kt:87`
(`applyVerifiedOAuthTokens`) and `:138` (`updateGmailConfig`) — both have **zero callers** (V9). The Settings UI can
only save account settings; the connect action
(`app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt`, "Google Gmail Account" card) writes
`email`, `senderName`, `signature` and the subject template and then reports that OAuth sign-in is required.

**Consequence today:** `isConnected` can never become `true` from the UI, so the direct Gmail send path
(`GmailService.sendOfferEmail`) and `validateOfferPreSend` both fail at the
"Gmail account is not connected" check. The Gmail integration is inert in the shipped build. That also means several
credential-exposure scenarios are currently unreachable rather than mitigated — see FP-4.

**Risk if it is wired as written:** `applyVerifiedOAuthTokens` persists any pasted access/refresh token as an
authorised credential with no proof of consent, no scope record, and no audience binding. A paste-based flow also
routes a long-lived refresh token through the **system clipboard**, which keyboards and (on older Android) any
background app can read, and pasting an OAuth Playground token typically carries broader scopes than the app needs.

**Recommendation:** implement an authorization-code flow with PKCE using a platform OAuth library, request only
`gmail.send`, store the granted scope string alongside the token, and never send a client secret from the device.
Do not relax the placeholder-recipient check to make sending work.

---

### ASR-07 — Credential-shaped strings in test sources — Info — No action

Eight strings in four test files match credential shapes (V7, V8):

- `app/src/test/java/com/example/AutomationAuditSecurityTest.kt:16-17`
- `app/src/test/java/com/example/domain/outreach/OutreachSafetyTest.kt:53`
- `app/src/test/java/com/example/domain/outreach/SellerOutreachEngineTest.kt:185, 233, 365`
- `app/src/test/java/com/example/reliability/WorkflowFailureInjectionTest.kt:357, 371`

All are fixtures for redaction tests — they exist precisely so the redactors can be asserted against them, so they
must keep their shape. Measured evidence that they are not scanner triggers: the longest `AIza…` suffix in the tree is
**34 characters**, one short of the 35-character Google API key pattern used by secret scanners. Nothing outside
`app/src/test` matches (V7). No action required; documented so a future scan hit is not mistaken for a leak.

---

### ASR-08 — Room is not encrypted; only credentials are — Medium — Accepted (single device)

**Locations:** `app/src/main/java/com/example/data/local/AppDatabase.kt:48-49, 69` — plain
`Room.databaseBuilder(...)` with no `openHelperFactory` and no SQLCipher; `exportSchema = false`.
Encryption is applied at the field level only, and only to credentials:
`ConfigRepository.kt:41-42` (Gmail access/refresh token), `:193` (Gemini API key), decrypted at `:281, :294-295`
and in `GeminiManager.kt:56, 79, 132`.

**What this means, stated precisely:** the database file, the AI chat history, the property/seller/CRM records, the
financial analyses, the offers and their PDFs are **not** encrypted at rest by the app. They rely on the Android
application sandbox and on device-level encryption (FDE/FBE), which is on for any device with a lock screen.
`CryptoManager` is AES-256-GCM with a 12-byte provider-generated IV and a 128-bit tag
(`app/src/main/java/com/example/data/security/CryptoManager.kt:67-69, 114`), keyed by an Android Keystore key
(`:169`, alias `RealEstateAiMasterKey`), and it fails closed on corrupt, truncated or unauthenticated ciphertext.

**Verdict:** acceptable for the single-owner device in §2.1. It is a blocker for §2.2 — a shared or multi-user build
would need tenant-scoped encryption keyed per account, not a single device Keystore key.

---

### ASR-09 — Keystore key usable while locked — Low — Accepted trade-off

`KeyGenParameterSpec.Builder` (`CryptoManager.kt:61-72`) sets purpose, block mode, padding, key size and
`setRandomizedEncryptionRequired(true)`. It does not call `setUnlockedDeviceRequired(true)` (API 28+) and does not
request StrongBox.

This is deliberate and correct for this app: `AutomationCycleWorker` runs automation in the background via WorkManager
while the device can be locked, and that path decrypts the Gemini key and mints Gmail access tokens. Binding the key
to the unlocked state would break every scheduled cycle. If a future build stops decrypting credentials in the
background, revisit.

---

### ASR-10 — Image URL guard permits `http://` — Info — No action

`ValueGuards.imageUrl` (`app/src/main/java/com/example/domain/propertyurl/normalize/ValueGuards.kt`) accepts
`http://` and `https://`. Cleartext is blocked at connect time by `android:usesCleartextTraffic="false"`
(`AndroidManifest.xml:11`), and `targetSdk = 36` would also disable it by default. The guard additionally rejects
userinfo, IP-literal hosts, malformed hosts, non-public hosts (`UrlHosts.isPrivateNetwork`) and `..` path segments,
and it is re-applied at every use: `ImageFetchPolicy.isAllowedImageUrl` runs as an OkHttp interceptor on the Coil
loader wired in `RealEstateAiApp.kt:38-40`, and `BackupRestoreManager.kt:270` re-screens image URLs on restore.

No exposure today. The residual dependency is on the manifest flag: if a Network Security Config that permits
cleartext for some domain is ever added, image loads would silently become cleartext. Note it in that PR.

---

### ASR-11 — Release signing config is evaluated at configuration time — Low — Open

**Location:** `app/build.gradle.kts:37-44` — `System.getenv("KEYSTORE_PATH" / "STORE_PASSWORD" / "KEY_PASSWORD")` are
read while the build script is configuring, and `gradle.properties` sets `org.gradle.configuration-cache=true`.
The signing config (including both passwords) becomes part of the signing task's inputs, which the configuration
cache persists under `.gradle/configuration-cache/`.

**Impact:** keystore passwords can come to rest in the build machine's Gradle cache directory. That directory is not
committed (`.gitignore` excludes `.gradle`), but it is a reason not to copy Gradle caches between machines or publish
them as CI artifacts, and to prefer a short-lived CI keystore injection over a long-lived developer cache.

**Recommendation:** keep release signing on CI only, inject the keystore for the duration of the job, and treat
`~/.gradle/configuration-cache` as secret material (or disable the configuration cache for release builds).

---

## 5. Areas checked and found sound (no change)

| Area | Evidence |
|---|---|
| **Exported components** | `AndroidManifest.xml` declares one activity (`MainActivity`, `exported="true"`, launcher intent-filter only), one non-exported `FileProvider`, and removes `androidx.startup.InitializationProvider`. No services, receivers or content providers of our own. `MainActivity.onCreate` ignores the incoming intent, so there is no deep-link surface. Covered by `AndroidSecuritySurfaceTest.backupsAreDisabledAndNoNonLauncherComponentIsExported`. |
| **Permissions** | Only `INTERNET` and `ACCESS_NETWORK_STATE` (`AndroidManifest.xml:5-6`). No dangerous permissions, no location, contacts, storage or notification permission. |
| **OS backup / device transfer** | `allowBackup="false"` (`:10`); `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` exclude `database`, `file`, `sharedpref`, `root` and `external` for cloud backup **and** device transfer. Tested by `AndroidSecuritySurfaceTest.backupRuleFilesExcludePrivateApplicationData`. |
| **FileProvider scope** | `res/xml/file_paths.xml` declares exactly one path: `files-path name="offer_pdfs" path="offers/"`. Provider is `exported="false"` with `grantUriPermissions="true"`. Tested by `AndroidSecuritySurfaceTest.fileProviderIsPrivateAndOnlySharesOfferPdfs`. |
| **Gemini transport** | Hardened client (`GeminiManager.kt:27-38`): no redirects, no SSL redirects, no cookies, no authenticator/proxy, no connection retries. Endpoint pinned to `https` + `generativelanguage.googleapis.com` + port 443 + exact path + no userinfo/query/fragment (`:287-298`). Model name restricted to `[A-Za-z0-9._-]{1,100}` before interpolation into the URL (`:220`). Key travels in the `x-goog-api-key` header, never in the URL (`:260`). Provider error **bodies** are never returned or persisted — only a status-code-shaped message (`GeminiModels.kt`). |
| **Gmail transport** | Same hardening applied on top of any injected client (`GmailService.kt:73-83`). Both endpoints pinned (`:257`, `:361`, validator at `:456`). Access token only in `Authorization: Bearer` (`:379`); refresh token only in a POST form body to `oauth2.googleapis.com/token` (`:284-285`). Google's raw OAuth response body is never persisted (`:298`). |
| **MIME / header injection** | `GmailMimeBuilder.build` requires safe addresses and rejects CR/LF and ISO control characters in subject, recipient name and sender name (`:122`); display names and subjects are RFC 2047 encoded-word encoded (`:148`); the attachment filename is a fixed literal. |
| **Attachment approval** | `GmailMimeBuilder.isApprovedOfferPdf` (`:91`) canonicalises, requires a direct path under `filesDir/offers/`, requires a readable regular file of at least 5 bytes, and verifies the `%PDF-` magic. Applied in `GmailService.kt:115`, `OfferRepository.kt:154`, and on restore in `BackupRestoreManager.kt:360`. |
| **Logging** | Two `Log.w` calls in the entire `main` sourceset (`PropertySourceAdapter.kt:214`, `:262`), both logging only the exception **class name** with a fixed message. No `println`, `printStackTrace`, `System.out`/`System.err`, and no HTTP logging interceptor in the dependency graph (V4). |
| **Redaction** | `Redaction` masks bearer tokens, JWTs, Google API keys and OAuth tokens, `key=value` assignments, emails and phone numbers, and sensitive header names; it is applied before job records, audit messages and offer send errors are persisted (`AutomationAuditLogger.kt:93`, `AutomationExecutionLedger.kt:100`, `OfferRepository.kt:613`). |
| **User-supplied URL fetching** | `PropertyUrlValidator` defaults refuse IP-literal hosts, private-network hosts, credentials in URL, unsupported schemes and disallowed ports. Redirects are off and re-validated manually. `PublicOnlyDns` resolves only to public addresses and is the resolver used for the connection, so there is no resolve-then-connect window. `ImageFetchPolicy` re-applies the same rules to Coil. robots.txt is fetched without credentials and fails closed. |
| **Backup export/import** | Exports are plaintext JSON in `filesDir/backups/` (a residual risk, ASR-08) but retention is bounded to the newest five (`BackupRetention`), the format is version-checked, `primaryImageUrl` is re-screened on restore (`:270`), `pdfPath` is re-resolved through `OfferPdfStorage` (`:360`), and offer/email-send rows are dropped unless their parent property and offer pass referential checks. No API key or token field exists in the format. |
| **Job store hygiene** | `FilePropertyImportJobStore` writes atomically (`*.tmp` + rename) into `filesDir/property-url-intelligence/jobs/`, sanitises job ids to `[A-Za-z0-9_-]` before building a path, and trims to a maximum file count. No temp files are created elsewhere; no `MODE_WORLD_*`, `openFileOutput`, external storage or `Environment.*` usage anywhere in `main`. |
| **Secrets in the APK** | `BuildConfig` carries exactly one field, `GOOGLE_OAUTH_CLIENT_ID` (`app/build.gradle.kts:33`), documented as a public identifier in `.env.example`; Gradle does not load `.env`. No `google-services.json`, keystore or `.env` is tracked (`git ls-files`). |
| **Dependency posture** | No Firebase API is called from `main`; `firebase-appcheck-debug` is `debugImplementation`; `dependenciesInfo.includeInApk = false` (`:71`), so dependency-signature metadata is not shipped in the APK. |
| **Other** | No `WebView` and no JavaScript interfaces; no custom `TrustManager`, `HostnameVerifier` or certificate pinning (system trust store by design); no notifications, `PendingIntent` or foreground service; no `FLAG_SECURE` (screenshots of financial screens are possible — informational). |

---

## 6. False positives and speculative risks (explicitly labelled)

These were investigated and are **not** findings. They are recorded so the next audit does not re-open them.

- **FP-1 — "The Gemini API key is compiled into the APK."** False. There is one `buildConfigField`, and it is the
  OAuth client ID (`app/build.gradle.kts:33`); `ConfigRepository.seedDefaultsIfEmpty` creates six slots with empty
  keys and status `DISABLED`, and `GeminiManager` reads only user-entered, Keystore-encrypted values. Any key ever
  shipped by an older build cannot be removed by a code change — rotate it out of band.
- **FP-2 — "Secrets are in git history."** False. The history is a single squashed commit; a history-wide scan for
  Google API keys, OAuth tokens and client secrets returns eight hits, **all** in `app/src/test` (V8).
- **FP-3 — "The OAuth client secret is embedded."** False. No client secret exists anywhere. The refresh grant
  (`GmailService.kt:282-286`) sends only `grant_type`, `refresh_token` and `client_id`, which is the correct shape for
  an installed-app client. A client ID is a public identifier; it should still be restricted in Google Cloud to the
  package name and signing certificate.
- **FP-4 — "A pasted OAuth token is exposed in the clipboard."** Not exploitable today. The only token-paste entry
  point (`SettingsViewModel.applyVerifiedOAuthTokens`) has zero callers (V9), so the clipboard is never involved.
  It becomes a real risk the moment that method is wired — see ASR-06.
- **FP-5 — "Server-side request forgery in the property URL importer."** The classic SSRF model (a server coerced into
  fetching internal resources) does not apply: fetches originate on the user's own device. The equivalent client-side
  concern — a hostile page steering the device at the LAN or the cloud metadata endpoint — **is** addressed
  (validator defaults, `PublicOnlyDns`, no redirects, no credentials on redirect, image policy) and was verified.
- **FP-6 — "Cleartext traffic is possible."** `usesCleartextTraffic="false"` is explicit and `targetSdk` is 36; no
  Network Security Config exists to weaken it. See ASR-10 for the one residual dependency.
- **Speculative (out of scope, must not be assumed):** multi-tenant isolation, per-user rate limiting, server-side
  token custody, abuse handling for user-submitted URLs, and any claim that Gemini is called server-side. None of
  this exists; see §2.2.

---

## 7. Exact verification results

All commands were run from the repository root on branch `arena/2e68db17-2` at commit `712f9b28` plus this PR's
changes. Outputs are verbatim.

```
== V1: manifest attack surface ==
5:    <uses-permission android:name="android.permission.INTERNET" />
6:    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
10:        android:allowBackup="false"
11:        android:usesCleartextTraffic="false"
12:        android:dataExtractionRules="@xml/data_extraction_rules"
13:        android:fullBackupContent="@xml/backup_rules"
21:            android:authorities="${applicationId}.androidx-startup"
25:            android:exported="true"
38:            android:authorities="${applicationId}.fileprovider"
39:            android:exported="false"
40:            android:grantUriPermissions="true">

== V2: FileProvider scope ==
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path name="offer_pdfs" path="offers/" />
</paths>

== V3: release build type / signing / build config fields ==
    buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$escapedGoogleOAuthClientId\"")
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true

== V4: logging surface in app/src/main ==
app/src/main/java/com/example/data/adapter/PropertySourceAdapter.kt:214:                android.util.Log.w(
app/src/main/java/com/example/data/adapter/PropertySourceAdapter.kt:262:                android.util.Log.w(

== V5: network hardening of the Gemini client ==
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

== V6: network hardening of the Gmail client ==
    private val safeHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY)
        .retryOnConnectionFailure(false)
        .build()

== V7: credential-shaped strings in the tracked tree ==
./app/src/test/java/com/example/AutomationAuditSecurityTest.kt
./app/src/test/java/com/example/domain/outreach/OutreachSafetyTest.kt
./app/src/test/java/com/example/domain/outreach/SellerOutreachEngineTest.kt
./app/src/test/java/com/example/reliability/WorkflowFailureInjectionTest.kt

== V8: git history size and history-wide secret scan ==
commits: 1
history matches: 8

== V9: dormant code paths (callers outside their own declaration) ==
createGmailIntent -> 0 caller(s)
applyVerifiedOAuthTokens -> 0 caller(s)
updateGmailConfig -> 0 caller(s)

== V10: encryption at rest coverage (CryptoManager call sites) ==
app/src/main/java/com/example/data/repository/ConfigRepository.kt
app/src/main/java/com/example/data/security/CryptoManager.kt
app/src/main/java/com/example/domain/ai/GeminiManager.kt

== V11: Room database configuration ==
48:    version = 4,
49:    exportSchema = false
69:                val instance = Room.databaseBuilder(
```

Commands used (reproduce with these exact invocations):

```bash
grep -nE "uses-permission|allowBackup|usesCleartextTraffic|dataExtractionRules|fullBackupContent|android:exported|grantUriPermissions|authorities|networkSecurityConfig|debuggable" app/src/main/AndroidManifest.xml
cat app/src/main/res/xml/file_paths.xml
sed -n '33p;36,58p;70,72p' app/build.gradle.kts
grep -rn "android.util.Log\.\|println(\|printStackTrace\|System\.out\|System\.err" app/src/main --include=*.kt
sed -n '26,44p' app/src/main/java/com/example/domain/ai/GeminiManager.kt
sed -n '73,83p' app/src/main/java/com/example/domain/gmail/GmailService.kt
grep -rlIE "AIza[0-9A-Za-z_-]{10,}|ya29\.[A-Za-z0-9._-]{6,}|client_secret|BEGIN [A-Z ]*PRIVATE KEY|AKIA[0-9A-Z]{16}" --exclude-dir=.git . 2>/dev/null
git rev-list --all | wc -l
git grep -InE "AIza[0-9A-Za-z_-]{10,}|ya29\.[A-Za-z0-9._-]{6,}|client_secret" $(git rev-list --all) -- | wc -l
grep -rl "CryptoManager" app/src/main --include=*.kt
grep -n "databaseBuilder\|exportSchema\|version =" app/src/main/java/com/example/data/local/AppDatabase.kt
```

Additional checks run while auditing (not reproduced above): tracked-file inventory for `.env`, keystores and
`google-services.json` (only `.env.example` is tracked); `AIza…` suffix-length measurement (longest = 34 characters);
caller search for `OkHttpPropertyTransport` / `PropertyUrlImportService` (same-package and test references only);
and full-file reads of `CryptoManager`, `ConfigRepository`, `GmailService`, `GmailMimeBuilder`, `OfferPdfStorage`,
`BackupRestoreManager`, `BackupRetention`, `ImageFetchPolicy`, `PublicOnlyDns`, `ValueGuards` and `FilePropertyImportJobStore`.

---

## 8. Release checklist — verified vs unverified

### 8.1 Verified controls (evidence in this document)

- [x] `allowBackup="false"` and cloud/device-transfer rules exclude database, files, shared prefs, root and external (V1; `backup_rules.xml`, `data_extraction_rules.xml`).
- [x] `usesCleartextTraffic="false"`, explicitly (V1).
- [x] Only the launcher activity is exported; `FileProvider` is not exported; no services/receivers (V1, manifest read).
- [x] Only `INTERNET` and `ACCESS_NETWORK_STATE` are requested (V1).
- [x] `FileProvider` shares exactly one directory, `files/offers/` (V2).
- [x] No secrets in the tracked tree or in git history beyond synthetic test fixtures (V7, V8; ASR-07).
- [x] Credentials are Keystore-encrypted AES-256-GCM and decryption fails closed (`CryptoManager.kt`; ASR-08).
- [x] Gemini and Gmail clients: no redirects, no cookies, no proxy, no authenticators, no automatic retries (V5, V6).
- [x] Both Google endpoints are pinned to https + host + port 443 + exact path + no userinfo/query/fragment; credentials are never placed in a URL.
- [x] Provider error bodies and OAuth responses are never persisted or surfaced.
- [x] MIME headers reject CR/LF and control characters; display names are encoded-word encoded.
- [x] Attachments must be real PDFs directly under `files/offers/`, on the send path, the pre-send validation and the restore path.
- [x] Logging in `main` is two `Log.w` calls, both exception-class-only (V4).
- [x] Untrusted image URLs are screened at ingestion, at restore, and again in the Coil interceptor; DNS is public-only and redirects are off.
- [x] Job records are written atomically with sanitised ids; no temp files elsewhere; no world-readable or external files.
- [x] Dead or dormant credential paths confirmed unreachable (V9) and the two that mattered removed (ASR-02, ASR-03).
- [x] `dependenciesInfo.includeInApk = false`; no Firebase API used; App Check debug provider is debug-only.

### 8.2 Unverified — must be confirmed before release

These require a toolchain that is not available here. The pull request runs the first four automatically.

- [ ] `:app:testDebugUnitTest` passes, including the two new tests in this PR (GitHub Actions, `ai-analyst-ci.yml` → "Full app build and module test gate").
- [ ] `:urlintelligence:test` and `:repairestimator:test` pass.
- [ ] `:app:assembleDebug` succeeds.
- [ ] **Merged manifest inspection** (`apkanalyzer manifest print app-release.apk`): confirm no dependency contributes an exported activity/service/receiver beyond the launcher, and that `androidx.startup.InitializationProvider` is absent.
- [ ] `:app:assembleRelease` succeeds **and** R8 is enabled per ASR-01, with the smoke tests in `docs/SECURITY_REVIEW_2026-10.md` §8.
- [ ] APK secrets sweep on the built artifact (unzip + `strings` on `classes*.dex`, `apkanalyzer` on resources): no key, token or keystore material. Only the public OAuth client ID should be present.
- [ ] Release signing: keystore from `KEYSTORE_PATH`, passwords from the environment, Play App Signing enrolment, and no keystore or `mapping.txt` committed.
- [ ] Dependency vulnerability scan in CI (no advisory feeds are reachable from this sandbox).
- [ ] Manual device checks: fresh install, backup export → restore round trip, offer PDF generation → view → send, automation surviving a process restart, image loading from a real listing URL.
- [ ] Privacy disclosure for data sent to Gemini and Gmail (store data-safety form and privacy policy). Not a code change.
- [ ] ASR-06 decision: implement OAuth with PKCE, or ship with the Gmail send path inert and clearly labelled.

---

## 9. Changes made by this audit

| Change | File | Rationale |
|---|---|---|
| Resolve the PDF path before granting a URI | `app/src/main/java/com/example/ui/screens/offers/OffersViewModel.kt` | ASR-02 |
| Delete `GmailService.createGmailIntent` and its now-unused imports | `app/src/main/java/com/example/domain/gmail/GmailService.kt` | ASR-03 |
| Regression test: viewer grants are limited to real PDFs inside `offers/` | `app/src/test/java/com/example/OfferPdfStorageSecurityTest.kt` | ASR-02 |
| Regression test: the send service rejects unapproved attachments before any network call | `app/src/test/java/com/example/GmailOAuthSecurityTest.kt` | ASR-03 |
| This document | `docs/ANDROID_SECURITY_RELEASE_AUDIT.md` | Deliverable |

No UI or business logic was rewritten, no backend was added, no security check was relaxed, and no secret was
introduced or requested. Every other finding in §3 is documented rather than changed, with the reason recorded
inline.
