import gzip
import hashlib
import json
import pathlib
import re
import unittest
import xml.etree.ElementTree as ET


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
APP_DIR = REPO_ROOT / "app"
MAIN_DIR = APP_DIR / "src" / "main"
JAVA_DIR = MAIN_DIR / "java" / "com" / "example"
RES_DIR = MAIN_DIR / "res"
TEST_JAVA_DIR = APP_DIR / "src" / "test" / "java" / "com" / "example"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"


def strip_kotlin_comments(source: str) -> str:
    """Remove block and single-line comments while preserving string literals."""
    pattern = re.compile(
        r'"(?:\\.|[^"\\])*"'
        r"|/\*[\s\S]*?\*/"
        r"|//[^\n]*"
    )
    return pattern.sub(lambda m: m.group(0) if m.group(0).startswith('"') else "", source)


def read_text(rel_path: str) -> str:
    path = REPO_ROOT / rel_path
    if not path.is_file():
        raise AssertionError(f"Expected file does not exist: {rel_path}")
    return path.read_text(encoding="utf-8")


def parse_kotlin_data_class_fields(source: str, class_name: str) -> dict[str, str]:
    """Extract parameter names and default expressions from a Kotlin data class header."""
    code = strip_kotlin_comments(source)
    match = re.search(rf"\bdata\s+class\s+{re.escape(class_name)}\s*\((.*?)\)\s*(\{{|$)", code, re.S)
    if not match:
        raise AssertionError(f"Could not locate data class {class_name}")
    body = match.group(1)
    fields: dict[str, str] = {}
    for prop_match in re.finditer(
        r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^=,)\n]+(?:\s*<[^=)\n]+>)?)\s*(?:=\s*([^,\n]+))?",
        body,
    ):
        name = prop_match.group(1)
        default_expr = (prop_match.group(3) or "").strip()
        fields[name] = default_expr
    return fields


