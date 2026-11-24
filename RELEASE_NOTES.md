# TRAISms v1.0.3 — Actionable SMS Notifications & 4-Button OTP Shade

**Package ID:** `com.traisms.app`  
**Minimum Android Version:** Android 7.0 (API 24) • **Target SDK:** Android 16 (API 36)  
**Network Permissions:** **0 (`INTERNET` & `ACCESS_NETWORK_STATE` stripped at Manifest merge)**

---

## Download & Install

Download **`TRAISms-v1.0.3-release.apk`** from the **Assets** section below:

1. Verify the file's SHA-256 checksum against `SHA256SUMS.txt`:
   ```bash
   sha256sum -c SHA256SUMS.txt
   ```
2. Transfer `TRAISms-v1.0.3-release.apk` to your Android phone and open it to install.
3. Launch **TRAISms** and tap **Set TRAISms as Default SMS App** to import and encrypt your existing SMS inbox on-device.

### Installing on Android 15 / 16 (Google Pixel & Sideloaded APKs)

On Android 15 and 16 (including Google Pixel devices), Android's **Enhanced Confirmation Mode** restricts APKs sideloaded outside the Play Store from becoming the Default SMS App, displaying *"App was denied access to be default SMS app ... restricted permissions"*. This is an OS sideloading safeguard — TRAISms meets all Android 14–16 `ROLE_SMS` requirements while maintaining zero network permissions.

To unlock Default SMS access in 3 steps:

1. **Open TRAISms App info** — Tap **Open App Info** inside TRAISms (or go to **Settings → Apps → TRAISms**).
2. **Allow restricted settings** — Tap the **⋮** (top-right) menu → **Allow restricted settings**, then verify with your PIN, pattern, or fingerprint.
3. **Set TRAISms as Default SMS App** — Return to **TRAISms** and tap **Set TRAISms as Default SMS App** (or choose **TRAISms** under **Settings → Apps → Default apps → SMS app**).

> **Tip:** On Android 13–16, the **⋮ → Allow restricted settings** menu item in App info often appears **only after** you first tap **Set TRAISms as Default SMS App** once and dismiss Android's blocked dialog.

---

## What's New in v1.0.3

- **OTP Auto-Delete (`Off`, `1`, `3`, `7`, `15`, `30` Days) & In-Tab Auto-Delete Bar:**
  - Added configurable **OTP Auto-Delete** (`Off` [Default], `1`, `3`, `7`, `15`, or `30` Days) directly inside an in-tab quick-preset bar at the top of the **OTP** tab (alongside a matching quick-preset bar on the **Promotional** tab) as well as under **Settings → OTP Auto-Delete**.
  - Powered by a periodic 12-hour offline `OtpCleanupWorker` (`WorkManager` with `NetworkType.NOT_REQUIRED`) plus immediate purge on preset selection, surgically restricted to unarchived **OTP** messages in both SQLCipher Room and the system `Telephony.Sms` provider — never touching **Personal**, **Transactional**, **Service**, **Promotional**, **Government**, **Unknown**, **Other**, or **Archived** messages.
