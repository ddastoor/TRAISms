import pathlib
import re
import sqlite3
import unittest
import xml.etree.ElementTree as ET


REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
APP_DIR = REPO_ROOT / "app"
MAIN_DIR = APP_DIR / "src" / "main"
JAVA_DIR = MAIN_DIR / "java" / "com" / "example"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"


def strip_kotlin_comments(source: str) -> str:
    """Remove Kotlin block and line comments while preserving single and triple-quoted strings."""
    pattern = re.compile(
        r'"""[\s\S]*?"""'
        r'|"(?:\\.|[^"\\])*"'
        r"|/\*[\s\S]*?\*/"
        r"|//[^\n]*"
    )
    return pattern.sub(
        lambda m: m.group(0) if m.group(0).startswith('"') else "",
        source,
    )


def read_text(rel_path: str) -> str:
    path = REPO_ROOT / rel_path
    if not path.is_file():
        raise AssertionError(f"Expected file does not exist: {rel_path}")
    return path.read_text(encoding="utf-8")


def extract_dao_queries() -> dict[str, str]:
    """Extract exact @Query SQL strings keyed by SmsDao method name."""
    dao_code = strip_kotlin_comments(
        read_text("app/src/main/java/com/example/data/db/SmsDao.kt")
    )
    pattern = re.compile(
        r'@Query\s*\(\s*(?:"""([\s\S]*?)"""|"([^"]*)")\s*\)\s*'
        r"(?:suspend\s+)?fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(",
        re.S,
    )
    queries: dict[str, str] = {}
    for match in pattern.finditer(dao_code):
        sql = match.group(1) if match.group(1) is not None else match.group(2)
        fn_name = match.group(3)
        queries[fn_name] = sql.strip()
    return queries