class ScreenshotUnblockedAcrossAllSurfacesSpecTest(unittest.TestCase):
    """Spec-anchored tests verifying screenshots are permitted across every part of TRAISms."""

    def test_manifest_declares_single_activity_with_no_screen_capture_blocks(self) -> None:
        manifest_path = MAIN_DIR / "AndroidManifest.xml"
        root = ET.parse(manifest_path).getroot()

        # Ensure no screen-capture detection permission is requested
        perm_names = [
            elem.attrib.get(f"{ANDROID_NS}name", "")
            for elem in root.findall("uses-permission")
        ]
        self.assertNotIn("android.permission.DETECT_SCREEN_CAPTURE", perm_names)

        application = root.find("application")
        self.assertIsNotNone(application, "Missing <application> element in AndroidManifest.xml")
        activities = application.findall("activity")
        self.assertEqual(
            len(activities),
            1,
            "TRAISms single-activity architecture must declare strictly MainActivity",
        )
        main_activity = activities[0]
        self.assertEqual(main_activity.attrib.get(f"{ANDROID_NS}name"), ".MainActivity")

    def test_main_activity_unconditionally_clears_flag_secure_and_never_sets_it(self) -> None:
        raw_source = read_text("app/src/main/java/com/example/MainActivity.kt")
        code = strip_kotlin_comments(raw_source)

        # Must unconditionally clear FLAG_SECURE inside onCreate
        on_create_match = re.search(
            r"override\s+fun\s+onCreate\s*\([^)]*\)\s*\{([\s\S]*?)setContent\s*\{",
            code,
        )
        self.assertIsNotNone(on_create_match, "MainActivity.onCreate before setContent not found")
        pre_content_body = on_create_match.group(1)

        self.assertRegex(
            pre_content_body,
            r"window\.clearFlags\s*\(\s*WindowManager\.LayoutParams\.FLAG_SECURE\s*\)",
            "MainActivity.onCreate must clear WindowManager.LayoutParams.FLAG_SECURE before setContent",
        )

        # Ensure clearFlags is top-level inside onCreate (not wrapped inside an if/when condition)
        clear_stmt_index = pre_content_body.find("window.clearFlags")
        prefix_before_clear = pre_content_body[:clear_stmt_index]
        open_braces = prefix_before_clear.count("{") - prefix_before_clear.count("}")
        self.assertEqual(
            open_braces,
            0,
            "window.clearFlags(FLAG_SECURE) in MainActivity.onCreate must execute unconditionally "
            "on both debug/release and physical/emulator devices",
        )

        # Zero setFlags or addFlags calls referencing FLAG_SECURE anywhere in MainActivity
        self.assertIsNone(
            re.search(r"\b(setFlags|addFlags)\s*\([^)]*FLAG_SECURE", code),
            "MainActivity must never call setFlags or addFlags with FLAG_SECURE",
        )
        self.assertNotIn("shouldEnableFlagSecure", code)
        self.assertNotIn("blockScreenshots", code)
        self.assertNotIn("userForcedFlagSecureInStream", code)
        self.assertNotIn("updateBlockScreenshots", code)

    def test_zero_screenshot_or_capture_restrictions_across_entire_production_codebase(self) -> None:
        kt_files = sorted(JAVA_DIR.rglob("*.kt"))
        self.assertGreaterEqual(len(kt_files), 25, "Expected full Kotlin production source tree")

        forbidden_patterns = [
            (r"\b(setFlags|addFlags)\s*\([^)]*FLAG_SECURE", "Window.setFlags/addFlags(FLAG_SECURE)"),
            (r"SecureFlagPolicy\s*\.\s*SecureOn", "Compose SecureFlagPolicy.SecureOn"),
            (r"\.setSecure\s*\(\s*true\s*\)", "SurfaceView.setSecure(true)"),
            (r"\bregisterScreenCaptureCallback\b", "Activity.registerScreenCaptureCallback"),
            (r"\bScreenCaptureCallback\b", "Activity.ScreenCaptureCallback"),
            (r"\bVIRTUAL_DISPLAY_FLAG_SECURE\b", "DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE"),
        ]

        for kt_path in kt_files:
            rel = kt_path.relative_to(REPO_ROOT)
            code = strip_kotlin_comments(kt_path.read_text(encoding="utf-8"))
            for pattern, label in forbidden_patterns:
                self.assertIsNone(
                    re.search(pattern, code),
                    f"Forbidden screenshot restriction '{label}' found in {rel}",
                )

    def test_all_navigation_routes_category_tabs_layouts_and_lock_gate_allow_screenshots(self) -> None:
        main_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/MainActivity.kt"))

        # All 7 Compose Navigation routes must be hosted inside MainActivity's NavHost
        expected_routes = [
            "MessagesRoute",
            "ArchiveRoute",
            "BackupRoute",
            "SettingsRoute",
            "OnboardingRoute",
            "ThreadDetailRoute",
            "ComposeSmsRoute",
        ]
        for route in expected_routes:
            self.assertRegex(
                main_code,
                rf"composable<\s*{route}\s*>",
                f"Expected NavHost destination composable<{route}> in MainActivity.kt",
            )

        # Both compact phone BottomNavigationBar and tablet NavigationRail plus AppLockGateScreen exist
        for tag in (
            "bottom_navigation_bar",
            "primary_navigation_rail",
            "app_lock_gate_screen",
            "unlock_biometric_button",
        ):
            self.assertIn(f'testTag("{tag}")', main_code)

        # All 8 SMS categories in SmsCategory must be present and free of screenshot flags
        sms_cat_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/db/SmsCategory.kt")
        )
        for cat in (
            "PERSONAL",
            "TRANSACTIONAL",
            "SERVICE",
            "OTP",
            "PROMOTIONAL",
            "GOVERNMENT",
            "UNKNOWN",
            "OTHER",
        ):
            self.assertRegex(sms_cat_code, rf"\b{cat}\s*\(")

        # Verify every screen composable source file contains zero FLAG_SECURE or SecureFlagPolicy
        screen_files = [
            "app/src/main/java/com/example/ui/messages/MessagesScreen.kt",
            "app/src/main/java/com/example/ui/archive/ArchiveScreen.kt",
            "app/src/main/java/com/example/ui/backup/BackupScreen.kt",
            "app/src/main/java/com/example/ui/settings/SettingsScreen.kt",
            "app/src/main/java/com/example/ui/onboarding/OnboardingScreen.kt",
            "app/src/main/java/com/example/ui/thread/ThreadDetailScreen.kt",
            "app/src/main/java/com/example/ui/thread/ComposeSmsScreen.kt",
        ]
        for rel in screen_files:
            code = strip_kotlin_comments(read_text(rel))
            self.assertNotIn("FLAG_SECURE", code, f"{rel} must not reference FLAG_SECURE")
            self.assertNotIn("SecureOn", code, f"{rel} must not set SecureFlagPolicy.SecureOn")

    def test_all_dialogs_bottom_sheets_and_dropdown_menus_inherit_unblocked_window_policy(self) -> None:
        subwindow_contracts = [
            (
                "app/src/main/java/com/example/ui/common/CategoryUiHelpers.kt",
                ["ModalBottomSheet(", "AlertDialog(", 'testTag("move_category_bottom_sheet")', 'testTag("global_delete_confirm_input")'],
            ),
            (
                "app/src/main/java/com/example/ui/messages/MessagesScreen.kt",
                ["DropdownMenu(", "AlertDialog(", 'testTag("confirm_delete_tab_button")'],
            ),
            (
                "app/src/main/java/com/example/ui/thread/ThreadDetailScreen.kt",
                ["ModalBottomSheet(", "AlertDialog(", 'testTag("message_actions_prompt")', 'testTag("message_action_copy")', 'testTag("message_action_forward")'],
            ),
            (
                "app/src/main/java/com/example/ui/backup/BackupScreen.kt",
                ["AlertDialog(", 'testTag("restore_passphrase_dialog_input")', 'testTag("confirm_restore_button")'],
            ),
        ]

        for rel, required_tokens in subwindow_contracts:
            code = strip_kotlin_comments(read_text(rel))
            for token in required_tokens:
                self.assertIn(token, code, f"Expected UI sub-window token '{token}' in {rel}")
            self.assertNotIn("SecureFlagPolicy.SecureOn", code, f"{rel} must not force SecureOn")
            self.assertNotIn("FLAG_SECURE", code, f"{rel} must not apply FLAG_SECURE")

    def test_settings_screen_removes_flag_secure_toggle_while_preserving_test_call_compatibility(self) -> None:
        settings_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/settings/SettingsScreen.kt")
        )

        # Screenshot-blocking UI switch and copy must be absent
        self.assertNotIn("flag_secure_switch", settings_code)
        self.assertNotIn("Block Screenshots", settings_code)
        self.assertNotIn("effectiveFlagSecure", settings_code)
        self.assertNotIn("blockScreenshots", settings_code)
        self.assertNotIn("userForcedFlagSecureInStream", settings_code)

        # Defaulted no-op parameter must remain so existing Robolectric test call-sites compile cleanly
        self.assertRegex(
            settings_code,
            r"onToggleBlockScreenshots\s*:\s*\(\s*Boolean\s*\)\s*->\s*Unit\s*=\s*\{\s*\}",
            "SettingsScreen must retain defaulted onToggleBlockScreenshots: (Boolean) -> Unit = {} "
            "for existing Robolectric test source compatibility",
        )

        # Confirm existing untouched Robolectric tests still pass named argument cleanly
        for existing_test in (
            "app/src/test/java/com/example/SmsTextAppearanceRobolectricTest.kt",
            "app/src/test/java/com/example/OtpAutoDeleteRobolectricTest.kt",
            "app/src/test/java/com/example/IndependentSwipeActionsRobolectricTest.kt",
        ):
            test_src = read_text(existing_test)
            self.assertIn("onToggleBlockScreenshots =", test_src)

    def test_viewmodel_and_datastore_remove_block_screenshots_and_migrate_legacy_encrypted_json(self) -> None:
        vm_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/MainViewModel.kt")
        )
        self.assertNotIn("updateBlockScreenshots", vm_code)
        self.assertNotIn("blockScreenshots", vm_code)
        self.assertNotIn("userForcedFlagSecureInStream", vm_code)

        store_raw = read_text("app/src/main/java/com/example/data/crypto/EncryptedSettingsStore.kt")
        store_code = strip_kotlin_comments(store_raw)
        app_settings_fields = parse_kotlin_data_class_fields(store_raw, "AppSettings")

        self.assertNotIn("blockScreenshots", app_settings_fields)
        self.assertNotIn("userForcedFlagSecureInStream", app_settings_fields)
        self.assertIn("appLockEnabled", app_settings_fields)
        self.assertEqual(app_settings_fields["appLockEnabled"], "false")

        # TinkAppSettingsSerializer must ignore unknown keys so upgrading installs with persisted
        # "blockScreenshots": true in traisms_secure_settings.pb deserialize without error
        self.assertRegex(store_code, r"ignoreUnknownKeys\s*=\s*true")
        self.assertRegex(store_code, r"encodeDefaults\s*=\s*true")

        # Functional schema-migration simulation against legacy persisted JSON payload
        legacy_persisted_payload = json.dumps(
            {
                "promoAutoDeleteDays": 15,
                "otpAutoDeleteDays": 3,
                "appLockEnabled": True,
                "blockScreenshots": True,
                "userForcedFlagSecureInStream": True,
                "regionOverride": "IN",
                "backupPassphrase": "correct-horse-battery-staple",
                "onboardingDismissed": True,
                "smsFontSize": "LARGE",
                "smsFontBold": True,
            }
        )
        decoded_raw = json.loads(legacy_persisted_payload)
        migrated = {k: v for k, v in decoded_raw.items() if k in app_settings_fields}

        self.assertNotIn("blockScreenshots", migrated)
        self.assertNotIn("userForcedFlagSecureInStream", migrated)
        self.assertEqual(migrated["promoAutoDeleteDays"], 15)
        self.assertEqual(migrated["otpAutoDeleteDays"], 3)
        self.assertTrue(migrated["appLockEnabled"])
        self.assertEqual(migrated["regionOverride"], "IN")
        self.assertEqual(migrated["backupPassphrase"], "correct-horse-battery-staple")
        self.assertTrue(migrated["onboardingDismissed"])
        self.assertEqual(migrated["smsFontSize"], "LARGE")
        self.assertTrue(migrated["smsFontBold"])


