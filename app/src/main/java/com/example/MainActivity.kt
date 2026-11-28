package com.example

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.db.SmsCategory
import com.example.data.sms.SmsNotificationHelper
import com.example.data.sms.SystemSmsHelper
import com.example.ui.AssignCategoryRequest
import com.example.ui.MainViewModel
import com.example.ui.archive.ArchiveScreen
import com.example.ui.backup.BackupScreen
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.messages.MessagesScreen
import com.example.ui.onboarding.OnboardingScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.TraiSmsTheme
import com.example.ui.thread.ComposeSmsScreen
import com.example.ui.thread.ThreadDetailScreen
import kotlinx.serialization.Serializable

// Type-Safe Navigation Routes
@Serializable
object MessagesRoute

@Serializable
object ArchiveRoute

@Serializable
object BackupRoute

@Serializable
object SettingsRoute

@Serializable
object OnboardingRoute

@Serializable
data class ThreadDetailRoute(val threadKey: String)

@Serializable
data class ComposeSmsRoute(
    val initialRecipient: String = "",
    val initialBody: String = ""
)

data class PendingExternalAction(
    val openThreadKey: String? = null,
    val composeRecipient: String? = null,
    val composeBody: String? = null,
    val assignCategoryRequest: AssignCategoryRequest? = null
)

/**
 * Single-Activity entry point extending `ComponentActivity` directly so Jetpack Compose
 * `rememberLauncherForActivityResult` uses 32-bit `ActivityResultRegistry` request codes cleanly
 * without legacy FragmentActivity 16-bit requestCode restrictions.
 */
class MainActivity : ComponentActivity() {

    private var pendingExternalAction by mutableStateOf<PendingExternalAction?>(null)
    private var isRequestingDefaultSmsRole = false
    private var hasEnteredStopped = false
    private var promptedDefaultSmsThisForeground by mutableStateOf(false)
    private var foregroundEpoch by mutableIntStateOf(0)
    private var onDefaultSmsRoleReturned: (() -> Unit)? = null