def create_in_memory_room_v2_db() -> sqlite3.Connection:
    """Create an in-memory SQLite database matching Room v2 sms_messages + mms_attachments."""
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE sms_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            systemSmsId INTEGER,
            threadKey TEXT NOT NULL,
            rawAddress TEXT NOT NULL,
            contactName TEXT,
            body TEXT NOT NULL,
            bodySha256 TEXT NOT NULL,
            timestampMillis INTEGER NOT NULL,
            isIncoming INTEGER NOT NULL,
            isRead INTEGER NOT NULL,
            isArchived INTEGER NOT NULL,
            category TEXT NOT NULL,
            extractedOtp TEXT,
            confidenceScore REAL NOT NULL,
            classificationSource TEXT NOT NULL,
            transportType TEXT NOT NULL DEFAULT 'SMS',
            deliveryStatus TEXT NOT NULL DEFAULT 'NONE',
            systemMmsId INTEGER,
            mmsSubject TEXT,
            mmsContentLocation TEXT,
            mmsTransactionId TEXT,
            mmsSizeBytes INTEGER NOT NULL DEFAULT 0,
            groupRecipientsJson TEXT,
            replyToMessageId INTEGER,
            replyToSender TEXT,
            replyToSnippet TEXT,
            reactionsJson TEXT,
            isHiddenReaction INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE mms_attachments (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            messageId INTEGER NOT NULL,
            partIndex INTEGER NOT NULL,
            contentType TEXT NOT NULL,
            fileName TEXT,
            localFilePath TEXT,
            contentId TEXT,
            sizeBytes INTEGER NOT NULL,
            textContent TEXT
        );
        """
    )
    return conn


def insert_sms_row(
    conn: sqlite3.Connection,
    *,
    thread_key: str,
    body: str,
    timestamp_millis: int,
    category: str,
    is_incoming: int = 1,
    is_read: int = 0,
    is_archived: int = 0,
    system_sms_id: int | None = None,
    system_mms_id: int | None = None,
    transport_type: str = "SMS",
    is_hidden_reaction: int = 0,
    contact_name: str | None = None,
    extracted_otp: str | None = None,
    mms_subject: str | None = None,
) -> int:
    cur = conn.execute(
        """
        INSERT INTO sms_messages (
            systemSmsId, threadKey, rawAddress, contactName, body, bodySha256,
            timestampMillis, isIncoming, isRead, isArchived, category, extractedOtp,
            confidenceScore, classificationSource, transportType, deliveryStatus,
            systemMmsId, mmsSubject, isHiddenReaction
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1.0, 'TEST', ?, 'NONE', ?, ?, ?)
        """,
        (
            system_sms_id,
            thread_key,
            thread_key,
            contact_name,
            body,
            f"sha_{thread_key}_{timestamp_millis}_{is_incoming}_{is_hidden_reaction}",
            timestamp_millis,
            is_incoming,
            is_read,
            is_archived,
            category,
            extracted_otp,
            transport_type,
            system_mms_id,
            mms_subject,
            is_hidden_reaction,
        ),
    )
    return int(cur.lastrowid)


class SmsDaoUnreadAndCategorySqlSpecTest(unittest.TestCase):
    """Executes actual SmsDao SQL queries against an in-memory SQLite v2 DB for unread & mark-all-read edge cases."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.queries = extract_dao_queries()
        required = (
            "observeCategoryThreads",
            "observeUnreadCategoryThreads",
            "searchActiveThreads",
            "observeArchivedThreads",
            "observeCategoryCounts",
            "getUnreadSystemIdsInActiveCategory",
            "getUnreadSystemMmsIdsInActiveCategory",
            "getUnreadThreadKeysInActiveCategory",
            "markAllReadInActiveCategory",
        )
        for name in required:
            if name not in cls.queries:
                raise AssertionError(f"Missing expected @Query method in SmsDao.kt: {name}")

    def test_unread_incoming_followed_by_outgoing_reply_and_unread_flagged_outgoing_row(self) -> None:
        conn = create_in_memory_room_v2_db()
        try:
            # Thread A: unread incoming SMS followed by outgoing reply in same thread
            id_a_in = insert_sms_row(
                conn,
                thread_key="NUM_9811112222",
                body="Hey, free for coffee tonight?",
                timestamp_millis=1_000,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                system_sms_id=101,
            )
            id_a_out = insert_sms_row(
                conn,
                thread_key="NUM_9811112222",
                body="Sure, 8 PM works!",
                timestamp_millis=2_000,
                category="PERSONAL",
                is_incoming=0,
                is_read=1,
                system_sms_id=102,
            )

            # Thread B: read incoming SMS followed by outgoing row erroneously marked isRead=0
            insert_sms_row(
                conn,
                thread_key="NUM_9822223333",
                body="Earlier read message",
                timestamp_millis=1_500,
                category="PERSONAL",
                is_incoming=1,
                is_read=1,
                system_sms_id=103,
            )
            id_b_out_unread_flag = insert_sms_row(
                conn,
                thread_key="NUM_9822223333",
                body="Outgoing row with stale isRead=0",
                timestamp_millis=2_500,
                category="PERSONAL",
                is_incoming=0,
                is_read=0,
                system_sms_id=104,
            )
            conn.commit()

            counts = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(counts["PERSONAL"]["unreadCount"], 1)
            self.assertEqual(counts["PERSONAL"]["totalCount"], 4)

            unread_threads = conn.execute(
                self.queries["observeUnreadCategoryThreads"], {"category": "PERSONAL"}
            ).fetchall()
            self.assertEqual([r["threadKey"] for r in unread_threads], ["NUM_9811112222"])
            self.assertEqual(unread_threads[0]["latestMessageId"], id_a_in)
            self.assertEqual(unread_threads[0]["unreadCount"], 1)
            self.assertEqual(unread_threads[0]["messageCount"], 2)

            all_threads = conn.execute(
                self.queries["observeCategoryThreads"], {"category": "PERSONAL"}
            ).fetchall()
            self.assertEqual(
                [(r["threadKey"], r["latestMessageId"], r["unreadCount"], r["messageCount"]) for r in all_threads],
                [
                    ("NUM_9822223333", id_b_out_unread_flag, 0, 2),
                    ("NUM_9811112222", id_a_out, 1, 2),
                ],
            )

            sys_ids = [
                r[0]
                for r in conn.execute(
                    self.queries["getUnreadSystemIdsInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall()
            ]
            self.assertEqual(sys_ids, [101])

            thread_keys = [
                r[0]
                for r in conn.execute(
                    self.queries["getUnreadThreadKeysInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall()
            ]
            self.assertEqual(thread_keys, ["NUM_9811112222"])

            cur = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "PERSONAL"}
            )
            conn.commit()
            self.assertEqual(cur.rowcount, 1)

            # Outgoing row id_b_out_unread_flag remains untouched; incoming id_a_in is now read
            row_in = conn.execute("SELECT isRead FROM sms_messages WHERE id = ?", (id_a_in,)).fetchone()
            row_out = conn.execute("SELECT isRead FROM sms_messages WHERE id = ?", (id_b_out_unread_flag,)).fetchone()
            self.assertEqual(row_in["isRead"], 1)
            self.assertEqual(row_out["isRead"], 0)

            after_counts = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(after_counts["PERSONAL"]["unreadCount"], 0)
            self.assertEqual(
                conn.execute(
                    self.queries["observeUnreadCategoryThreads"], {"category": "PERSONAL"}
                ).fetchall(),
                [],
            )
        finally:
            conn.close()

    def test_hidden_reaction_rows_never_inflate_unread_counts_or_surface_threads(self) -> None:
        conn = create_in_memory_room_v2_db()
        try:
            # Thread with ONLY an unread hidden reaction row
            insert_sms_row(
                conn,
                thread_key="NUM_9844445555",
                body="Liked “Ok”",
                timestamp_millis=1_000,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                system_sms_id=201,
                system_mms_id=301,
                is_hidden_reaction=1,
            )
            # Thread with 1 read normal message + 1 unread hidden reaction row
            insert_sms_row(
                conn,
                thread_key="NUM_9866667777",
                body="Dinner at 8?",
                timestamp_millis=1_500,
                category="PERSONAL",
                is_incoming=1,
                is_read=1,
                system_sms_id=202,
                is_hidden_reaction=0,
            )
            insert_sms_row(
                conn,
                thread_key="NUM_9866667777",
                body="Loved “Dinner at 8?”",
                timestamp_millis=2_000,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                system_sms_id=203,
                is_hidden_reaction=1,
            )
            conn.commit()

            counts = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(counts["PERSONAL"]["unreadCount"], 0)
            self.assertEqual(counts["PERSONAL"]["totalCount"], 1)

            all_threads = conn.execute(
                self.queries["observeCategoryThreads"], {"category": "PERSONAL"}
            ).fetchall()
            self.assertEqual([r["threadKey"] for r in all_threads], ["NUM_9866667777"])
            self.assertEqual(all_threads[0]["messageCount"], 1)
            self.assertEqual(all_threads[0]["unreadCount"], 0)

            self.assertEqual(
                conn.execute(
                    self.queries["observeUnreadCategoryThreads"], {"category": "PERSONAL"}
                ).fetchall(),
                [],
            )
            self.assertEqual(
                conn.execute(
                    self.queries["getUnreadSystemIdsInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall(),
                [],
            )
            self.assertEqual(
                conn.execute(
                    self.queries["getUnreadSystemMmsIdsInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall(),
                [],
            )
            self.assertEqual(
                conn.execute(
                    self.queries["getUnreadThreadKeysInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall(),
                [],
            )
            cur = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "PERSONAL"}
            )
            self.assertEqual(cur.rowcount, 0)
        finally:
            conn.close()

    def test_unread_mms_messages_included_in_mark_all_read_and_mms_id_lookup(self) -> None:
        conn = create_in_memory_room_v2_db()
        try:
            mms_msg_id = insert_sms_row(
                conn,
                thread_key="GRP_FAMILY01",
                body="Trip photos!",
                timestamp_millis=3_000,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                system_sms_id=None,
                system_mms_id=5001,
                transport_type="MMS",
                mms_subject="Weekend",
            )
            conn.execute(
                "INSERT INTO mms_attachments (messageId, partIndex, contentType, fileName, sizeBytes) "
                "VALUES (?, 0, 'image/jpeg', 'beach.jpg', 2048)",
                (mms_msg_id,),
            )
            # Hidden-reaction MMS, outgoing unread-flagged MMS, and archived unread MMS must all be excluded
            insert_sms_row(
                conn,
                thread_key="GRP_FAMILY01",
                body="Loved “Trip photos!”",
                timestamp_millis=3_100,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                system_mms_id=5002,
                transport_type="MMS",
                is_hidden_reaction=1,
            )
            insert_sms_row(
                conn,
                thread_key="NUM_9811110000",
                body="Outgoing MMS",
                timestamp_millis=3_200,
                category="PERSONAL",
                is_incoming=0,
                is_read=0,
                system_mms_id=5003,
                transport_type="MMS",
            )
            insert_sms_row(
                conn,
                thread_key="NUM_9899990000",
                body="Archived MMS",
                timestamp_millis=3_300,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                is_archived=1,
                system_mms_id=5004,
                transport_type="MMS",
            )
            conn.commit()

            mms_ids = [
                r[0]
                for r in conn.execute(
                    self.queries["getUnreadSystemMmsIdsInActiveCategory"], {"category": "PERSONAL"}
                ).fetchall()
            ]
            self.assertEqual(mms_ids, [5001])

            unread_threads = conn.execute(
                self.queries["observeUnreadCategoryThreads"], {"category": "PERSONAL"}
            ).fetchall()
            self.assertEqual([r["threadKey"] for r in unread_threads], ["GRP_FAMILY01"])
            self.assertEqual(unread_threads[0]["hasAttachments"], 1)
            self.assertEqual(unread_threads[0]["transportType"], "MMS")

            cur = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "PERSONAL"}
            )
            conn.commit()
            self.assertEqual(cur.rowcount, 1)

            self.assertEqual(
                conn.execute("SELECT isRead FROM sms_messages WHERE systemMmsId = 5001").fetchone()[0],
                1,
            )
            self.assertEqual(
                conn.execute("SELECT isRead FROM sms_messages WHERE systemMmsId = 5004").fetchone()[0],
                0,
            )
        finally:
            conn.close()

    def test_archived_threads_isolated_from_active_unread_search_and_mark_all_read(self) -> None:
        conn = create_in_memory_room_v2_db()
        try:
            insert_sms_row(
                conn,
                thread_key="NUM_9800001111",
                body="ZebraActive unread message",
                timestamp_millis=4_000,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                is_archived=0,
                system_sms_id=6001,
            )
            insert_sms_row(
                conn,
                thread_key="NUM_9800002222",
                body="ZebraSecret archived unread message",
                timestamp_millis=4_500,
                category="PERSONAL",
                is_incoming=1,
                is_read=0,
                is_archived=1,
                system_sms_id=6002,
            )
            conn.commit()

            counts = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(counts["PERSONAL"]["unreadCount"], 1)
            self.assertEqual(counts["PERSONAL"]["totalCount"], 1)

            search_rows = conn.execute(
                self.queries["searchActiveThreads"], {"query": "Zebra"}
            ).fetchall()
            self.assertEqual([r["threadKey"] for r in search_rows], ["NUM_9800001111"])

            archived_rows = conn.execute(self.queries["observeArchivedThreads"]).fetchall()
            self.assertEqual([r["threadKey"] for r in archived_rows], ["NUM_9800002222"])
            self.assertEqual(archived_rows[0]["unreadCount"], 1)

            cur = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "PERSONAL"}
            )
            conn.commit()
            self.assertEqual(cur.rowcount, 1)

            self.assertEqual(
                conn.execute("SELECT isRead FROM sms_messages WHERE systemSmsId = 6001").fetchone()[0],
                1,
            )
            self.assertEqual(
                conn.execute("SELECT isRead FROM sms_messages WHERE systemSmsId = 6002").fetchone()[0],
                0,
            )
        finally:
            conn.close()

    def test_other_seven_categories_untouched_by_mark_all_read_in_active_category(self) -> None:
        all_categories = [
            "PERSONAL",
            "TRANSACTIONAL",
            "SERVICE",
            "OTP",
            "PROMOTIONAL",
            "GOVERNMENT",
            "UNKNOWN",
            "OTHER",
        ]
        conn = create_in_memory_room_v2_db()
        try:
            for idx, cat in enumerate(all_categories, start=1):
                insert_sms_row(
                    conn,
                    thread_key=f"THREAD_{cat}",
                    body=f"Unread in {cat}",
                    timestamp_millis=10_000 + idx,
                    category=cat,
                    is_incoming=1,
                    is_read=0,
                    system_sms_id=7000 + idx,
                )
            conn.commit()

            cur = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "PERSONAL"}
            )
            conn.commit()
            self.assertEqual(cur.rowcount, 1)

            counts = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(counts["PERSONAL"]["unreadCount"], 0)
            for cat in all_categories:
                if cat != "PERSONAL":
                    self.assertEqual(counts[cat]["unreadCount"], 1, f"{cat} must remain unread")

            # Mark OTP read as well; remaining 6 categories must remain untouched
            cur2 = conn.execute(
                self.queries["markAllReadInActiveCategory"], {"category": "OTP"}
            )
            conn.commit()
            self.assertEqual(cur2.rowcount, 1)

            counts2 = {
                row["category"]: dict(row)
                for row in conn.execute(self.queries["observeCategoryCounts"]).fetchall()
            }
            self.assertEqual(counts2["PERSONAL"]["unreadCount"], 0)
            self.assertEqual(counts2["OTP"]["unreadCount"], 0)
            for cat in ("TRANSACTIONAL", "SERVICE", "PROMOTIONAL", "GOVERNMENT", "UNKNOWN", "OTHER"):
                self.assertEqual(counts2[cat]["unreadCount"], 1, f"{cat} must stay unread")
        finally:
            conn.close()


