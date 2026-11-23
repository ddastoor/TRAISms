package com.example

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.sms.SmsNotificationHelper
import com.example.ui.MainViewModel
import com.example.ui.archive.ArchiveScreen
import com.example.ui.backup.BackupScreen
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
data class ThreadDetailRoute(val threadKey: String)

@Serializable
data class ComposeSmsRoute(
    val initialRecipient: String = "",
    val initialBody: String = ""
)

data class PendingExternalAction(
    val openThreadKey: String? = null,
    val composeRecipient: String? = null,
    val composeBody: String? = null
)

class MainActivity : FragmentActivity() {

    private var pendingExternalAction by mutableStateOf<PendingExternalAction?>(null)

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
        val isStreamingOrEmulator = BuildConfig.DEBUG || appContainer.systemSmsHelper.isRunningOnEmulator()

        // Ensure window is NOT blacked out by FLAG_SECURE in the browser Streaming Emulator on launch
        if (isStreamingOrEmulator) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.provideFactory(appContainer)
            )
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()

            // Dynamically enforce or clear FLAG_SECURE:
            // - On physical release devices: enforced whenever settings.blockScreenshots == true (ON by default)
            // - On Streaming Emulator / Debug preview: kept clear unless user explicitly forces it in Settings
            LaunchedEffect(settings.blockScreenshots, settings.userForcedFlagSecureInStream) {
                val shouldEnableFlagSecure = if (isStreamingOrEmulator) {
                    settings.blockScreenshots && settings.userForcedFlagSecureInStream
                } else {
                    settings.blockScreenshots
                }

                if (shouldEnableFlagSecure) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                mainViewModel.refreshPermissionsAndSyncIfAuthorized()
            }

            TraiSmsTheme {
                TraiSmsRootContent(
                    activity = this@MainActivity,
                    viewModel = mainViewModel,
                    pendingExternalAction = pendingExternalAction,
                    onConsumeExternalAction = { pendingExternalAction = null }
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

        val threadKeyExtra = intent.getStringExtra(SmsNotificationHelper.EXTRA_OPEN_THREAD_KEY)
        if (!threadKeyExtra.isNullOrBlank()) {
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

private data class TopLevelNavItem(
    val labelRes: Int,
    val route: Any,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

@Composable
private fun TraiSmsRootContent(
    activity: FragmentActivity,
    viewModel: MainViewModel,
    pendingExternalAction: PendingExternalAction?,
    onConsumeExternalAction: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isDefaultSmsApp by viewModel.isDefaultSmsApp.collectAsStateWithLifecycle()
    val showEmulatorDemo by viewModel.showEmulatorDemoOption.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val activeThreads by viewModel.activeThreads.collectAsStateWithLifecycle()
    val archivedThreads by viewModel.archivedThreads.collectAsStateWithLifecycle()
    val categoryCounts by viewModel.categoryCounts.collectAsStateWithLifecycle()
    val archivedCount by viewModel.archivedMessageCount.collectAsStateWithLifecycle()
    val totalMessageCount by viewModel.totalMessageCount.collectAsStateWithLifecycle()
    val activeRegionBadge by viewModel.activeRegionBadge.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsStateWithLifecycle()

    var isSessionUnlocked by rememberSaveable { mutableStateOf(false) }

    // Trigger BiometricPrompt automatically when App Lock is enabled
    LaunchedEffect(settings.appLockEnabled) {
        if (settings.appLockEnabled && !isSessionUnlocked) {
            launchBiometricUnlock(activity) { unlocked ->
                isSessionUnlocked = unlocked
            }
        }
    }

    if (settings.appLockEnabled && !isSessionUnlocked) {
        AppLockGateScreen(
            onUnlockRequested = {
                launchBiometricUnlock(activity) { unlocked ->
                    isSessionUnlocked = unlocked
                }
            }
        )
        return
    }

    // Show onboarding when not Default SMS App and user hasn't dismissed/seeded yet (unless launched via SENDTO)
    val shouldShowOnboarding = !isDefaultSmsApp &&
        !settings.onboardingDismissed &&
        totalMessageCount == 0 &&
        pendingExternalAction == null

    if (shouldShowOnboarding) {
        OnboardingScreen(
            systemSmsHelper = viewModel.systemSmsHelper,
            isDefaultSmsApp = isDefaultSmsApp,
            showEmulatorDemoSeed = showEmulatorDemo,
            onRoleOrPermissionsUpdated = {
                viewModel.refreshPermissionsAndSyncIfAuthorized()
                if (viewModel.systemSmsHelper.hasReadSmsPermission()) {
                    viewModel.dismissOnboarding()
                }
            },
            onSeedDemoInbox = { viewModel.seedDemoInbox() },
            onSkipToInbox = { viewModel.dismissOnboarding() }
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Handle external ACTION_SENDTO / notification deep-links
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

    val topLevelItems = remember {
        listOf(
            TopLevelNavItem(
                labelRes = R.string.nav_messages,
                route = MessagesRoute,
                selectedIcon = Icons.Filled.ChatBubble,
                unselectedIcon = Icons.Outlined.ChatBubbleOutline,
                testTag = "bottom_nav_messages"
            ),
            TopLevelNavItem(
                labelRes = R.string.nav_archive,
                route = ArchiveRoute,
                selectedIcon = Icons.Filled.Archive,
                unselectedIcon = Icons.Outlined.Archive,
                testTag = "bottom_nav_archive"
            ),
            TopLevelNavItem(
                labelRes = R.string.nav_backup,
                route = BackupRoute,
                selectedIcon = Icons.Filled.Backup,
                unselectedIcon = Icons.Outlined.Backup,
                testTag = "bottom_nav_backup"
            ),
            TopLevelNavItem(
                labelRes = R.string.nav_settings,
                route = SettingsRoute,
                selectedIcon = Icons.Filled.Settings,
                unselectedIcon = Icons.Outlined.Settings,
                testTag = "bottom_nav_settings"
            )
        )
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
                        activeRegionBadge = activeRegionBadge,
                        syncProgress = syncProgress,
                        showEmulatorDemoBanner = showEmulatorDemo,
                        onSelectCategory = viewModel::selectCategory,
                        onSearchQueryChange = viewModel::updateSearchQuery,
                        onOpenThread = { key -> navController.navigate(ThreadDetailRoute(key)) },
                        onOpenCompose = { navController.navigate(ComposeSmsRoute()) },
                        onArchiveThread = viewModel::setThreadArchived,
                        onDeleteThread = viewModel::deleteThread,
                        onAssignSenderCategory = viewModel::assignSenderCategory,
                        onDeleteAllInCurrentTab = viewModel::deleteAllInCategory,
                        onRequestCountInCurrentTab = viewModel::countActiveMessagesInCategory,
                        onDeleteAllInApp = viewModel::deleteAllMessagesInApp,
                        onTriggerDeviceSync = viewModel::refreshPermissionsAndSyncIfAuthorized,
                        onSeedDemoInbox = viewModel::seedDemoInbox
                    )
                }

                composable<ArchiveRoute> {
                    ArchiveScreen(
                        archivedThreads = archivedThreads,
                        onOpenThread = { key -> navController.navigate(ThreadDetailRoute(key)) },
                        onSetThreadArchived = viewModel::setThreadArchived,
                        onSetThreadsArchived = viewModel::setThreadsArchived,
                        onDeleteThreads = viewModel::deleteThreads,
                        onAssignSenderCategory = viewModel::assignSenderCategory
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
                        onUpdateUnarchiveOnNewSms = viewModel::updateUnarchiveOnNewSms,
                        onUpdateRegionOverride = viewModel::updateRegionOverride,
                        onUpdateBackupSchedule = viewModel::updateBackupSchedule,
                        onNavigateToBackupTab = { navigateTopLevel(BackupRoute) },
                        onToggleAppLock = { enable ->
                            if (enable) {
                                launchBiometricUnlock(activity) { verified ->
                                    if (verified) {
                                        isSessionUnlocked = true
                                        viewModel.updateAppLockEnabled(true)
                                    }
                                }
                            } else {
                                viewModel.updateAppLockEnabled(false)
                            }
                        },
                        onToggleBlockScreenshots = viewModel::updateBlockScreenshots,
                        onRerunCategorization = viewModel::rerunCategorizationOnAll,
                        onDeleteAllMessagesInApp = viewModel::deleteAllMessagesInApp
                    )
                }

                composable<ThreadDetailRoute> { entry ->
                    val route = entry.toRoute<ThreadDetailRoute>()
                    val threadMessages by viewModel.observeThreadMessages(route.threadKey)
                        .collectAsStateWithLifecycle(initialValue = emptyList())

                    ThreadDetailScreen(
                        threadKey = route.threadKey,
                        messages = threadMessages,
                        onBack = { navController.popBackStack() },
                        onMarkThreadRead = viewModel::markThreadRead,
                        onSendReply = viewModel::sendSms,
                        onDeleteSingleMessage = viewModel::deleteSingleMessage,
                        onToggleArchive = viewModel::setThreadArchived,
                        onAssignCategory = viewModel::assignSenderCategory
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
            ) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    topLevelItems.forEach { item ->
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
                            label = { Text(stringResource(item.labelRes)) },
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                navHostComposable(Modifier.weight(1f))
            }
        } else {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (isTopLevelDestination) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            topLevelItems.forEach { item ->
                                val selected = isRouteSelected(currentDestination, item.route)
                                NavigationBarItem(
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
                                    label = { Text(stringResource(item.labelRes)) },
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }
            ) { scaffoldPadding ->
                navHostComposable(Modifier.padding(scaffoldPadding))
            }
        }
    }
}

private fun isRouteSelected(
    destination: androidx.navigation.NavDestination?,
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
    item: TopLevelNavItem,
    selected: Boolean,
    totalUnreadCount: Int,
    archivedCount: Int
) {
    val badgeValue = when (item.route) {
        is MessagesRoute -> totalUnreadCount
        is ArchiveRoute -> 0
        else -> 0
    }
    BadgedBox(
        badge = {
            if (badgeValue > 0) {
                Badge {
                    Text(badgeValue.coerceAtMost(99).toString())
                }
            } else if (item.route is ArchiveRoute && archivedCount > 0) {
                // Subtle count indicator only when non-intrusive
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
            .padding(32.dp)
            .testTag("app_lock_gate_screen")
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
            style = MaterialTheme.typography.headlineMedium
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
                .height(50.dp)
                .testTag("unlock_biometric_button")
        ) {
            Text(stringResource(R.string.unlock_button))
        }
    }
}

private fun launchBiometricUnlock(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit
) {
    val biometricManager = BiometricManager.from(activity)
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    val canAuth = biometricManager.canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        // Device/emulator has no biometric or lockscreen PIN enrolled; unlock gracefully
        onResult(true)
        return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        }
    )

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock TRAISms")
        .setSubtitle("Verify identity to access encrypted SMS vault")
        .setAllowedAuthenticators(authenticators)
        .build()

    prompt.authenticate(promptInfo)
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}
