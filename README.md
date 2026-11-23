# TRAISms (`com.traisms.app`)

**Production-grade, security-hardened, 100% offline Android SMS organizer** built with Kotlin, Jetpack Compose, Material Design 3, SQLCipher, Google Tink, Jetpack DataStore, LiteRT, and WorkManager.

> **Latest Release & APK Download:** Visit the [**GitHub Releases**](../../releases/latest) page to download `TRAISms-v1.0.0-release.apk` and `SHA256SUMS.txt`.

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

1. **Personal** — Saved device contacts & user-assigned personal senders
2. **Transactional** — Banking, UPI, cards, invoices & TRAI `-T` headers
3. **Service** — Telecom, deliveries, travel/IRCTC, `127xxx` consent & TRAI `-S` headers
4. **OTP** — Priority-extracted 4–8 char 2FA/verification codes with one-tap **Copy OTP** chips
5. **Promotional** — Offers, marketing & TRAI `-P` headers (with 7-day default auto-cleanup)
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
│   └── sms/          # SmsRepository, SmsDeliverReceiver, MmsWapPushReceiver, HeadlessSmsSendService
├── domain/
│   └── classifier/   # SmsCategorizationPipeline, OtpDetector, OfflineSmsMlClassifier, RegionProfileDetector
├── worker/           # PromotionalCleanupWorker (12h), ScheduledBackupWorker, WorkScheduler
└── ui/
    ├── messages/     # 8-tab Inbox, search, swipe gestures, per-tab & global delete
    ├── thread/       # ThreadDetailScreen & ComposeSmsScreen
    ├── archive/      # Dedicated Archive screen with multi-select & swipe unarchive
    ├── backup/       # SAF Google Drive encrypted .trai Backup & Restore Center
    ├── settings/     # Grouped Material 3 Settings & Security Hardening controls
    └── onboarding/   # Default SMS RoleManager onboarding & emulator demo seeder
```

---

## Automated GitHub Release Workflow

This repository includes [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml), which automatically:
1. Runs unit and Robolectric verification tests (`:app:testDebugUnitTest`).
2. Builds signed release and debug APKs (`TRAISms-v1.0.0-release.apk` and `TRAISms-v1.0.0-debug.apk`).
3. Generates `SHA256SUMS.txt`.
4. Publishes the formatted release page from [`RELEASE_NOTES.md`](RELEASE_NOTES.md) directly to **GitHub Releases** whenever pushed to `main`/`master`, tagged `v*`, or triggered manually from the **Actions** tab.