    private val defaultSmsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isRequestingDefaultSmsRole = false
        onDefaultSmsRoleReturned?.invoke()
    }

    override fun onStart() {
        super.onStart()
        if (hasEnteredStopped) {
            hasEnteredStopped = false
            promptedDefaultSmsThisForeground = false
            foregroundEpoch += 1
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isRequestingDefaultSmsRole) {
            hasEnteredStopped = true
        }
    }

    fun maybePromptDefaultSmsRoleOnLaunch(systemSmsHelper: SystemSmsHelper) {
        if (systemSmsHelper.isDefaultSmsApp() ||
            promptedDefaultSmsThisForeground ||
            isRequestingDefaultSmsRole
        ) {
            return
        }
        promptedDefaultSmsThisForeground = true
        isRequestingDefaultSmsRole = true

        val primaryIntent = systemSmsHelper.createDefaultSmsRequestIntent()
        if (primaryIntent == null) {
            isRequestingDefaultSmsRole = false
            return
        }

        try {
            defaultSmsRoleLauncher.launch(primaryIntent)
        } catch (_: ActivityNotFoundException) {
            try {
                defaultSmsRoleLauncher.launch(systemSmsHelper.createFallbackDefaultSmsIntent())
            } catch (_: Exception) {
                isRequestingDefaultSmsRole = false
            }
        } catch (_: Exception) {
            isRequestingDefaultSmsRole = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.LTGRAY
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.WHITE,
                android.graphics.Color.LTGRAY
            )
        )
        pendingExternalAction = parseExternalIntent(intent)

        val appContainer = (applicationContext as TraiSmsApp).container
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.provideFactory(appContainer)
            )
            onDefaultSmsRoleReturned = { mainViewModel.refreshPermissionsAndSyncIfAuthorized() }
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshPermissionsAndSyncIfAuthorized()
            }

            TraiSmsTheme(
                smsFontSize = settings.smsFontSize,
                smsFontBold = settings.smsFontBold
            ) {
                TraiSmsPrimaryApp(
                    activity = this@MainActivity,
                    viewModel = mainViewModel,
                    pendingExternalAction = pendingExternalAction,
                    onConsumeExternalAction = { pendingExternalAction = null },
                    foregroundEpoch = foregroundEpoch,
                    onRequestDefaultSmsPopup = { maybePromptDefaultSmsRoleOnLaunch(mainViewModel.systemSmsHelper) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingExternalAction = parseExternalIntent(intent)
    }

    private fun parseExternalIntent(intent: Intent?): PendingExternalAction? {
        if (intent == null) return null

        val hadNotificationIdExtra = intent.hasExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID)
        if (hadNotificationIdExtra) {
            val notificationId = intent.getIntExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
            SmsNotificationHelper.cancelNotification(this, notificationId)
            intent.removeExtra(SmsNotificationHelper.EXTRA_NOTIFICATION_ID)
        }

        if (intent.action == SmsNotificationHelper.ACTION_ASSIGN_CATEGORY) {
            val threadKey = intent.getStringExtra(SmsNotificationHelper.EXTRA_THREAD_KEY)?.trim().orEmpty()
            val rawAddress = intent.getStringExtra(SmsNotificationHelper.EXTRA_RAW_ADDRESS)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: threadKey
            val senderTitle = intent.getStringExtra(SmsNotificationHelper.EXTRA_SENDER_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: rawAddress
            val currentCategory = intent.getStringExtra(SmsNotificationHelper.EXTRA_CURRENT_CATEGORY)
                ?.trim()
                ?.let { raw -> runCatching { SmsCategory.valueOf(raw) }.getOrNull() }
                ?: SmsCategory.OTHER

            if (!hadNotificationIdExtra && threadKey.isNotEmpty()) {
                SmsNotificationHelper.cancelNotification(
                    this,
                    SmsNotificationHelper.notificationIdForThread(threadKey)
                )
            }

            intent.action = null
            intent.removeExtra(SmsNotificationHelper.EXTRA_MESSAGE_ID)
            intent.removeExtra(SmsNotificationHelper.EXTRA_THREAD_KEY)
            intent.removeExtra(SmsNotificationHelper.EXTRA_RAW_ADDRESS)
            intent.removeExtra(SmsNotificationHelper.EXTRA_SENDER_TITLE)
            intent.removeExtra(SmsNotificationHelper.EXTRA_CURRENT_CATEGORY)

            if (threadKey.isNotEmpty()) {
                return PendingExternalAction(
                    assignCategoryRequest = AssignCategoryRequest(
                        threadKey = threadKey,
                        rawAddress = rawAddress,
                        senderTitle = senderTitle,
                        currentCategory = currentCategory
                    )
                )
            }
            return null
        }

        val threadKeyExtra = intent.getStringExtra(SmsNotificationHelper.EXTRA_OPEN_THREAD_KEY)?.trim()
        if (!threadKeyExtra.isNullOrEmpty()) {
            if (!hadNotificationIdExtra) {
                SmsNotificationHelper.cancelNotification(
                    this,
                    SmsNotificationHelper.notificationIdForThread(threadKeyExtra)
                )
            }
            intent.removeExtra(SmsNotificationHelper.EXTRA_OPEN_THREAD_KEY)
            return PendingExternalAction(openThreadKey = threadKeyExtra)
        }

        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SENDTO) {
            val recipient = intent.data?.schemeSpecificPart
                ?.substringBefore('?')
                ?.trim()
                .orEmpty()
            val body = intent.getStringExtra("sms_body")
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: ""
            return PendingExternalAction(
                composeRecipient = recipient,
                composeBody = body
            )
        }
        return null
    }
}

