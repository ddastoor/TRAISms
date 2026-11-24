package com.example.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.domain.classifier.PhoneAndSenderNormalizer
import com.example.domain.classifier.SenderAddressType
import com.example.ui.common.CategoryBadgePill
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.common.formatFullDateTime
import com.example.ui.theme.CategoryOtpBg
import com.example.ui.theme.CategoryOtpFg
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadDetailScreen(
    threadKey: String,
    messages: List<SmsMessageEntity>,
    onBack: () -> Unit,
    onMarkThreadRead: (String) -> Unit,
    onSendReply: (destinationAddress: String, text: String, onResult: (Result<String>) -> Unit) -> Unit,
    onDeleteSingleMessage: (Long) -> Unit,
    onToggleArchive: (String, Boolean) -> Unit,
    onAssignCategory: (threadKey: String, senderTitle: String, target: SmsCategory, alwaysRule: Boolean) -> Unit,
    onForwardMessage: (String) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var replyText by remember { mutableStateOf("") }
    var actionMenuMessage by remember { mutableStateOf<SmsMessageEntity?>(null) }
    var deleteCandidate by remember { mutableStateOf<SmsMessageEntity?>(null) }
    var showMoveSheet by remember { mutableStateOf(false) }

    val latestMessage = messages.lastOrNull()
    val rawAddress = latestMessage?.rawAddress ?: threadKey
    val senderTitle = latestMessage?.contactName ?: rawAddress
    val currentCategory = latestMessage?.category ?: SmsCategory.PERSONAL
    val isArchived = latestMessage?.isArchived == true
    val canReply = remember(rawAddress) {
        PhoneAndSenderNormalizer.classifySenderType(rawAddress) != SenderAddressType.ALPHANUMERIC_HEADER
    }

    LaunchedEffect(threadKey, messages.size) {
        onMarkThreadRead(threadKey)
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("thread_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = senderTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CategoryBadgePill(category = currentCategory)
                                if (latestMessage?.contactName != null) {
                                    Text(
                                        text = rawAddress,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showMoveSheet = true },
                            modifier = Modifier.testTag("thread_move_category_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DriveFileMove,
                                contentDescription = "Move sender category"
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleArchive(threadKey, !isArchived)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (!isArchived) "Conversation archived" else "Conversation unarchived"
                                    )
                                }
                            },
                            modifier = Modifier.testTag("thread_archive_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                                contentDescription = if (isArchived) "Unarchive" else "Archive"
                            )
                        }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                if (canReply) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        OutlinedTextField(
                            value = replyText,
                            onValueChange = { replyText = it },
                            placeholder = { Text(stringResource(R.string.reply_placeholder)) },
                            maxLines = 4,
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("thread_reply_input")
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                val outgoing = replyText.trim()
                                if (outgoing.isNotEmpty()) {
                                    replyText = ""
                                    onSendReply(rawAddress, outgoing) { res ->
                                        if (res.isFailure) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    res.exceptionOrNull()?.message ?: "Unable to send SMS"
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = replyText.isNotBlank(),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("thread_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_sms_cd)
                            )
                        }
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = "One-way alphanumeric sender header ($rawAddress) — replies are not supported by carrier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .testTag("thread_messages_list")
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { msg ->
                val isIncoming = msg.isIncoming
                val bubbleShape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isIncoming) 4.dp else 16.dp,
                    bottomEnd = if (isIncoming) 16.dp else 4.dp
                )
                Column(
                    horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = if (isIncoming) MaterialTheme.colorScheme.surfaceContainer
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (isIncoming) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onPrimary,
                        shape = bubbleShape,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .clip(bubbleShape)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    actionMenuMessage = msg
                                }
                            )
                            .testTag("thread_message_bubble_${msg.id}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = msg.body,
                                style = MaterialTheme.typography.bodyLarge
                            )

                            msg.extractedOtp?.let { otpCode ->
                                Spacer(modifier = Modifier.height(8.dp))
                                val otpChipShape = RoundedCornerShape(8.dp)
                                Surface(
                                    color = CategoryOtpBg,
                                    contentColor = CategoryOtpFg,
                                    shape = otpChipShape,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .border(1.dp, CategoryOtpFg.copy(alpha = 0.4f), otpChipShape)
                                        .clip(otpChipShape)
                                        .combinedClickable(
                                            onClick = {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                clipboard?.setPrimaryClip(ClipData.newPlainText("OTP", otpCode))
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.otp_copied_toast, otpCode)
                                                    )
                                                }
                                            },
                                            onLongClick = {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                actionMenuMessage = msg
                                            }
                                        )
                                        .testTag("bubble_copy_otp_${msg.id}")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy OTP $otpCode",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.copy_otp_chip, otpCode),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = JetBrainsMonoFamily,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
                    ) {
                        Text(
                            text = "${formatFullDateTime(msg.timestampMillis)} • ${msg.classificationSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { deleteCandidate = msg },
                            modifier = Modifier
                                .size(28.dp)
                                .testTag("delete_single_msg_${msg.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete message",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    actionMenuMessage?.let { selectedMsg ->
        val actionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val optionShape = RoundedCornerShape(12.dp)

        ModalBottomSheet(
            onDismissRequest = { actionMenuMessage = null },
            sheetState = actionSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.testTag("message_actions_prompt")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.message_actions_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = selectedMsg.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = optionShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(optionShape)
                        .clickable {
                            val bodyToCopy = selectedMsg.body
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("SMS", bodyToCopy))
                            val copiedToast = context.getString(R.string.message_copied_toast)
                            actionMenuMessage = null
                            scope.launch {
                                snackbarHostState.showSnackbar(copiedToast)
                            }
                        }
                        .testTag("message_action_copy")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.message_action_copy),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = optionShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(optionShape)
                        .clickable {
                            val bodyToForward = selectedMsg.body
                            actionMenuMessage = null
                            onForwardMessage(bodyToForward)
                        }
                        .testTag("message_action_forward")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.message_action_forward),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Message") },
            text = { Text(stringResource(R.string.delete_message_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        val idToDelete = candidate.id
                        deleteCandidate = null
                        onDeleteSingleMessage(idToDelete)
                        if (messages.size <= 1) {
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMoveSheet) {
        MoveSenderCategoryBottomSheet(
            senderTitle = senderTitle,
            currentCategory = currentCategory,
            onDismiss = { showMoveSheet = false },
            onConfirm = { targetCat, alwaysRule ->
                showMoveSheet = false
                onAssignCategory(threadKey, senderTitle, targetCat, alwaysRule)
                scope.launch {
                    snackbarHostState.showSnackbar("Moved $senderTitle to ${targetCat.title}")
                }
            }
        )
    }
}
