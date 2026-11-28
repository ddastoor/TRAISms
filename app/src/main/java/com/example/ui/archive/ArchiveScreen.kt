package com.example.ui.archive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.LeftSwipeAction
import com.example.data.db.SmsCategory
import com.example.data.db.ThreadSummary
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.common.ThreadRowItem
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    archivedThreads: List<ThreadSummary>,
    onOpenThread: (String) -> Unit,
    onSetThreadArchived: (String, Boolean) -> Unit,
    onSetThreadsArchived: (List<String>, Boolean) -> Unit,
    onDeleteThreads: (List<String>) -> Unit,
    onAssignSenderCategory: (threadKey: String, senderTitle: String, target: SmsCategory, alwaysRule: Boolean) -> Unit,
    leftSwipeAction: LeftSwipeAction = LeftSwipeAction.ARCHIVE,
    rightSwipeAction: LeftSwipeAction = LeftSwipeAction.ASSIGN_CATEGORY
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedThreadKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var moveSheetTarget by remember { mutableStateOf<ThreadSummary?>(null) }

    fun executeArchiveSwipeAction(action: LeftSwipeAction, swiped: ThreadSummary) {
        val senderTitle = swiped.contactName ?: swiped.rawAddress
        when (action) {
            LeftSwipeAction.ARCHIVE -> {
                selectedThreadKeys = selectedThreadKeys - swiped.threadKey
                onSetThreadArchived(swiped.threadKey, false)
                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "Unarchived $senderTitle to ${swiped.category.title}",
                        actionLabel = "Undo",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        onSetThreadArchived(swiped.threadKey, true)
                    }
                }
            }
            LeftSwipeAction.DELETE -> {
                selectedThreadKeys = selectedThreadKeys - swiped.threadKey
                onDeleteThreads(listOf(swiped.threadKey))
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

    val filteredThreads by remember(archivedThreads, searchQuery) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase(Locale.ROOT)
            if (q.isEmpty()) archivedThreads
            else archivedThreads.filter {
                it.rawAddress.lowercase(Locale.ROOT).contains(q) ||
                    (it.contactName?.lowercase(Locale.ROOT)?.contains(q) == true) ||
                    it.latestBody.lowercase(Locale.ROOT).contains(q)
            }
        }
    }

    val isMultiSelect = selectedThreadKeys.isNotEmpty()
    BackHandler(enabled = isMultiSelect) {
        selectedThreadKeys = emptySet()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        if (isMultiSelect) {
                            IconButton(onClick = { selectedThreadKeys = emptySet() }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Clear selection")
                            }
                        }
                    },
                    title = {
                        Text(
                            text = if (isMultiSelect) "${selectedThreadKeys.size} Selected"
                            else stringResource(R.string.nav_archive),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (isMultiSelect) {
                            IconButton(
                                onClick = {
                                    val keys = selectedThreadKeys.toList()
                                    selectedThreadKeys = emptySet()
                                    onSetThreadsArchived(keys, false)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Unarchived ${keys.size} conversation(s)")
                                    }
                                },
                                modifier = Modifier.testTag("archive_batch_unarchive_button")
                            ) {
                                Icon(Icons.Outlined.Unarchive, contentDescription = "Unarchive selected")
                            }
                            IconButton(
                                onClick = {
                                    val keys = selectedThreadKeys.toList()
                                    selectedThreadKeys = emptySet()
                                    onDeleteThreads(keys)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Deleted ${keys.size} conversation(s)")
                                    }
                                },
                                modifier = Modifier.testTag("archive_batch_delete_button")
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete selected",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                )

                if (archivedThreads.isNotEmpty() && !isMultiSelect) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search archived conversations…") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("archive_search_input")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (filteredThreads.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(32.dp)
                        .widthIn(max = 400.dp)
                        .align(Alignment.Center)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Archive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching archived conversations"
                        else stringResource(R.string.archive_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "Try a different sender name, phone number, or message keyword."
                        else stringResource(R.string.archive_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("archive_lazy_column")
                ) {
                    items(
                        items = filteredThreads,
                        key = { it.threadKey }
                    ) { thread ->
                        val isSelected = thread.threadKey in selectedThreadKeys
                        ThreadRowItem(
                            thread = thread,
                            isArchiveScreen = true,
                            leftSwipeAction = leftSwipeAction,
                            rightSwipeAction = rightSwipeAction,
                            showCategoryPill = true,
                            isSelected = isSelected,
                            onClick = {
                                if (isMultiSelect) {
                                    selectedThreadKeys = if (isSelected) {
                                        selectedThreadKeys - thread.threadKey
                                    } else {
                                        selectedThreadKeys + thread.threadKey
                                    }
                                } else {
                                    onOpenThread(thread.threadKey)
                                }
                            },
                            onLongClick = {
                                selectedThreadKeys = if (isSelected) {
                                    selectedThreadKeys - thread.threadKey
                                } else {
                                    selectedThreadKeys + thread.threadKey
                                }
                            },
                            onCopyOtp = { otpCode ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("OTP", otpCode))
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.otp_copied_toast, otpCode)
                                    )
                                }
                            },
                            onSwipeLeftAction = { swiped ->
                                executeArchiveSwipeAction(leftSwipeAction, swiped)
                            },
                            onSwipeRightAction = { swiped ->
                                executeArchiveSwipeAction(rightSwipeAction, swiped)
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            modifier = Modifier.padding(start = 73.dp, end = 16.dp)
                        )
                    }
                }
            }
        }
    }

    moveSheetTarget?.let { target ->
        val senderTitle = target.contactName ?: target.rawAddress
        MoveSenderCategoryBottomSheet(
            senderTitle = senderTitle,
            currentCategory = target.category,
            onDismiss = { moveSheetTarget = null },
            onConfirm = { chosenCat, saveRule ->
                moveSheetTarget = null
                onAssignSenderCategory(target.threadKey, senderTitle, chosenCat, saveRule)
                scope.launch {
                    snackbarHostState.showSnackbar("Assigned $senderTitle to ${chosenCat.title}")
                }
            }
        )
    }
}