data class BottomNavDestination(
    val labelRes: Int,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val PrimaryBottomDestinations = listOf(
    BottomNavDestination(
        labelRes = R.string.nav_messages,
        route = MessagesRoute,
        selectedIcon = Icons.Filled.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        testTag = "bottom_nav_messages"
    ),
    BottomNavDestination(
        labelRes = R.string.nav_archive,
        route = ArchiveRoute,
        selectedIcon = Icons.Filled.Archive,
        unselectedIcon = Icons.Outlined.Archive,
        testTag = "bottom_nav_archive"
    ),
    BottomNavDestination(
        labelRes = R.string.nav_backup,
        route = BackupRoute,
        selectedIcon = Icons.Filled.Backup,
        unselectedIcon = Icons.Outlined.Backup,
        testTag = "bottom_nav_backup"
    ),
    BottomNavDestination(
        labelRes = R.string.nav_settings,
        route = SettingsRoute,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "bottom_nav_settings"
    )
)

/**
 * Primary Material 3 Light Composable structure hosting the 4-tab `BottomNavigationBar`
 * (`Messages`, `Archive`, `Backup`, `Settings`) and adaptive `NavigationRail` on tablets.
 */
@Composable
fun TraiSmsPrimaryApp(
    activity: ComponentActivity,
    viewModel: MainViewModel,
    pendingExternalAction: PendingExternalAction?,
    onConsumeExternalAction: () -> Unit,
    foregroundEpoch: Int = 0,
    onRequestDefaultSmsPopup: () -> Unit = {}
) {
    val isSettingsReady by viewModel.isSettingsReady.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isDefaultSmsApp by viewModel.isDefaultSmsApp.collectAsStateWithLifecycle()
    val showEmulatorDemo by viewModel.showEmulatorDemoOption.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val currentCategoryUnreadOnly by viewModel.currentCategoryUnreadOnly.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val activeThreads by viewModel.activeThreads.collectAsStateWithLifecycle()
    val archivedThreads by viewModel.archivedThreads.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedMessageCount.collectAsStateWithLifecycle()
    val totalMessageCount by viewModel.totalMessageCount.collectAsStateWithLifecycle()
    val activeRegionBadge by viewModel.activeRegionBadge.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsStateWithLifecycle()
    val pendingAssignCategory by viewModel.pendingAssignCategory.collectAsStateWithLifecycle()

    var isSessionUnlocked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingExternalAction) {
        val assignReq = pendingExternalAction?.assignCategoryRequest ?: return@LaunchedEffect
        viewModel.requestAssignCategory(assignReq)
        if (
            pendingExternalAction.openThreadKey.isNullOrBlank() &&
            pendingExternalAction.composeRecipient == null &&
            pendingExternalAction.composeBody == null
        ) {
            onConsumeExternalAction()
        }
    }

    LaunchedEffect(settings.appLockEnabled) {
        if (settings.appLockEnabled && !isSessionUnlocked) {
            launchNativeBiometricUnlock(activity) { unlocked ->
                isSessionUnlocked = unlocked
            }
        }
    }

    LaunchedEffect(isSettingsReady, settings.appLockEnabled, isSessionUnlocked, foregroundEpoch) {
        if (!isSettingsReady) return@LaunchedEffect
        if (settings.appLockEnabled && !isSessionUnlocked) return@LaunchedEffect
        if (!viewModel.systemSmsHelper.isDefaultSmsApp()) {
            onRequestDefaultSmsPopup()
        }
    }

    if (settings.appLockEnabled && !isSessionUnlocked) {
        AppLockGateScreen(
            onUnlockRequested = {
                launchNativeBiometricUnlock(activity) { unlocked ->
                    isSessionUnlocked = unlocked
                }
            }
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(pendingExternalAction) {
        val action = pendingExternalAction ?: return@LaunchedEffect
        if (!action.openThreadKey.isNullOrBlank()) {
            navController.navigate(ThreadDetailRoute(action.openThreadKey))
            onConsumeExternalAction()
        } else if (action.composeRecipient != null || action.composeBody != null) {
            navController.navigate(
                ComposeSmsRoute(
                    initialRecipient = action.composeRecipient.orEmpty(),
                    initialBody = action.composeBody.orEmpty()
                )
            )
            onConsumeExternalAction()
        }
    }

    val isTopLevelDestination = currentDestination?.let { dest ->
        dest.hasRoute<MessagesRoute>() ||
            dest.hasRoute<ArchiveRoute>() ||
            dest.hasRoute<BackupRoute>() ||
            dest.hasRoute<SettingsRoute>()
    } ?: true

    val totalUnreadCount = remember(categoryCounts) {
        categoryCounts.values.sumOf { it.unreadCount }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        val navigateTopLevel: (Any) -> Unit = { targetRoute ->
            navController.navigate(targetRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        val navHostComposable: @Composable (Modifier) -> Unit = { hostModifier ->
            NavHost(
                navController = navController,
                startDestination = MessagesRoute,
                modifier = hostModifier
            ) {
                composable<MessagesRoute> {
                    MessagesScreen(
                        selectedCategory = selectedCategory,
                        threads = activeThreads,
                        categoryCounts = categoryCounts,
                        totalMessageCount = totalMessageCount,
                        searchQuery = searchQuery,
                        leftSwipeAction = settings.leftSwipeAction,
                        rightSwipeAction = settings.rightSwipeAction,
                        activeRegionBadge = activeRegionBadge,
                        syncProgress = syncProgress,
                        isDefaultSmsApp = isDefaultSmsApp,
                        showSetupBanner = !settings.onboardingDismissed,
                        showEmulatorDemoBanner = showEmulatorDemo,
                        systemSmsHelper = viewModel.systemSmsHelper,
                        onSelectCategory = viewModel::selectCategory,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onOpenThread = { key -> navController.navigate(ThreadDetailRoute(key)) },
                        onOpenCompose = { navController.navigate(ComposeSmsRoute()) },
                        onOpenOnboardingGuide = { navController.navigate(OnboardingRoute) },
                        onDismissSetupBanner = viewModel::dismissOnboarding,
                        onArchiveThread = viewModel::setThreadArchived,
                        onDeleteThread = viewModel::deleteThread,
                        onAssignSenderCategory = viewModel::assignSenderCategory,
                        onDeleteAllInCurrentTab = viewModel::deleteAllInCategory,
                        onRequestCountInCurrentTab = viewModel::countActiveMessagesInCategory,
                        onDeleteAllInApp = viewModel::deleteAllMessagesInApp,
                        onTriggerDeviceSync = viewModel::refreshPermissionsAndSyncIfAuthorized,
                        onSeedDemoInbox = viewModel::seedDemoInbox,
                        promoAutoDeleteDays = settings.promoAutoDeleteDays,
                        otpAutoDeleteDays = settings.otpAutoDeleteDays,
                        onUpdatePromoAutoDelete = viewModel::updatePromoAutoDeleteDays,
                        onUpdateOtpAutoDelete = viewModel::updateOtpAutoDeleteDays,
                        showUnreadOnly = currentCategoryUnreadOnly,
                        onToggleCategoryUnreadOnly = viewModel::updateCategoryUnreadOnly,
                        onMarkAllReadInCurrentTab = viewModel::markAllReadInCategory
                    )
                }

                composable<ArchiveRoute> {
                    ArchiveScreen(
                        archivedThreads = archivedThreads,
                        onOpenThread = { key -> navController.navigate(ThreadDetailRoute(key)) },
                        onSetThreadArchived = viewModel::setThreadArchived,
                        onSetThreadsArchived = viewModel::setThreadsArchived,
                        onDeleteThreads = viewModel::deleteThreads,
                        onAssignSenderCategory = viewModel::assignSenderCategory,
                        leftSwipeAction = settings.leftSwipeAction,
                        rightSwipeAction = settings.rightSwipeAction
                    )
                }

                composable<BackupRoute> {
                    BackupScreen(
                        settings = settings,
                        totalMessagesInDb = totalMessageCount,
                        isBackupInProgress = isBackupInProgress,
                        onSaveDriveDestination = viewModel::saveDriveDestination,
                        onSaveBackupPassphrase = viewModel::saveBackupPassphrase,
                        onUpdateBackupSchedule = viewModel::updateBackupSchedule,
                        onRunManualBackupNow = viewModel::runManualBackupNow,
                        onRunRestoreFromUri = viewModel::runRestoreFromUri
                    )
                }

                composable<SettingsRoute> {
                    SettingsScreen(
                        settings = settings,
                        isDefaultSmsApp = isDefaultSmsApp,
                        detectedRegionLabel = activeRegionBadge,
                        totalMessageCount = totalMessageCount,
                        systemSmsHelper = viewModel.systemSmsHelper,
                        onRefreshRoleStatus = viewModel::refreshPermissionsAndSyncIfAuthorized,
                        onUpdatePromoAutoDelete = viewModel::updatePromoAutoDeleteDays,
                        onUpdateLeftSwipeAction = viewModel::updateLeftSwipeAction,
                        onUpdateRightSwipeAction = viewModel::updateRightSwipeAction,
                        onUpdateUnarchiveOnNewSms = viewModel::updateUnarchiveOnNewSms,
                        onUpdateRegionOverride = viewModel::updateRegionOverride,
                        onUpdateBackupSchedule = viewModel::updateBackupSchedule,
                        onNavigateToBackupTab = { navigateTopLevel(BackupRoute) },
                        onToggleAppLock = { enable ->
                            if (enable) {
                                launchNativeBiometricUnlock(activity) { verified ->
                                    if (verified) {
                                        isSessionUnlocked = true
                                        viewModel.updateAppLockEnabled(true)
                                    }
                                }
                            } else {
                                viewModel.updateAppLockEnabled(false)
                            }
                        },
                        onRerunCategorization = viewModel::rerunCategorizationOnAll,
                        onDeleteAllMessagesInApp = viewModel::deleteAllMessagesInApp,
                        onUpdateSmsFontSize = viewModel::updateSmsFontSize,
                        onUpdateSmsFontBold = viewModel::updateSmsFontBold,
                        onUpdateOtpAutoDelete = viewModel::updateOtpAutoDeleteDays,
                        onUpdateRcsChatsEnabled = viewModel::updateRcsChatsEnabled,
                        onUpdateAutoDownloadMms = viewModel::updateAutoDownloadMms,
                        onUpdateSendReadReceipts = viewModel::updateSendReadReceipts
                    )
                }

                composable<OnboardingRoute> {
                    BackHandler { navController.popBackStack() }
                    OnboardingScreen(
                        systemSmsHelper = viewModel.systemSmsHelper,
                        isDefaultSmsApp = isDefaultSmsApp,
                        showEmulatorDemoSeed = showEmulatorDemo,
                        onRoleOrPermissionsUpdated = {
                            viewModel.refreshPermissionsAndSyncIfAuthorized()
                            if (viewModel.systemSmsHelper.hasReadSmsPermission()) {
                                viewModel.dismissOnboarding()
                                navController.popBackStack()
                            }
                        },
                        onSeedDemoInbox = {
                            viewModel.seedDemoInbox()
                            navController.popBackStack()
                        },
                        onSkipToInbox = {
                            viewModel.dismissOnboarding()
                            navController.popBackStack()
                        }
                    )
                }

                composable<ThreadDetailRoute> { entry ->
                    val route = entry.toRoute<ThreadDetailRoute>()
                    val threadMessagesWithAttachments by viewModel
                        .observeThreadMessagesWithAttachments(route.threadKey)
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    val threadMessages = remember(threadMessagesWithAttachments) {
                        threadMessagesWithAttachments.map { it.message }
                    }
                    val attachmentsByMessageId = remember(threadMessagesWithAttachments) {
                        threadMessagesWithAttachments.associate { it.message.id to it.attachments }
                    }

                    ThreadDetailScreen(
                        threadKey = route.threadKey,
                        messages = threadMessages,
                        onBack = { navController.popBackStack() },
                        onMarkThreadRead = viewModel::markThreadRead,
                        onSendReply = viewModel::sendSms,
                        onDeleteSingleMessage = viewModel::deleteSingleMessage,
                        onToggleArchive = viewModel::setThreadArchived,
                        onAssignCategory = viewModel::assignSenderCategory,
                        onForwardMessage = { forwardedBody ->
                            navController.navigate(ComposeSmsRoute(initialBody = forwardedBody))
                        },
                        attachmentsByMessageId = attachmentsByMessageId,
                        rcsChatsEnabled = settings.rcsChatsEnabled,
                        contactCache = viewModel.contactCache,
                        systemSmsHelper = viewModel.systemSmsHelper,
                        onSendReplyWithQuote = viewModel::sendSmsWithQuote,
                        onSendMmsReply = viewModel::sendMms,
                        onToggleReaction = { msgId, emoji -> viewModel.sendReaction(msgId, emoji) },
                        onDownloadMms = { msgId -> viewModel.downloadPendingMms(msgId) },
                        onRetryMessage = { msgId -> viewModel.retryFailedMessage(msgId) }
                    )
                }

                composable<ComposeSmsRoute> { entry ->
                    val route = entry.toRoute<ComposeSmsRoute>()
                    ComposeSmsScreen(
                        initialRecipient = route.initialRecipient,
                        initialBody = route.initialBody,
                        contactCache = viewModel.contactCache,
                        onBack = { navController.popBackStack() },
                        onSendSms = { recipient, body, onResult ->
                            viewModel.sendSms(recipient, body) { res ->
                                onResult(res)
                                res.onSuccess { newThreadKey ->
                                    navController.popBackStack()
                                    navController.navigate(ThreadDetailRoute(newThreadKey))
                                }
                            }
                        },
                        systemSmsHelper = viewModel.systemSmsHelper,
                        onSendMms = { recipients, body, subject, attachments, onResult ->
                            viewModel.sendMms(recipients, body, subject, attachments) { res ->
                                onResult(res)
                                res.onSuccess { newThreadKey ->
                                    navController.popBackStack()
                                    navController.navigate(ThreadDetailRoute(newThreadKey))
                                }
                            }
                        }
                    )
                }
            }
        }

        if (useNavigationRail && isTopLevelDestination) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .consumeWindowInsets(WindowInsets.safeDrawing)
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxHeight()
                        .testTag("primary_navigation_rail")
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    PrimaryBottomDestinations.forEach { item ->
                        val selected = isRouteSelected(currentDestination, item.route)
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTopLevel(item.route) },
                            icon = {
                                NavItemBadgedIcon(
                                    item = item,
                                    selected = selected,
                                    totalUnreadCount = totalUnreadCount,
                                    archivedCount = archivedCount
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(item.labelRes),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                navHostComposable(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (isTopLevelDestination) {
                        BottomNavigationBar(
                            currentDestination = currentDestination,
                            totalUnreadCount = totalUnreadCount,
                            archivedCount = archivedCount,
                            onNavigate = navigateTopLevel
                        )
                    }
                }
            ) { scaffoldPadding ->
                navHostComposable(
                    Modifier
                        .padding(scaffoldPadding)
                        .consumeWindowInsets(scaffoldPadding)
                )
            }
        }
    }

    pendingAssignCategory?.let { req ->
        MoveSenderCategoryBottomSheet(
            senderTitle = req.senderTitle,
            currentCategory = req.currentCategory,
            onDismiss = viewModel::dismissAssignCategory,
            onConfirm = { chosenCat, saveRule ->
                viewModel.dismissAssignCategory()
                viewModel.assignSenderCategory(
                    threadKey = req.threadKey,
                    senderTitle = req.senderTitle,
                    targetCategory = chosenCat,
                    savePermanentRule = saveRule
                )
                viewModel.selectCategory(chosenCat)
            }
        )
    }
}

@Composable
fun BottomNavigationBar(
    currentDestination: NavDestination?,
    totalUnreadCount: Int,
    archivedCount: Int,
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier.testTag("bottom_navigation_bar")
    ) {
        PrimaryBottomDestinations.forEach { item ->
            val selected = isRouteSelected(currentDestination, item.route)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    NavItemBadgedIcon(
                        item = item,
                        selected = selected,
                        totalUnreadCount = totalUnreadCount,
                        archivedCount = archivedCount
                    )
                },
                label = {
                    Text(
                        text = stringResource(item.labelRes),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag(item.testTag)
            )
        }
    }
}

private fun isRouteSelected(
    destination: NavDestination?,
    route: Any
): Boolean {
    if (destination == null) return false
    return when (route) {
        is MessagesRoute -> destination.hasRoute<MessagesRoute>()
        is ArchiveRoute -> destination.hasRoute<ArchiveRoute>()
        is BackupRoute -> destination.hasRoute<BackupRoute>()
        is SettingsRoute -> destination.hasRoute<SettingsRoute>()
        else -> false
    }
}

@Composable
private fun NavItemBadgedIcon(
    item: BottomNavDestination,
    selected: Boolean,
    totalUnreadCount: Int,
    archivedCount: Int
) {
    val badgeValue = if (item.route is MessagesRoute) totalUnreadCount else 0
    BadgedBox(
        badge = {
            if (badgeValue > 0) {
                Badge {
                    Text(badgeValue.coerceAtMost(99).toString())
                }
            } else if (item.route is ArchiveRoute && archivedCount > 0) {
                // Keep Archive icon uncluttered
            }
        }
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
            contentDescription = stringResource(item.labelRes)
        )
    }
}

@Composable
private fun AppLockGateScreen(onUnlockRequested: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("app_lock_gate_screen")
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.app_locked_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_locked_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onUnlockRequested,
                modifier = Modifier
                    .heightIn(min = 50.dp)
                    .testTag("unlock_biometric_button")
            ) {
                Text(stringResource(R.string.unlock_button))
            }
        }
    }
}

/**
 * Native OS Biometric + Device PIN unlock helper running directly on `ComponentActivity`
 * (zero `FragmentActivity` dependency).
 */
private fun launchNativeBiometricUnlock(
    activity: ComponentActivity,
    onResult: (Boolean) -> Unit
) {
    val keyguard = activity.getSystemService(KeyguardManager::class.java)
    if (keyguard?.isDeviceSecure != true) {
        onResult(true)
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val biometricManager = activity.getSystemService(BiometricManager::class.java)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (biometricManager?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(true)
            return
        }

        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock TRAISms")
            .setSubtitle("Verify identity to access encrypted SMS vault")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(
            CancellationSignal(),
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onResult(false)
                }
            }
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock TRAISms")
            .setSubtitle("Verify identity to access encrypted SMS vault")
            .setDeviceCredentialAllowed(true)
            .build()

        prompt.authenticate(
            CancellationSignal(),
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    onResult(false)
                }
            }
        )
    } else {
        onResult(true)
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
