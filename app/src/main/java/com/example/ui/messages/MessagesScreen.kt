package com.example.ui.messages

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.LeftSwipeAction
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SmsCategory
import com.example.data.db.ThreadSummary
import com.example.data.sms.SyncProgressState
import com.example.data.sms.SystemSmsHelper
import com.example.ui.common.GlobalDeleteAllMessagesDialog
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.common.ThreadRowItem
import com.example.ui.common.visualStyle
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    selectedCategory: SmsCategory,
    threads: List<ThreadSummary>,
    categoryCounts: Map<SmsCategory, CategoryUnreadCount>,
    totalMessageCount: Int,
    searchQuery: String,
    leftSwipeAction: LeftSwipeAction,
    rightSwipeAction: LeftSwipeAction = LeftSwipeAction.ASSIGN_CATEGORY,
    activeRegionBadge: String,
    syncProgress: SyncProgressState,
    isDefaultSmsApp: Boolean,
    showSetupBanner: Boolean,
    showEmulatorDemoBanner: Boolean,
    systemSmsHelper: SystemSmsHelper,
    onSelectCategory: (SmsCategory) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenCompose: () -> Unit,
    onOpenOnboardingGuide: () -> Unit,
    onDismissSetupBanner: () -> Unit,
    onArchiveThread: (String, Boolean) -> Unit,
    onDeleteThread: (String) -> Unit,
    onAssignSenderCategory: (threadKey: String, senderTitle: String, target: SmsCategory, alwaysRule: Boolean) -> Unit,
    onDeleteAllInCurrentTab: (SmsCategory, (Int) -> Unit) -> Unit,
    onRequestCountInCurrentTab: suspend (SmsCategory) -> Int,
    onDeleteAllInApp: () -> Unit,
    onTriggerDeviceSync: () -> Unit,
    onSeedDemoInbox: () -> Unit,
    promoAutoDeleteDays: Int = 7,
    otpAutoDeleteDays: Int = 0,
    onUpdatePromoAutoDelete: (Int) -> Unit = {},
    onUpdateOtpAutoDelete: (Int) -> Unit = {},
    showUnreadOnly: Boolean = true,
    onToggleCategoryUnreadOnly: (SmsCategory, Boolean) -> Unit = { _, _ -> },
    onMarkAllReadInCurrentTab: (SmsCategory, (Int) -> Unit) -> Unit = { _, onDone -> onDone(0) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val currentCatCounts = categoryCounts[selectedCategory]
    val unreadInTab = currentCatCounts?.unreadCount ?: 0
    val totalInTab = currentCatCounts?.totalCount ?: 0

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onTriggerDeviceSync()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (systemSmsHelper.isDefaultSmsApp()) {
            val perms = buildList {
                add(Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            permissionsLauncher.launch(perms)
        } else {
            onTriggerDeviceSync()
            onOpenOnboardingGuide()
        }
    }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var moveSheetTarget by remember { mutableStateOf<ThreadSummary?>(null) }
    var tabDeleteCountDialog by remember { mutableStateOf<Int?>(null) }
    var showGlobalDeleteDialog by remember { mutableStateOf(false) }

    fun executeInboxSwipeAction(action: LeftSwipeAction, swiped: ThreadSummary) {
        val senderTitle = swiped.contactName ?: swiped.rawAddress
        when (action) {
            LeftSwipeAction.ARCHIVE -> {
                onArchiveThread(swiped.threadKey, true)
                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "Archived $senderTitle",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        onArchiveThread(swiped.threadKey, false)
                    }
                }
            }
            LeftSwipeAction.DELETE -> {
                onDeleteThread(swiped.threadKey)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Deleted conversation with $senderTitle",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            LeftSwipeAction.ASSIGN_CATEGORY -> {
                moveSheetTarget = swiped
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = activeRegionBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                isSearchExpanded = !isSearchExpanded
                                if (!isSearchExpanded && searchQuery.isNotEmpty()) {
                                    onSearchQueryChange("")
                                }
                            },
                            modifier = Modifier.testTag("inbox_search_toggle")
                        ) {
                            Icon(
                                imageVector = if (isSearchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = "Search messages"
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { overflowExpanded = true },
                                modifier = Modifier.testTag("inbox_overflow_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "More options"
                                )
                            }

                            DropdownMenu(
                                expanded = overflowExpanded,
                                onDismissRequest = { overflowExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Sync Device SMS Inbox") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Sync, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onTriggerDeviceSync()
                                    },
                                    modifier = Modifier.testTag("menu_sync_sms")
                                )
                                DropdownMenuItem(
                                    text = { Text("Default SMS & Privacy Guide") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Info, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        onOpenOnboardingGuide()
                                    },
                                    modifier = Modifier.testTag("menu_open_onboarding")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.mark_all_read_in_tab, selectedCategory.title))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.DoneAll, contentDescription = null)
                                    },
                                    enabled = unreadInTab > 0,
                                    onClick = {
                                        val targetCat = selectedCategory
                                        overflowExpanded = false
                                        onMarkAllReadInCurrentTab(targetCat) { markedCount ->
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (markedCount == 0) {
                                                        "No unread ${targetCat.title} messages"
                                                    } else {
                                                        "Marked $markedCount ${targetCat.title} message(s) as read"
                                                    }
                                                )
                                            }
                                        }
                                    },
                                    modifier = Modifier.testTag("menu_mark_all_read_in_tab")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.delete_all_in_tab, selectedCategory.title))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        scope.launch {
                                            val count = onRequestCountInCurrentTab(selectedCategory)
                                            tabDeleteCountDialog = count
                                        }
                                    },
                                    modifier = Modifier.testTag("menu_delete_all_in_tab")
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.delete_all_in_app),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.DeleteForever,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        overflowExpanded = false
                                        showGlobalDeleteDialog = true
                                    },
                                    modifier = Modifier.testTag("menu_delete_all_in_app")
                                )
                            }
                        }
                    }
                )

                AnimatedVisibility(visible = isSearchExpanded) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.search_messages_hint)) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("inbox_search_input")
                    )
                }

                if (syncProgress.isSyncing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.sync_progress_label, syncProgress.importedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                // Exhaustive 8-Category PrimaryScrollableTabRow with unread badges
                if (searchQuery.isBlank()) {
                    val tabs = SmsCategory.orderedTabs
                    val selectedIndex = tabs.indexOf(selectedCategory).coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = selectedIndex,
                        edgePadding = 12.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
                        modifier = Modifier.testTag("category_scrollable_tab_row")
                    ) {
                        tabs.forEach { category ->
                            val unread = categoryCounts[category]?.unreadCount ?: 0
                            val isSelected = category == selectedCategory
                            Tab(
                                selected = isSelected,
                                onClick = { onSelectCategory(category) },
                                modifier = Modifier.testTag("tab_${category.name.lowercase(Locale.ROOT)}"),
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = category.title,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                        if (unread > 0) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = unread.coerceAtMost(99).toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenCompose,
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.compose_new_sms)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("compose_sms_fab")
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!isDefaultSmsApp && showSetupBanner && searchQuery.isBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("inline_default_sms_banner")
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Sms,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Enable Default SMS & Offline Sync",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = onDismissSetupBanner,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Dismiss setup card",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = "Set TRAISms as Default SMS handler to import, categorize, and encrypt your messages 100% offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    val roleIntent = systemSmsHelper.createDefaultSmsRequestIntent()
                                    if (roleIntent != null) {
                                        roleLauncher.launch(roleIntent)
                                    } else {
                                        val perms = buildList {
                                            add(Manifest.permission.READ_SMS)
                                            add(Manifest.permission.SEND_SMS)
                                            add(Manifest.permission.RECEIVE_SMS)
                                            add(Manifest.permission.READ_CONTACTS)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                add(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }.toTypedArray()
                                        permissionsLauncher.launch(perms)
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("banner_set_default_sms_button")
                            ) {
                                Text("Set Default SMS")
                            }

                            OutlinedButton(
                                onClick = onOpenOnboardingGuide,
                                modifier = Modifier.testTag("banner_privacy_guide_button")
                            ) {
                                Text("Privacy Guide")
                            }
                        }
                    }
                }
            }

            if (searchQuery.isBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("category_unread_filter_bar")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleCategoryUnreadOnly(selectedCategory, !showUnreadOnly) }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MarkEmailUnread,
                                contentDescription = null,
                                tint = if (showUnreadOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.show_unread_toggle_label),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (showUnreadOnly) "Unread only ($unreadInTab)" else "All ($totalInTab)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("category_unread_filter_summary")
                            )
                        }
                        Switch(
                            checked = showUnreadOnly,
                            onCheckedChange = { enabled -> onToggleCategoryUnreadOnly(selectedCategory, enabled) },
                            modifier = Modifier.testTag("category_unread_only_switch")
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (
                searchQuery.isBlank() &&
                (selectedCategory == SmsCategory.OTP || selectedCategory == SmsCategory.PROMOTIONAL)
            ) {
                val autoDeletePresets = listOf(
                    0 to "Off",
                    1 to "1 Day",
                    3 to "3 Days",
                    7 to "7 Days",
                    15 to "15 Days",
                    30 to "30 Days"
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("inbox_auto_delete_bar")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoDelete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (selectedCategory == SmsCategory.OTP) {
                                stringResource(R.string.inbox_auto_delete_otp_label)
                            } else {
                                stringResource(R.string.inbox_auto_delete_promo_label)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        autoDeletePresets.forEach { (days, label) ->
                            FilterChip(
                                selected = (if (selectedCategory == SmsCategory.OTP) otpAutoDeleteDays else promoAutoDeleteDays) == days,
                                onClick = {
                                    if (selectedCategory == SmsCategory.OTP) {
                                        onUpdateOtpAutoDelete(days)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (days == 0) "OTP auto-delete disabled"
                                                else "OTP SMS older than $label will auto-delete"
                                            )
                                        }
                                    } else {
                                        onUpdatePromoAutoDelete(days)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (days == 0) "Promotional auto-delete disabled"
                                                else "Promotional SMS older than $label will auto-delete"
                                            )
                                        }
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .testTag(
                                        if (selectedCategory == SmsCategory.OTP) "inbox_otp_preset_$days"
                                        else "inbox_promo_preset_$days"
                                    )
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            if (threads.isEmpty()) {
                val style = selectedCategory.visualStyle()
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .widthIn(max = 420.dp)
                        .align(Alignment.Center)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(style.containerColor)
                    ) {
                        Icon(
                            imageVector = style.icon,
                            contentDescription = null,
                            tint = style.contentColor,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "No matching messages"
                            showUnreadOnly -> stringResource(
                                R.string.empty_unread_category_title,
                                selectedCategory.title
                            )
                            else -> stringResource(
                                R.string.empty_category_title,
                                selectedCategory.title
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "Try searching by sender header, contact name, or OTP digits."
                            showUnreadOnly && totalInTab > 0 -> stringResource(
                                R.string.empty_unread_with_read_subtitle,
                                totalInTab,
                                selectedCategory.title
                            )
                            else -> stringResource(
                                R.string.empty_category_subtitle,
                                selectedCategory.title
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    if (searchQuery.isBlank() && showUnreadOnly && totalInTab > 0) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = { onToggleCategoryUnreadOnly(selectedCategory, false) },
                            modifier = Modifier.testTag("empty_unread_show_all_button")
                        ) {
                            Text(
                                stringResource(
                                    R.string.show_all_in_category_button,
                                    selectedCategory.title
                                )
                            )
                        }
                    }

                    if (showEmulatorDemoBanner && searchQuery.isBlank()) {
                        Spacer(modifier = Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = onSeedDemoInbox,
                            modifier = Modifier.testTag("empty_inbox_demo_seed_button")
                        ) {
                            Icon(Icons.Outlined.Science, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_demo_seed))
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("messages_lazy_column")
                ) {
                    items(
                        items = threads,
                        key = { "${it.category.name}_${it.threadKey}" }
                    ) { thread ->
                        ThreadRowItem(
                            thread = thread,
                            isArchiveScreen = false,
                            leftSwipeAction = leftSwipeAction,
                            rightSwipeAction = rightSwipeAction,
                            showCategoryPill = searchQuery.isNotBlank(),
                            onClick = { onOpenThread(thread.threadKey) },
                            onCopyOtp = { code ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OTP", code))
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = context.getString(R.string.otp_copied_toast, code),
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            },
                            onSwipeLeftAction = { swiped ->
                                executeInboxSwipeAction(leftSwipeAction, swiped)
                            },
                            onSwipeRightAction = { swiped ->
                                executeInboxSwipeAction(rightSwipeAction, swiped)
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(start = 73.dp)
                        )
                    }
                }
            }
            }
        }
    }

    // Swipe Right: Move Sender Category Bottom Sheet
    moveSheetTarget?.let { targetThread ->
        val senderTitle = targetThread.contactName ?: targetThread.rawAddress
        MoveSenderCategoryBottomSheet(
            senderTitle = senderTitle,
            currentCategory = targetThread.category,
            onDismiss = { moveSheetTarget = null },
            onConfirm = { chosenCat, saveRule ->
                moveSheetTarget = null
                onAssignSenderCategory(targetThread.threadKey, senderTitle, chosenCat, saveRule)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = if (saveRule) {
                            "Always routing $senderTitle to ${chosenCat.title}"
                        } else {
                            "Moved $senderTitle to ${chosenCat.title}"
                        },
                        duration = SnackbarDuration.Short
                    )
                }
            }
        )
    }

    // Per-Tab "Delete All in [Current Tab]" Confirmation Dialog
    tabDeleteCountDialog?.let { count ->
        AlertDialog(
            onDismissRequest = { tabDeleteCountDialog = null },
            title = {
                Text("Delete all in ${selectedCategory.title}?")
            },
            text = {
                Text(
                    if (count == 0) {
                        "There are currently 0 messages in ${selectedCategory.title}."
                    } else {
                        "Permanently delete $count message(s) in ${selectedCategory.title} from both TRAISms and the Android system SMS database?"
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cat = selectedCategory
                        tabDeleteCountDialog = null
                        if (count > 0) {
                            onDeleteAllInCurrentTab(cat) { deleted ->
                                scope.launch {
                                    snackbarHostState.showSnackbar("Deleted $deleted ${cat.title} message(s)")
                                }
                            }
                        }
                    },
                    enabled = count > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_tab_button")
                ) {
                    Text("Delete ($count)")
                }
            },
            dismissButton = {
                TextButton(onClick = { tabDeleteCountDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // App-Wide "Delete All Messages in App" two-step dialog
    if (showGlobalDeleteDialog) {
        GlobalDeleteAllMessagesDialog(
            totalCount = totalMessageCount,
            onDismiss = { showGlobalDeleteDialog = false },
            onConfirmDeleteAll = {
                showGlobalDeleteDialog = false
                onDeleteAllInApp()
                scope.launch {
                    snackbarHostState.showSnackbar("All SMS messages permanently deleted")
                }
            }
        )
    }
}