- **4-Button OTP Custom `RemoteViews` Notifications:** Unlocked OTP alerts use `NotificationCompat.DecoratedCustomViewStyle` with custom collapsed, expanded, and heads-up `RemoteViews` (`Copy OTP`, `Archive`, `Assign Category`, `Delete`), overcoming Android SystemUI's 3-action cap cleanly without duplicate standard notification actions.
- **3-Button Standard Actions & App Lock Redaction:** Non-OTP and App-Locked notifications provide 3 standard actions (`Archive`, `Assign Category`, `Delete`). When **App Lock** is enabled, sender and OTP/message previews are fully redacted and `Copy OTP` is omitted so verification codes cannot be exfiltrated while locked.
- **Clipboard Privacy, Background Actions & Trampoline-Compliant Category Routing:**
  - `Copy OTP` sets `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+ (API 33+) to mask OTPs in system clipboard overlays (with a toast confirmation on Android 7–12).
  - `Archive` and `Delete` execute asynchronously in the background via non-exported `SmsNotificationActionReceiver` (`goAsync()`) and immediately dismiss the active notification.
  - `Assign Category` launches `MainActivity` directly via `PendingIntent.getActivity` (Android 12+ / `targetSdk 36` notification-trampoline compliant) to open `MoveSenderCategoryBottomSheet` — strictly gated behind `AppLockGateScreen` when App Lock is enabled.
- **Long-Press SMS Copy & Forward:** Long-pressing any message bubble in an open conversation (`ThreadDetailScreen`) triggers haptic feedback and opens a Material 3 **Message Options** sheet with **Copy** (copies full SMS text to the clipboard with snackbar confirmation) and **Forward** (pre-fills the message body in **New Message** so you can pick a contact or enter a number), while preserving one-tap **Copy OTP** chips inside OTP bubbles.
- **Customizable SMS Font Size (`Default`, `Large`, `Larger`) & Bold Text:** Added **Settings → SMS Text Appearance** with `Default` (`15sp` / `14sp`), `Large` (`18sp` / `16.5sp`), and `Larger` (`21sp` / `19sp`) font size presets, a **Bold SMS text** toggle, and a live sample preview — persisted in Tink-encrypted DataStore and applied across open conversation bubbles, reply/compose inputs, and inbox/archive previews.
- **Expanded Robolectric 4.16.1 Verification Suite:** Added `OtpAutoDeleteRobolectricTest` and `SmsNotificationActionsRobolectricTest` covering surgical OTP auto-cleanup safety across all 8 categories and archived OTPs, in-tab and Settings preset bars, 4-button OTP custom views, 3-button non-OTP and App-Locked redaction, negative `hashCode()` thread notification cancellation, sensitive clipboard flags, background archive/delete database updates, and `MainActivity` `MoveSenderCategoryBottomSheet` + App Lock gating flows.

### Previous Highlights (v1.0.2 & v1.0.1)

- **New Message Keyboard Inset Fix & Smarter Contact Search (`v1.0.2`):** Eliminated double-applied `WindowInsets.ime` and `statusBars` insets across `MainActivity`, `ComposeSmsScreen`, and `ThreadDetailScreen` on Pixel / Android 15–16, and added digit-normalized formatted phone matching in `ContactLookupCache`, recipient clear/auto-focus UX, and direct-send rows for unsaved numbers.
- **Pixel / Android 15+ Restricted Settings Recovery & Unified Versioning (`v1.0.1`):** Added built-in detection and one-tap **Open App Info** / **Open Default Apps** recovery shortcuts when Android blocks Default SMS role assignment on sideloaded APKs, alongside centralized `version.properties` versioning across Gradle, **Settings → About TRAISms**, APK filenames, and GitHub Releases.

---

## Core Highlights

### 1. 100% Offline Privacy Guarantee
- Explicitly strips `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE` via `tools:node="remove"` in `AndroidManifest.xml`.
- Blocks unencrypted OS cloud backups and ADB device-transfer extraction (`android:allowBackup="false"`, `android:fullBackupContent="false"`, hardened `data_extraction_rules.xml`).
- Zero telemetry, zero analytics SDKs, and zero sensitive Logcat output.

### 2. Exhaustive 8-Category Inbox (TRAI DLT + Multi-Country Local ML)
Every incoming and imported SMS is evaluated completely offline in strict waterfall order:
- **Layer 0 — Custom Sender Rules:** Instant routing for senders you manually assign via Swipe Right.
- **Layer 1A — Contacts vs. Raw Numbers:** Saved contacts route to **Personal**; unsaved full 7–15 digit phone numbers without alphanumeric headers route strictly to **Unknown**.
- **Layer 1B — High-Precision OTP Fast-Path:** Extracts 4–8 character verification codes (excluding currency amounts) with priority over `-T`/`-S` headers and surfaces a one-tap **Copy OTP** chip.
- **Layer 1C — India TRAI DLT Header Rules:** Parses `-P` (**Promotional**), `-S` (**Service**), `-T` (**Transactional**), `-G` (**Government**), and `127xxx` consent shortcodes.
- **Layer 2 & 3 — Local ML Classifier & Thresholding:** Runs LiteRT INT8 (`universal_en_sms_int8.tflite`) / calibrated `EmbeddedKotlinSmsModel` with regional priors for **India (`IN`)**, **United States (`US`)**, **United Kingdom (`GB`)**, **Canada (`CA`)**, **Ireland & Europe (`EU_EN`)**, and **Universal English (`GLOBAL_EN`)**. Predictions below `0.60` confidence route safely to **Other**.

### 3. Fast Material 3 Light UI & Smart Controls
- **Strict High-Contrast Light Mode:** Crisp `Plus Jakarta Sans` + `JetBrains Mono` typography and indexed Room queries for instant scrolling.
- **Swipe Gestures:** Swipe Left to **Archive** (or **Delete**) with Undo; Swipe Right to open **Move Sender Category** and save permanent sender rules.
- **Targeted Promotional & OTP Auto-Delete:** Independent periodic 12-hour offline `WorkManager` cleanup schedules strictly restricted to unarchived **Promotional** (`7` days default) and unarchived **OTP** (`Off` default) messages (`Off`, `1`, `3`, `7`, `15`, or `30` days), configurable directly from in-tab preset bars and **Settings**.
- **Bulk Hygiene:** Per-tab *Delete all in [Tab]* action and two-step *Delete All Messages in App* guarded by `DELETE` confirmation.

### 4. Hardware-Backed Encryption & Google Drive SAF Backup
- **Database at Rest:** Encrypted with 256-bit **SQLCipher** (`net.zetetic:sqlcipher-android`) using a random 32-byte key wrapped by **Google Tink** (`AndroidKeysetManager` AES-256-GCM in Android Hardware Keystore).
- **Preferences at Rest:** Encrypted Jetpack DataStore via Tink AEAD serializer (zero deprecated `EncryptedSharedPreferences`).
- **Offline-Safe Google Drive Backup:** Uses Android's Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) to write GZIP + `AES/GCM/NoPadding` (`PBKDF2WithHmacSHA256`, 600,000 iterations) `.trai` archives directly through the Google Drive app without network permissions in TRAISms.
- **Runtime Protections:** `FLAG_SECURE` screenshot blocking enabled by default on physical release builds and optional `BiometricPrompt` App Lock.