class UiLayoutAndPolishContractsSpecTest(unittest.TestCase):
    """Source-level UI polish, Show-Unread sync, Mark-All-Read, and security non-regression checks."""

    def test_messages_screen_and_viewmodel_unread_and_mark_all_read_contracts(self) -> None:
        messages_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/messages/MessagesScreen.kt")
        )
        vm_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/MainViewModel.kt")
        )
        repo_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/data/sms/SmsRepository.kt")
        )

        # Exact locked selectors & absence of inline Mark All Read button
        for tag in (
            "inbox_region_badge",
            "inbox_search_toggle",
            "inbox_overflow_menu_button",
            "menu_mark_all_read_in_tab",
            "category_scrollable_tab_row",
            "inline_default_sms_banner",
            "banner_set_default_sms_button",
            "banner_privacy_guide_button",
            "category_unread_filter_bar",
            "category_unread_filter_summary",
            "category_unread_only_switch",
            "empty_unread_show_all_button",
            "messages_empty_state",
            "messages_lazy_column",
            "compose_sms_fab",
        ):
            self.assertIn(f'testTag("{tag}")', messages_code)
        self.assertNotIn("category_mark_all_read_button", messages_code)

        # Single-toggle Switch contract: Switch passes `enabled` directly, row stacks label+summary cleanly
        self.assertIn(
            "onCheckedChange = { enabled -> onToggleCategoryUnreadOnly(selectedCategory, enabled) }",
            messages_code,
        )
        self.assertIn("Column(modifier = Modifier.weight(1f))", messages_code)
        self.assertIn("snackbarHostState.currentSnackbarData?.dismiss()", messages_code)
        self.assertIn(
            '"Marked $markedCount ${targetCat.title} message(s) as read"',
            messages_code,
        )

        # Layout & overflow guards on MessagesScreen
        self.assertIn(".weight(1f, fill = false)", messages_code)
        self.assertIn("RoundedCornerShape(999.dp)", messages_code)
        self.assertIn(".size(36.dp)", messages_code)
        self.assertIn("PaddingValues(horizontal = 12.dp, vertical = 10.dp)", messages_code)
        self.assertIn(".verticalScroll(rememberScrollState())", messages_code)
        self.assertIn("padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 96.dp)", messages_code)
        self.assertIn("Modifier.padding(start = 73.dp, end = 16.dp)", messages_code)

        # Optimistic generation-guarded Show-Unread state in MainViewModel
        for token in (
            "_pendingUnreadOverrides",
            "unreadOverrideSeq",
            "effectiveUnreadOnly",
            "fun updateCategoryUnreadOnly(category: SmsCategory, unreadOnly: Boolean)",
            "fun markAllReadInCategory(category: SmsCategory, onDone: (Int) -> Unit = {})",
        ):
            self.assertIn(token, vm_code)

        # Repository cancels thread notifications in both markThreadAsRead and markAllActiveInCategoryAsRead
        mark_thread_match = re.search(
            r"suspend\s+fun\s+markThreadAsRead\s*\([^)]*\)\s*=([\s\S]*?)\n\s{4}\}",
            repo_code,
        )
        self.assertIsNotNone(mark_thread_match)
        self.assertIn("SmsNotificationHelper.cancelNotification(", mark_thread_match.group(1))
        for dao_call in (
            "getUnreadSystemIdsInActiveCategory(category)",
            "getUnreadSystemMmsIdsInActiveCategory(category)",
            "getUnreadThreadKeysInActiveCategory(category)",
            "markAllReadInActiveCategory(category)",
            "markSystemMmsRead(mmsIds)",
        ):
            self.assertIn(dao_call, repo_code)

    def test_thread_row_detail_compose_and_mms_ui_polish_contracts(self) -> None:
        row_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/common/ThreadRowItem.kt")
        )
        detail_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/thread/ThreadDetailScreen.kt")
        )
        mms_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/thread/MmsUiComponents.kt")
        )
        compose_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/thread/ComposeSmsScreen.kt")
        )

        # ThreadRowItem: FlowRow badges, dynamic pill unread count, ellipsis & touch target
        self.assertIn("FlowRow(", row_code)
        self.assertIn(".defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)", row_code)
        self.assertNotIn(".size(22.dp)\n                                        .clip(CircleShape)", row_code)
        self.assertGreaterEqual(row_code.count("TextOverflow.Ellipsis"), 3)
        self.assertGreaterEqual(row_code.count("softWrap = false"), 4)
        self.assertIn(".minimumInteractiveComponentSize()", row_code)

        # ThreadDetailScreen: responsive BoxWithConstraints bubble width, FlowRow metadata row, centered carrier footer
        self.assertIn("BoxWithConstraints(", detail_code)
        self.assertIn("(maxWidth * 0.85f).coerceIn(240.dp, 420.dp)", detail_code)
        self.assertIn("FlowRow(", detail_code)
        self.assertIn('testTag("thread_metadata_row_${msg.id}")', detail_code)
        self.assertIn("textAlign = TextAlign.Center", detail_code)
        self.assertGreaterEqual(detail_code.count("TextOverflow.Ellipsis"), 6)

        # MmsUiComponents: scrollable picker & emoji bar, bounded filename chip, padded retry CTA
        self.assertIn(".verticalScroll(rememberScrollState())", mms_code)
        self.assertIn(".horizontalScroll(rememberScrollState())", mms_code)
        self.assertIn(".widthIn(max = 160.dp)", mms_code)
        self.assertIn(".padding(horizontal = 4.dp, vertical = 2.dp)", mms_code)

        # ComposeSmsScreen: weighted bottom controls + single-line send & counter ellipsis
        self.assertIn(".weight(1f)\n                                .padding(end = 8.dp)", compose_code)
        self.assertIn('testTag("compose_char_counter")', compose_code)
        self.assertIn("softWrap = false", compose_code)
        self.assertGreaterEqual(compose_code.count("TextOverflow.Ellipsis"), 6)

    def test_settings_archive_backup_helpers_onboarding_and_main_activity_polish_contracts(self) -> None:
        settings_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/settings/SettingsScreen.kt")
        )
        archive_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/archive/ArchiveScreen.kt")
        )
        backup_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/backup/BackupScreen.kt")
        )
        helpers_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/common/CategoryUiHelpers.kt")
        )
        onboarding_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/ui/onboarding/OnboardingScreen.kt")
        )
        main_code = strip_kotlin_comments(
            read_text("app/src/main/java/com/example/MainActivity.kt")
        )

        # SettingsScreen
        self.assertIn(".heightIn(min = 44.dp)", settings_code)
        self.assertGreaterEqual(settings_code.count(".heightIn(min = 48.dp)"), 2)
        self.assertNotIn(".height(48.dp)", settings_code)
        self.assertIn(".clip(RoundedCornerShape(10.dp))", settings_code)
        self.assertIn("onCheckedChange = onCheckedChange", settings_code)

        # ArchiveScreen
        self.assertIn("BackHandler(enabled = isMultiSelect)", archive_code)
        self.assertIn('"No matching archived conversations"', archive_code)
        self.assertIn(".verticalScroll(rememberScrollState())", archive_code)
        self.assertIn("Modifier.padding(start = 73.dp, end = 16.dp)", archive_code)

        # BackupScreen
        self.assertIn('testTag("backup_status_row_last_status")', backup_code)
        self.assertIn("Arrangement.spacedBy(12.dp)", backup_code)
        self.assertIn("Modifier.weight(1.2f)", backup_code)
        self.assertIn("textAlign = TextAlign.End", backup_code)
        self.assertIn(".heightIn(min = 50.dp)", backup_code)
        self.assertGreaterEqual(backup_code.count(".heightIn(min = 48.dp)"), 3)
        self.assertNotIn(".height(48.dp)", backup_code)
        self.assertNotIn(".height(50.dp)", backup_code)
        self.assertGreaterEqual(backup_code.count(".verticalScroll(rememberScrollState())"), 2)

        # CategoryUiHelpers
        self.assertIn("BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)", helpers_code)
        self.assertIn(".heightIn(min = 50.dp)", helpers_code)
        self.assertGreaterEqual(helpers_code.count(".verticalScroll(rememberScrollState())"), 2)

        # OnboardingScreen
        self.assertIn(".heightIn(min = 54.dp)", onboarding_code)
        self.assertIn(".heightIn(min = 52.dp)", onboarding_code)
        self.assertIn(".heightIn(min = 50.dp)", onboarding_code)
        self.assertIn(".heightIn(min = 48.dp)", onboarding_code)
        self.assertIn(".heightIn(min = 44.dp)", onboarding_code)
        self.assertNotIn(".height(54.dp)", onboarding_code)
        self.assertNotIn(".height(52.dp)", onboarding_code)
        self.assertNotIn(".height(50.dp)", onboarding_code)

        # MainActivity: NavigationRail inset consumption, single-line nav labels, AppLock scroll, screenshot safety
        self.assertIn(".consumeWindowInsets(WindowInsets.safeDrawing)", main_code)
        self.assertGreaterEqual(main_code.count("TextOverflow.Ellipsis"), 2)
        self.assertIn(".safeDrawingPadding()", main_code)
        self.assertIn(".heightIn(min = 50.dp)", main_code)
        self.assertIn("window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)", main_code)

    def test_security_and_screenshot_invariants_preserved_across_all_ui_files(self) -> None:
        manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
        removed_perms = {
            el.attrib.get(f"{ANDROID_NS}name")
            for el in manifest_root.findall("uses-permission")
            if el.attrib.get(f"{TOOLS_NS}node") == "remove"
        }
        self.assertIn("android.permission.INTERNET", removed_perms)
        app_elem = manifest_root.find("application")
        self.assertIsNotNone(app_elem)
        self.assertEqual(app_elem.attrib.get(f"{ANDROID_NS}allowBackup"), "false")

        for kt_path in sorted(JAVA_DIR.rglob("*.kt")):
            code = strip_kotlin_comments(kt_path.read_text(encoding="utf-8"))
            self.assertNotIn("SecureFlagPolicy.SecureOn", code, f"Unexpected SecureOn in {kt_path}")
            self.assertIsNone(
                re.search(r"\b(setFlags|addFlags)\s*\([^)]*FLAG_SECURE", code),
                f"Unexpected FLAG_SECURE set/add in {kt_path}",
            )


if __name__ == "__main__":
    unittest.main()
