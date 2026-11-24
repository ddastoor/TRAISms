# TRAISms (`com.traisms.app`)

**Production-grade, security-hardened, 100% offline Android SMS organizer** built with Kotlin, Jetpack Compose, Material Design 3, SQLCipher, Google Tink, Jetpack DataStore, LiteRT, and WorkManager.

> **Latest Release & APK Download (`v1.0.3`):** Visit the [**GitHub Releases**](https://github.com/ddastoor/TRAISms/releases/latest) page to download `TRAISms-v1.0.3-release.apk` (pattern: `TRAISms-v<version>-release.apk`) and `SHA256SUMS.txt`.
>
> **Android 15+ / Pixel Sideloading Note:** Android 15/16's *Enhanced Confirmation Mode* blocks sideloaded APKs from becoming the Default SMS App until you allow restricted settings. If Android denies the request:
> 1. Tap **Open App Info** in TRAISms (or open **Settings → Apps → TRAISms**).
> 2. Tap the **⋮** menu (top-right) → **Allow restricted settings** and verify with your PIN or fingerprint *(note: on Android 13–16, the **⋮** menu often appears only after tapping **Set TRAISms as Default SMS App** once and seeing Android's blocked dialog)*.
> 3. Return to **TRAISms** and tap **Set TRAISms as Default SMS App** again (or select TRAISms under **Settings → Apps → Default apps → SMS app**).

---

## Core Architecture & Security Guarantees

| Pillar | Implementation |
| :--- | :--- |
| **100% Offline Execution** | `INTERNET` and `ACCESS_NETWORK_STATE` explicitly stripped via `tools:node="remove"` in `AndroidManifest.xml` |
| **Database Encryption** | Full Room SQLite encryption via **SQLCipher 4.6** (`SupportOpenHelperFactory`) + 32-byte key wrapped in Android Keystore via **Google Tink** |
| **Settings Encryption** | Jetpack DataStore encrypted with Google Tink `Aead` (`AES256_GCM`) — zero `EncryptedSharedPreferences` |
| **Google Drive Backup** | Zero-permission Android Storage Access Framework (`SAF`) + `GZIP` + `AES-256-GCM` (`PBKDF2WithHmacSHA256`, 600,000 iterations) |
| **Screen & Biometric Lock** | `FLAG_SECURE` enabled by default on release builds + optional `BiometricPrompt` App Lock |
| **UI Aesthetic** | Ultra-clean, high-contrast Material 3 **Light Mode Only** with `Plus Jakarta Sans` & `JetBrains Mono` |

---

## 8-Tab Exhaustive SMS Categorization

Each tab includes an independent **Show unread** toggle (enabled by default and persisted per category in Tink-encrypted DataStore), a per-category **Mark all as read** action (inline in the filter bar whenever unread messages exist and via **Mark all as read in [Category]** in the **⋮** menu, strictly scoped to active messages in that tab without touching other categories or Archive), and **independently configurable Left & Right swipe gestures** (`Archive`, `Delete`, or `Assign Category`, persisted in Tink-encrypted DataStore across both **Inbox** and **Archive**) so your inbox stays focused on unread messages while keeping full read history and rapid triage one tap or swipe away:

1. **Personal** — Saved device contacts & user-assigned personal senders
2. **Transactional** — Banking, UPI, cards, invoices & TRAI `-T` headers
3. **Service** — Telecom, deliveries, travel/IRCTC, `127xxx` consent & TRAI `-S` headers
4. **OTP** — Priority-extracted 4–8 char 2FA/verification codes with one-tap **Copy OTP** chips (with optional 1–30 day auto-cleanup)
5. **Promotional** — Offers, marketing & TRAI `-P` headers (automatically marked as read on arrival, with 7-day default auto-cleanup)
6. **Government** — Civic advisories, emergency alerts, NHS/GOVUK/IRS/CRA/NDMA & TRAI `-G` headers
7. **Unknown** — Unsaved raw 7–15 digit phone numbers without alphanumeric headers
8. **Other** — Safe catch-all for messages below the `0.60` local ML confidence threshold

---

## Package Structure

```text
app/src/main/java/com/example/
├── data/
│   ├── crypto/       # TinkKeyManager, EncryptedSettingsStore, BackupCryptoEngine
│   ├── db/           # SQLCipher TraiSmsDatabase, SmsDao, SenderRuleDao, Entities
│   └── sms/          # SmsRepository, SmsDeliverReceiver, SmsNotificationHelper, SmsNotificationActionReceiver, MmsWapPushReceiver, HeadlessSmsSendService
├── domain/
│   └── classifier/   # SmsCategorizationPipeline, OtpDetector, OfflineSmsMlClassifier, RegionProfileDetector
├── worker/           # PromotionalCleanupWorker (12h), OtpCleanupWorker (12h), ScheduledBackupWorker, WorkScheduler
└── ui/
    ├── messages/     # 8-tab Inbox, per-tab unread filter & mark-all-read, search, configurable L/R swipe gestures, per-tab & global delete
    ├── thread/       # ThreadDetailScreen & ComposeSmsScreen
    ├── archive/      # Dedicated Archive screen with multi-select & configurable L/R swipe actions
    ├── backup/       # SAF Google Drive encrypted .trai Backup & Restore Center
    ├── settings/     # Grouped Material 3 Settings, independent L/R Swipe Gesture controls & Security Hardening
    └── onboarding/   # Default SMS RoleManager onboarding & emulator demo seeder
```

---

## Versioning & Automated GitHub Release Workflow

App versioning is defined in a single source of truth at [`version.properties`](version.properties) (`VERSION_NAME` and `VERSION_CODE`), which drives both Gradle (`BuildConfig.VERSION_NAME`, `BuildConfig.VERSION_CODE`, and the APK manifest) and GitHub Releases.

This repository includes [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml), which automatically:
1. Reads `VERSION_NAME` and `VERSION_CODE` from `version.properties` (or a pushed `v*` tag / manual override).
2. Runs unit and Robolectric verification tests (`:app:testDebugUnitTest`).
3. Builds signed release and debug APKs (`TRAISms-v<version>-release.apk` and `TRAISms-v<version>-debug.apk`).
4. Verifies via `aapt2` that the release APK embeds the exact matching `versionName`/`versionCode` and contains zero network permissions.
5. Generates `SHA256SUMS.txt` and uploads the versioned APKs + checksums (`TRAISms-v<version>-APKs`) as a workflow artifact on pushes to `main`/`master` (after `:app:testDebugUnitTest` and `aapt2` zero-network-permission verification), while publishing the formatted release page from [`RELEASE_NOTES.md`](RELEASE_NOTES.md) directly to [**GitHub Releases**](https://github.com/ddastoor/TRAISms/releases) when tagged `v*` (e.g. `v1.0.3`) or triggered manually via `workflow_dispatch` from the **Actions** tab.
