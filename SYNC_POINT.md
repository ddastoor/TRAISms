# Sync Point — SMS Notification Action Buttons (`wip/sms-notification-actions`)

**Date:** 2026-11-24  
**Base Release on `main`:** `v1.0.2` (`171485f`, `VERSION_CODE=3` — live on GitHub Releases)  
**Active Feature Branch:** `wip/sms-notification-actions`

---

## Feature Goal
Add action buttons to incoming SMS notifications with their corresponding behaviors:
1. **Archive** — Archives the SMS conversation/thread in the background and dismisses the notification.
2. **Assign Category** — Opens TRAISms directly to the **Move Sender Category** bottom sheet for that sender/thread and dismisses the notification.
3. **Copy OTP** *(only if it's an OTP message & App Lock preview redaction is not active)* — Copies the OTP code to the clipboard (marking `ClipDescription.EXTRA_IS_SENSITIVE` on Android 13+) and dismisses the notification.
4. **Delete** — Deletes that incoming SMS message (from both Android's `Telephony.Sms` provider when Default SMS app and the encrypted Room/SQLCipher DB) and dismisses the notification.

---

## Completed & Committed on `wip/sms-notification-actions` (~80% Done)

### 1. Notification Backend, Custom 4-Button OTP `RemoteViews`, & Action Receiver (`a114523`)
- **`app/src/main/java/com/example/data/sms/SmsNotificationHelper.kt`**
  - Solves Android SystemUI's 3-action cap (`Notification.MAX_ACTION_BUTTONS = 3`):
    - **Unlocked OTP messages (`extractedOtp != null && !redactPreviewForAppLock`):** Uses `NotificationCompat.DecoratedCustomViewStyle()` with custom `RemoteViews` (`@layout/notification_sms_otp_collapsed` and `@layout/notification_sms_otp_expanded` for expanded + 2-line heads-up) rendering all **4 buttons** (`Copy OTP`, `Archive`, `Assign Category`, `Delete`) with zero `.addAction(...)` duplication.
    - **Non-OTP / App-Locked messages:** Uses `NotificationCompat.BigTextStyle()` with **3 standard actions** (`Archive`, `Assign Category`, `Delete`).
  - Collision-free `PendingIntent` request codes (`31 * notificationId + slot`, `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`).
  - Android 12+ (`targetSdk 36`) Notification Trampoline compliant: `ACTION_ASSIGN_CATEGORY` uses `PendingIntent.getActivity` directly to `MainActivity`; `ACTION_COPY_OTP`, `ACTION_ARCHIVE`, and `ACTION_DELETE` use `PendingIntent.getBroadcast` to `SmsNotificationActionReceiver`.
  - Exposes `notificationIdForThread(threadKey)` and `cancelNotification(context, notificationId)`.
- **`app/src/main/java/com/example/data/sms/SmsNotificationActionReceiver.kt` & `app/src/main/AndroidManifest.xml`**
  - Registered with `android:exported="false"` (`INTERNET` & `ACCESS_NETWORK_STATE` `tools:node="remove"` untouched).
  - Cancels notifications safely via `intent.hasExtra(EXTRA_NOTIFICATION_ID)` (handles negative `threadKey.hashCode()` values).
  - Copies OTP synchronously on the main thread (preserving SystemUI clipboard whitelist token) with `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+ and Toast fallback on `< 33`.
  - Runs `ACTION_ARCHIVE` (`setThreadArchived(threadKey, true)`) and `ACTION_DELETE` (`deleteNotificationMessage(messageId, threadKey)`) via nullable `goAsync()` on `Dispatchers.IO`.
- **`SmsRepository.kt`, `SmsDao.kt`, `SmsDeliverReceiver.kt`, `res/layout/notification_sms_otp_*.xml`, `res/values/strings.xml`**
  - Passes `insertedMessageId` and `rawAddress` into `postIncomingSmsNotification(...)`.
  - Added `SmsRepository.deleteNotificationMessage(messageId, fallbackThreadKey)` and `SmsDao.getLatestMessageIdForThread(threadKey)`.

### 2. ViewModel & Bottom Sheet Prep (Committed in Sync Point)
- **`app/src/main/java/com/example/ui/MainViewModel.kt`**: Added `AssignCategoryRequest`, `pendingAssignCategory: StateFlow<AssignCategoryRequest?>`, `requestAssignCategory(...)`, and `dismissAssignCategory()`.
- **`app/src/main/java/com/example/ui/common/CategoryUiHelpers.kt`**: Added `move_category_sender_title` and `sheet_radio_<category>` test tags on `MoveSenderCategoryBottomSheet`.
- **`GEMINI.md`**: Project-wide Swarm-first workflow and core TRAISms invariants saved in repo.

---

## Exact Remaining Steps to Resume Tomorrow (~20% Left)

1. **Wire `ACTION_ASSIGN_CATEGORY` in `app/src/main/java/com/example/MainActivity.kt`:**
   - Add `val assignCategoryRequest: AssignCategoryRequest? = null` to `PendingExternalAction`.
   - In `parseExternalIntent(intent)` (used by both `onCreate` and `onNewIntent`):
     - If `intent.hasExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID)`, immediately cancel that notification via `SmsNotificationHelper.cancelNotification(...)` (`setAutoCancel(true)` does not auto-cancel on action button taps). Also remove consumed `EXTRA_OPEN_THREAD_KEY` / `EXTRA_NOTIFICATION_ID` extras so screen rotation doesn't re-trigger navigation.
     - When `intent.action == SmsNotificationHelper.ACTION_ASSIGN_CATEGORY`: parse `EXTRA_THREAD_KEY`, `EXTRA_RAW_ADDRESS`, `EXTRA_SENDER_TITLE`, `EXTRA_CURRENT_CATEGORY` (`SmsCategory.valueOf` fallback `OTHER`), cancel notification (fallback `notificationIdForThread(threadKey)` if needed), clear `intent.action = null` + remove extras so rotation doesn't re-open the sheet, and return `PendingExternalAction(assignCategoryRequest = ...)`.
   - In `TraiSmsPrimaryApp`:
     - Forward `pendingExternalAction.assignCategoryRequest` into `viewModel.requestAssignCategory(...)` (preserving it across `AppLockGateScreen` if App Lock is enabled) and render `MoveSenderCategoryBottomSheet` **after** the `if (settings.appLockEnabled && !isSessionUnlocked)` gate!
     - On confirm: `viewModel.dismissAssignCategory()`, `viewModel.assignSenderCategory(req.threadKey, req.senderTitle, chosenCat, saveRule)`, and `viewModel.selectCategory(chosenCat)`.

2. **Add Robolectric Suite (`app/src/test/java/com/example/SmsNotificationActionsRobolectricTest.kt`, `@Config(sdk = [34])`):**
   - Non-OTP notification exposes 3 actions (`Archive`, `Assign Category`, `Delete`) and no `Copy OTP`.
   - OTP notification exposes all 4 clickable `RemoteViews` buttons (`notif_btn_copy_otp`, `notif_btn_archive`, `notif_btn_assign_category`, `notif_btn_delete`) with zero duplicate `.actions`.
   - `ACTION_COPY_OTP` copies exact OTP to `ClipboardManager` (with `EXTRA_IS_SENSITIVE`) and cancels the notification.
   - `ACTION_ARCHIVE` marks the thread archived in Room DB and cancels the notification.
   - `ACTION_DELETE` deletes the target message from Room DB and cancels the notification.
   - Launching `MainActivity` with `ACTION_ASSIGN_CATEGORY` cancels the notification and displays `MoveSenderCategoryBottomSheet` (and respects `AppLockGateScreen` when locked).

3. **Verify & Ship:**
   - Have `@verifier` audit the diff, merge `wip/sms-notification-actions` into `main` (removing `SYNC_POINT.md` once done), push to `main` so GitHub Actions verifies `:app:testDebugUnitTest` + APK build, test on Pixel 7, and cut `v1.0.3` when ready.
