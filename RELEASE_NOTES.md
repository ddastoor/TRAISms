# TRAISms v1.0.1 — Android 15/16 Restricted Settings Recovery & Release Hardening

**Package ID:** `com.traisms.app`  
**Minimum Android Version:** Android 7.0 (API 24) • **Target SDK:** Android 16 (API 36)  
**Network Permissions:** **0 (`INTERNET` & `ACCESS_NETWORK_STATE` stripped at Manifest merge)**

---

## Download & Install

Download **`TRAISms-v1.0.1-release.apk`** from the **Assets** section below:

1. Verify the file's SHA-256 checksum against `SHA256SUMS.txt`:
   ```bash
   sha256sum -c SHA256SUMS.txt
   ```
2. Transfer `TRAISms-v1.0.1-release.apk` to your Android phone and open it to install.
3. Launch **TRAISms** and tap **Set TRAISms as Default SMS App** to import and encrypt your existing SMS inbox on-device.

### Installing on Android 15 / 16 (Google Pixel & Sideloaded APKs)

On Android 15 and 16 (including Google Pixel devices), Android's **Enhanced Confirmation Mode** restricts APKs sideloaded outside the Play Store from becoming the Default SMS App, displaying *"App was denied access to be default SMS app ... restricted permissions"*. This is an OS sideloading safeguard — TRAISms meets all Android 14–16 `ROLE_SMS` requirements while maintaining zero network permissions.

To unlock Default SMS access in 3 steps:

1. **Open TRAISms App info** — Tap **Open App Info** inside TRAISms (or go to **Settings → Apps → TRAISms**).
2. **Allow restricted settings** — Tap the **⋮** (top-right) menu → **Allow restricted settings**, then verify with your PIN, pattern, or fingerprint.
3. **Set TRAISms as Default SMS App** — Return to **TRAISms** and tap **Set TRAISms as Default SMS App** (or choose **TRAISms** under **Settings → Apps → Default apps → SMS app**).

> **Tip:** On Android 13–16, the **⋮ → Allow restricted settings** menu item in App info often appears **only after** you first tap **Set TRAISms as Default SMS App** once and dismiss Android's blocked dialog.

---

## What's New in v1.0.1

- **Pixel / Android 15+ Restricted Settings In-App Recovery:** Added built-in detection and recovery guidance in both **Onboarding** and **Settings** when Android blocks the Default SMS App role on sideloaded installs, including step-by-step instructions, one-tap **Open App Info**, and **Open Default Apps** shortcuts.
- **Unified Versioning & Settings About Display:** Centralized `VERSION_NAME` (`1.0.1`) and `VERSION_CODE` (`2`) in `version.properties`, driving Gradle builds, the in-app **Settings → About TRAISms** version card, APK filenames, and GitHub Release tags in lockstep.

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
- **Targeted Promotional Auto-Delete:** Periodic 12-hour offline `WorkManager` cleanup strictly restricted to unarchived **Promotional** messages (`Off`, `1`, `3`, `7` [Default], `15`, or `30` days).
- **Bulk Hygiene:** Per-tab *Delete all in [Tab]* action and two-step *Delete All Messages in App* guarded by `DELETE` confirmation.

### 4. Hardware-Backed Encryption & Google Drive SAF Backup
- **Database at Rest:** Encrypted with 256-bit **SQLCipher** (`net.zetetic:sqlcipher-android`) using a random 32-byte key wrapped by **Google Tink** (`AndroidKeysetManager` AES-256-GCM in Android Hardware Keystore).
- **Preferences at Rest:** Encrypted Jetpack DataStore via Tink AEAD serializer (zero deprecated `EncryptedSharedPreferences`).
- **Offline-Safe Google Drive Backup:** Uses Android's Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) to write GZIP + `AES/GCM/NoPadding` (`PBKDF2WithHmacSHA256`, 600,000 iterations) `.trai` archives directly through the Google Drive app without network permissions in TRAISms.
- **Runtime Protections:** `FLAG_SECURE` screenshot blocking enabled by default on physical release builds and optional `BiometricPrompt` App Lock.