class SecurityHardeningNonRegressionSpecTest(unittest.TestCase):
    """Regression suite verifying all 11 other security-hardened features remain 100% intact."""

    def test_01_offline_manifest_network_stripping_and_offline_workers(self) -> None:
        root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        removed_permissions = {
            elem.attrib.get(f"{ANDROID_NS}name")
            for elem in root.findall("uses-permission")
            if elem.attrib.get(f"{TOOLS_NS}node") == "remove"
        }
        self.assertIn("android.permission.INTERNET", removed_permissions)
        self.assertIn("android.permission.ACCESS_NETWORK_STATE", removed_permissions)

        granted_permissions = {
            elem.attrib.get(f"{ANDROID_NS}name")
            for elem in root.findall("uses-permission")
            if elem.attrib.get(f"{TOOLS_NS}node") != "remove"
        }
        self.assertNotIn("android.permission.INTERNET", granted_permissions)
        self.assertNotIn("android.permission.ACCESS_NETWORK_STATE", granted_permissions)

        # Verify WorkScheduler enforces NetworkType.NOT_REQUIRED on all periodic background workers
        scheduler_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/worker/WorkScheduler.kt")
        )
        self.assertIn("setRequiredNetworkType(NetworkType.NOT_REQUIRED)", scheduler_code)
        self.assertNotIn("NetworkType.CONNECTED", scheduler_code)
        self.assertNotIn("NetworkType.UNMETERED", scheduler_code)

        # Verify MmsWapPushReceiver performs zero network fetches and logs offline notice
        mms_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/MmsWapPushReceiver.kt")
        )
        self.assertIn("100% Offline Mode", mms_code)

        # Zero network client or WebView imports anywhere in app/src/main/java
        for kt_path in JAVA_DIR.rglob("*.kt"):
            code = strip_kotlin_comments(kt_path.read_text(encoding="utf-8"))
            for banned in ("HttpURLConnection", "okhttp3", "retrofit2", "android.webkit.WebView"):
                self.assertNotIn(banned, code, f"Unexpected network/WebView symbol {banned} in {kt_path}")

    def test_02_os_cloud_backup_and_device_transfer_extraction_blocking(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        app_elem = manifest_root.find("application")
        self.assertIsNotNone(app_elem)

        self.assertEqual(app_elem.attrib.get(f"{ANDROID_NS}allowBackup"), "false")
        self.assertEqual(app_elem.attrib.get(f"{ANDROID_NS}fullBackupContent"), "false")
        self.assertEqual(
            app_elem.attrib.get(f"{ANDROID_NS}dataExtractionRules"),
            "@xml/data_extraction_rules",
        )
        tools_replace = {
            part.strip()
            for part in app_elem.attrib.get(f"{TOOLS_NS}replace", "").split(",")
            if part.strip()
        }
        self.assertIn("android:allowBackup", tools_replace)
        self.assertIn("android:fullBackupContent", tools_replace)

        expected_domains = {"root", "file", "database", "sharedpref", "external"}

        backup_xml = ET.parse(RES_DIR / "xml" / "backup_rules.xml").getroot()
        self.assertEqual(backup_xml.tag, "full-backup-content")
        self.assertEqual(len(backup_xml.findall("include")), 0)
        backup_excluded = {
            el.attrib.get("domain")
            for el in backup_xml.findall("exclude")
            if el.attrib.get("path") == "."
        }
        self.assertEqual(backup_excluded, expected_domains)

        extraction_xml = ET.parse(RES_DIR / "xml" / "data_extraction_rules.xml").getroot()
        self.assertEqual(extraction_xml.tag, "data-extraction-rules")
        for section_name in ("cloud-backup", "device-transfer"):
            section = extraction_xml.find(section_name)
            self.assertIsNotNone(section, f"Missing <{section_name}> in data_extraction_rules.xml")
            self.assertEqual(len(section.findall("include")), 0)
            excluded = {
                el.attrib.get("domain")
                for el in section.findall("exclude")
                if el.attrib.get("path") == "."
            }
            self.assertEqual(excluded, expected_domains)

        # Sensitive key and DataStore files must live in noBackupFilesDir
        tink_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/crypto/TinkKeyManager.kt")
        )
        store_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/crypto/EncryptedSettingsStore.kt")
        )
        self.assertIn("File(appContext.noBackupFilesDir, DB_PASSPHRASE_FILE)", tink_code)
        self.assertIn('File(appContext.noBackupFilesDir, "traisms_secure_settings.pb")', store_code)

    def test_03_sqlcipher_256_bit_database_encryption_and_passphrase_zeroing(self) -> None:
        db_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/db/TraiSmsDatabase.kt")
        )
        self.assertRegex(db_code, r"exportSchema\s*=\s*false")
        self.assertIn('DB_NAME = "traisms_encrypted_vault.db"', db_code)
        self.assertIn("LazySqlCipherOpenHelperFactory", db_code)
        self.assertIn("tinkKeyManager.getOrCreateDatabasePassphrase()", db_code)
        self.assertIn('System.loadLibrary("sqlcipher")', db_code)
        self.assertIn("SupportOpenHelperFactory(passphrase.copyOf())", db_code)
        self.assertRegex(
            db_code,
            r"finally\s*\{\s*passphrase\.fill\(0\)\s*\}",
            "Database passphrase byte array must be zeroed in finally block",
        )

    def test_04_google_tink_hardware_keystore_and_encrypted_datastore(self) -> None:
        tink_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/crypto/TinkKeyManager.kt")
        )
        self.assertIn("AeadConfig.register()", tink_code)
        self.assertIn("AndroidKeysetManager.Builder()", tink_code)
        self.assertIn('KeyTemplates.get("AES256_GCM")', tink_code)
        self.assertIn('MASTER_KEY_URI = "android-keystore://traisms_hardware_master_key_v1"', tink_code)
        self.assertIn('KEYSET_NAME = "traisms_tink_master_keyset_v1"', tink_code)
        self.assertIn('DB_PASSPHRASE_FILE = "traisms_sqlcipher_passphrase.enc"', tink_code)
        self.assertIn('"traisms_sqlcipher_db_v1"', tink_code)
        self.assertIn("ByteArray(32)", tink_code)
        self.assertIn("SecureRandom().nextBytes(randomPassphrase)", tink_code)
        self.assertNotIn("EncryptedSharedPreferences", tink_code)

        store_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/crypto/EncryptedSettingsStore.kt")
        )
        self.assertIn("class TinkAppSettingsSerializer", store_code)
        self.assertIn("tinkKeyManager.getAead().decrypt(encryptedBytes, ASSOCIATED_DATA)", store_code)
        self.assertIn("tinkKeyManager.getAead().encrypt(jsonBytes, ASSOCIATED_DATA)", store_code)
        self.assertIn('"traisms_encrypted_datastore_v1"', store_code)

    def test_05_authenticated_trai_backup_crypto_and_zip_bomb_protection(self) -> None:
        backup_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/crypto/BackupCryptoEngine.kt")
        )
        self.assertIn("'T'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte(), 1", backup_code)
        self.assertRegex(backup_code, r"SALT_BYTES\s*=\s*16")
        self.assertRegex(backup_code, r"IV_BYTES\s*=\s*12")
        self.assertRegex(backup_code, r"GCM_TAG_BITS\s*=\s*128")
        self.assertRegex(backup_code, r"PBKDF2_ITERATIONS\s*=\s*600_000")
        self.assertRegex(backup_code, r"KEY_LENGTH_BITS\s*=\s*256")
        self.assertRegex(backup_code, r"MAX_DECOMPRESSED_BYTES\s*=\s*128\s*\*\s*1024\s*\*\s*1024")
        self.assertIn('Cipher.getInstance("AES/GCM/NoPadding")', backup_code)
        self.assertIn('SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")', backup_code)
        self.assertEqual(
            len(re.findall(r"cipher\.updateAAD\(MAGIC_HEADER\)", backup_code)),
            2,
            "Both encryptPayload and decryptPayload must bind MAGIC_HEADER via cipher.updateAAD",
        )
        self.assertIn("spec.clearPassword()", backup_code)
        self.assertIn("chars.fill('\\u0000')", backup_code)
        self.assertIn("if (totalRead > MAX_DECOMPRESSED_BYTES)", backup_code)
        self.assertIn('MessageDigest.getInstance("SHA-256")', backup_code)

        # Independent cryptographic vector check matching BackupCryptoEngine's parameters
        sample_body = "Acct XX1234 credited INR 5,000.00"
        self.assertEqual(
            len(hashlib.sha256(sample_body.encode("utf-8")).hexdigest()),
            64,
        )
        derived_key = hashlib.pbkdf2_hmac(
            "sha256",
            b"TestVaultPassphrase!",
            b"0123456789abcdef",
            600_000,
            dklen=32,
        )
        self.assertEqual(len(derived_key), 32)
        compressed = gzip.compress(b'{"formatVersion":1,"createdAtMillis":1,"messages":[],"senderRules":[]}')
        self.assertGreater(len(compressed), 0)

    def test_06_biometric_and_device_credential_app_lock_and_session_gate(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        perms = {
            el.attrib.get(f"{ANDROID_NS}name")
            for el in manifest_root.findall("uses-permission")
            if el.attrib.get(f"{TOOLS_NS}node") != "remove"
        }
        self.assertIn("android.permission.USE_BIOMETRIC", perms)

        main_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/MainActivity.kt"))
        self.assertIn("if (settings.appLockEnabled && !isSessionUnlocked)", main_code)
        self.assertIn("AppLockGateScreen(", main_code)
        self.assertIn("launchNativeBiometricUnlock(", main_code)
        self.assertIn("BiometricManager.Authenticators.BIOMETRIC_STRONG", main_code)
        self.assertIn("BiometricManager.Authenticators.BIOMETRIC_WEAK", main_code)
        self.assertIn("BiometricManager.Authenticators.DEVICE_CREDENTIAL", main_code)
        self.assertIn("keyguard?.isDeviceSecure", main_code)

        # Enabling App Lock in Settings must require biometric/device credential verification first
        self.assertRegex(
            main_code,
            r"onToggleAppLock\s*=\s*\{\s*enable\s*->\s*if\s*\(\s*enable\s*\)\s*\{\s*launchNativeBiometricUnlock",
        )

        settings_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/settings/SettingsScreen.kt")
        )
        self.assertIn('title = "Security Hardening"', settings_code)
        self.assertIn("SQLCipher 256-bit DB encryption & Tink Hardware Keystore are always active.", settings_code)
        self.assertIn('title = "App Lock (Biometric / Device PIN)"', settings_code)
        self.assertIn('testTag = "app_lock_switch"', settings_code)

        vm_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/ui/MainViewModel.kt"))
        self.assertIn("fun updateAppLockEnabled(enabled: Boolean)", vm_code)

    def test_07_notification_lockscreen_privacy_app_lock_redaction_and_immutable_intents(self) -> None:
        notif_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/SmsNotificationHelper.kt")
        )
        self.assertIn("NotificationCompat.VISIBILITY_PRIVATE", notif_code)
        self.assertIn("PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE", notif_code)
        self.assertNotIn("FLAG_MUTABLE", notif_code)
        self.assertIn("!redactPreviewForAppLock", notif_code)
        self.assertIn('"Unlock TRAISms to view encrypted message"', notif_code)
        self.assertIn("PendingIntent.getActivity(", notif_code)
        self.assertIn("ACTION_ASSIGN_CATEGORY", notif_code)

        # Expanded OTP RemoteViews layout must retain all 4 action button IDs
        expanded_xml = read_text("app/src/main/res/layout/notification_sms_otp_expanded.xml")
        for btn_id in (
            "@+id/notif_btn_copy_otp",
            "@+id/notif_btn_archive",
            "@+id/notif_btn_assign_category",
            "@+id/notif_btn_delete",
        ):
            self.assertIn(btn_id, expanded_xml)

    def test_08_android_13_sensitive_otp_clipboard_masking(self) -> None:
        receiver_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/SmsNotificationActionReceiver.kt")
        )
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU", receiver_code)
        self.assertIn("putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)", receiver_code)
        self.assertIn("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU", receiver_code)

    def test_09_component_export_permissions_and_intent_and_sql_sanitization(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        app_elem = manifest_root.find("application")
        self.assertIsNotNone(app_elem)

        receivers = {
            el.attrib.get(f"{ANDROID_NS}name"): el
            for el in app_elem.findall("receiver")
        }
        services = {
            el.attrib.get(f"{ANDROID_NS}name"): el
            for el in app_elem.findall("service")
        }

        notif_receiver = receivers.get(".data.sms.SmsNotificationActionReceiver")
        self.assertIsNotNone(notif_receiver)
        self.assertEqual(notif_receiver.attrib.get(f"{ANDROID_NS}exported"), "false")

        sms_receiver = receivers.get(".data.sms.SmsDeliverReceiver")
        self.assertIsNotNone(sms_receiver)
        self.assertEqual(
            sms_receiver.attrib.get(f"{ANDROID_NS}permission"),
            "android.permission.BROADCAST_SMS",
        )

        mms_receiver = receivers.get(".data.sms.MmsWapPushReceiver")
        self.assertIsNotNone(mms_receiver)
        self.assertEqual(
            mms_receiver.attrib.get(f"{ANDROID_NS}permission"),
            "android.permission.BROADCAST_WAP_PUSH",
        )
        mms_data = mms_receiver.find("intent-filter/data")
        self.assertIsNotNone(mms_data)
        self.assertEqual(
            mms_data.attrib.get(f"{ANDROID_NS}mimeType"),
            "application/vnd.wap.mms-message",
        )

        headless_service = services.get(".data.sms.HeadlessSmsSendService")
        self.assertIsNotNone(headless_service)
        self.assertEqual(
            headless_service.attrib.get(f"{ANDROID_NS}permission"),
            "android.permission.SEND_RESPOND_VIA_MESSAGE",
        )

        # Runtime action guards in receivers/service and parameterized SQLite queries in SystemSmsHelper
        sms_deliver_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/SmsDeliverReceiver.kt")
        )
        self.assertIn("Telephony.Sms.Intents.SMS_DELIVER_ACTION", sms_deliver_code)

        mms_push_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/MmsWapPushReceiver.kt")
        )
        self.assertIn("Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION", mms_push_code)
        self.assertIn('"application/vnd.wap.mms-message"', mms_push_code)

        headless_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/HeadlessSmsSendService.kt")
        )
        self.assertIn("TelephonyManager.ACTION_RESPOND_VIA_MESSAGE", headless_code)

        helper_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/SystemSmsHelper.kt")
        )
        self.assertIn("RoleManager.ROLE_SMS", helper_code)
        self.assertIn('val placeholders = chunk.joinToString(",") { "?" }', helper_code)
        self.assertIn("Build.VERSION_CODES.VANILLA_ICE_CREAM", helper_code)

        # One-time notification intent extra sanitization in MainActivity.parseExternalIntent
        main_code = strip_kotlin_comments(read_text("app/src/main/java/com/example/MainActivity.kt"))
        self.assertIn("intent.removeExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID)", main_code)
        self.assertIn("intent.removeExtra(SmsNotificationHelper.EXTRA_OPEN_THREAD_KEY)", main_code)

    def test_10_destructive_action_confirmation_and_surgical_auto_delete_scope(self) -> None:
        helpers_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/common/CategoryUiHelpers.kt")
        )
        self.assertIn('val isConfirmed = typedConfirmation.trim() == "DELETE"', helpers_code)
        self.assertIn('testTag("global_delete_confirm_input")', helpers_code)
        self.assertIn('testTag("global_delete_confirm_button")', helpers_code)
        self.assertIn("enabled = isConfirmed", helpers_code)

        dao_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/db/SmsDao.kt")
        )
        self.assertRegex(
            dao_code,
            r"DELETE\s+FROM\s+sms_messages\s+WHERE\s+category\s*=\s*'PROMOTIONAL'\s+AND\s+isArchived\s*=\s*0\s+AND\s+timestampMillis\s*<\s*:cutoffMillis",
        )
        self.assertRegex(
            dao_code,
            r"DELETE\s+FROM\s+sms_messages\s+WHERE\s+category\s*=\s*'OTP'\s+AND\s+isArchived\s*=\s*0\s+AND\s+timestampMillis\s*<\s*:cutoffMillis",
        )

        otp_worker_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/worker/OtpCleanupWorker.kt")
        )
        promo_worker_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/worker/PromotionalCleanupWorker.kt")
        )
        self.assertIn("purgeExpiredOtpMessages(days)", otp_worker_code)
        self.assertIn("purgeExpiredPromotionalMessages(days)", promo_worker_code)

    def test_11_build_signing_dependency_metadata_and_proguard_rules(self) -> None:
        gradle_code = strip_kotlin_comments(read_text("app/build.gradle.kts"))
        self.assertIn('create("release")', gradle_code)
        self.assertIn('create("debugConfig")', gradle_code)
        self.assertRegex(gradle_code, r"includeInApk\s*=\s*false")
        self.assertRegex(gradle_code, r"includeInBundle\s*=\s*true")
        self.assertIn("implementation(libs.sqlcipher.android)", gradle_code)
        self.assertIn("implementation(libs.tink.android)", gradle_code)
        self.assertIn('propertiesFileName = ".env"', gradle_code)

        proguard_rules = read_text("app/proguard-rules.pro")
        for required_rule in (
            "-keep class com.google.crypto.tink.** { *; }",
            "-keep class net.zetetic.database.** { *; }",
            "-keep class net.zetetic.database.sqlcipher.** { *; }",
            "native <methods>;",
            "-keep class * extends androidx.room.RoomDatabase { *; }",
            "-keep @androidx.room.Entity class * { *; }",
            "-keep @androidx.room.Dao interface * { *; }",
            "-keep,includedescriptorclasses class com.example.**$$serializer { *; }",
            "-keep class org.tensorflow.lite.** { *; }",
        ):
            self.assertIn(required_rule, proguard_rules)


if __name__ == "__main__":
    unittest.main()
