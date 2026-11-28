package com.example.ui.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Subject
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.MmsAttachmentEntity
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.PendingMmsAttachment
import com.example.data.sms.RcsInteropCodec
import com.example.data.sms.SystemSmsHelper
import com.example.domain.classifier.PhoneAndSenderNormalizer
import com.example.domain.classifier.SenderAddressType
import com.example.ui.common.CategoryBadgePill
import com.example.ui.common.MoveSenderCategoryBottomSheet
import com.example.ui.common.formatFullDateTime
import com.example.ui.theme.CategoryOtpBg
import com.example.ui.theme.CategoryOtpFg
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSmsTextPreferences
import com.example.ui.theme.smsMessageBodyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
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
    onForwardMessage: (String) -> Unit = {},
    attachmentsByMessageId: Map<Long, List<MmsAttachmentEntity>> = emptyMap(),
    rcsChatsEnabled: Boolean = true,
    contactCache: ContactLookupCache? = null,
    systemSmsHelper: SystemSmsHelper? = null,
    onSendReplyWithQuote: ((destinationAddress: String, text: String, replyToMessageId: Long?, onResult: (Result<String>) -> Unit) -> Unit)? = null,
    onSendMmsReply: ((recipients: List<String>, text: String, subject: String?, attachments: List<PendingMmsAttachment>, replyToMessageId: Long?, onResult: (Result<String>) -> Unit) -> Unit)? = null,
    onToggleReaction: (Long, String) -> Unit = { _, _ -> },
    onDownloadMms: (Long) -> Unit = {},
    onRetryMessage: (Long) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val smsTextPrefs = LocalSmsTextPreferences.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var replyText by remember { mutableStateOf("") }
    var mmsSubject by remember { mutableStateOf("") }
    var showSubjectInput by remember { mutableStateOf(false) }
    var replyTarget by remember { mutableStateOf<SmsMessageEntity?>(null) }
    var activeEmojiBarMessageId by remember { mutableStateOf<Long?>(null) }
    var actionMenuMessage by remember { mutableStateOf<SmsMessageEntity?>(null) }
    var deleteCandidate by remember { mutableStateOf<SmsMessageEntity?>(null) }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showContactVCardDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    val pendingAttachments = remember { mutableStateListOf<PendingMmsAttachment>() }

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isEmpty() || systemSmsHelper == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    systemSmsHelper.stageLocalAttachmentFromUri(
                        sourceUri = uri,
                        fallbackMime = "image/jpeg",
                        fallbackName = "media_${System.currentTimeMillis()}"
                    ).getOrNull()
                }
            }
            pendingAttachments.addAll(staged)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null || systemSmsHelper == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                systemSmsHelper.stageLocalAttachmentFromUri(
                    sourceUri = uri,
                    fallbackMime = "application/octet-stream",
                    fallbackName = "attachment_${System.currentTimeMillis()}"
                ).getOrNull()
            }
            if (staged != null) {
                pendingAttachments.add(staged)
            }
        }
    }

    val latestMessage = messages.lastOrNull()
    val groupRecipients = remember(messages) {
        val rawJson = messages.lastOrNull { !it.groupRecipientsJson.isNullOrBlank() }?.groupRecipientsJson
        if (rawJson.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                Json.decodeFromString(ListSerializer(String.serializer()), rawJson)
            }.getOrDefault(emptyList())
        }
    }
    val rawAddress = latestMessage?.rawAddress ?: threadKey
    val senderTitle = if (groupRecipients.size > 1) {
        latestMessage?.contactName ?: groupRecipients.joinToString(", ")
    } else {
        latestMessage?.contactName ?: rawAddress
    }
    val currentCategory = latestMessage?.category ?: SmsCategory.PERSONAL
    val isArchived = latestMessage?.isArchived == true
    val canReply = remember(rawAddress, groupRecipients) {
        groupRecipients.size > 1 ||
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
                                if (groupRecipients.size > 1) {
                                    Text(
                                        text = "${groupRecipients.size} participants",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("thread_group_participants_label")
                                    )
                                } else if (latestMessage?.contactName != null) {
                                    Text(
                                        text = rawAddress,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        replyTarget?.let { target ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                                    .testTag("reply_context_banner")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Replying to ${if (target.isIncoming) (target.contactName ?: target.rawAddress) else "You"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = target.body,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { replyTarget = null },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("cancel_reply_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Cancel reply",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        PendingAttachmentsStrip(
                            attachments = pendingAttachments,
                            onRemoveAt = { idx -> pendingAttachments.removeAt(idx) },
                            modifier = Modifier.testTag("thread_pending_attachments_strip")
                        )

                        if (showSubjectInput) {
                            OutlinedTextField(
                                value = mmsSubject,
                                onValueChange = { mmsSubject = it },
                                singleLine = true,
                                label = { Text(stringResource(R.string.mms_subject_label)) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .testTag("thread_mms_subject_input")
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            IconButton(
                                onClick = { showAttachmentSheet = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("thread_attach_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AddCircleOutline,
                                    contentDescription = "Attach media or file",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = { showSubjectInput = !showSubjectInput },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("thread_toggle_subject_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Subject,
                                    contentDescription = "Toggle MMS subject",
                                    tint = if (showSubjectInput) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedTextField(
                                value = replyText,
                                onValueChange = { replyText = it },
                                textStyle = smsMessageBodyStyle(
                                    MaterialTheme.typography.bodyLarge,
                                    smsTextPrefs.fontSize,
                                    smsTextPrefs.bold
                                ),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.reply_placeholder),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                maxLines = 4,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("thread_reply_input")
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val canSend = replyText.isNotBlank() ||
                                pendingAttachments.isNotEmpty() ||
                                mmsSubject.isNotBlank()
                            IconButton(
                                onClick = {
                                    val outgoing = replyText.trim()
                                    val subject = mmsSubject.trim().takeIf { it.isNotEmpty() }
                                    val quoteId = replyTarget?.id
                                    val stagedAtts = pendingAttachments.toList()
                                    val targetRecipients = if (groupRecipients.size > 1) {
                                        groupRecipients
                                    } else {
                                        listOf(rawAddress)
                                    }
                                    val isMmsSend = stagedAtts.isNotEmpty() ||
                                        subject != null ||
                                        targetRecipients.size > 1

                                    if (!canSend) return@IconButton

                                    replyText = ""
                                    mmsSubject = ""
                                    showSubjectInput = false
                                    replyTarget = null
                                    pendingAttachments.clear()

                                    val onSendComplete: (Result<String>) -> Unit = { res ->
                                        if (res.isFailure) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar(
                                                    res.exceptionOrNull()?.message ?: "Unable to send message"
                                                )
                                            }
                                        }
                                    }

                                    if (isMmsSend && onSendMmsReply != null) {
                                        onSendMmsReply(
                                            targetRecipients,
                                            outgoing,
                                            subject,
                                            stagedAtts,
                                            quoteId,
                                            onSendComplete
                                        )
                                    } else if (quoteId != null && onSendReplyWithQuote != null) {
                                        onSendReplyWithQuote(rawAddress, outgoing, quoteId, onSendComplete)
                                    } else {
                                        onSendReply(rawAddress, outgoing, onSendComplete)
                                    }
                                },
                                enabled = canSend,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val bubbleMaxWidth = (maxWidth * 0.85f).coerceIn(240.dp, 420.dp)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("thread_messages_list")
            ) {
            items(
                items = messages,
                key = { it.id }
            ) { msg ->
                val isIncoming = msg.isIncoming
                val attachments = attachmentsByMessageId[msg.id].orEmpty()
                val reactions = remember(msg.reactionsJson) {
                    RcsInteropCodec.decodeReactions(msg.reactionsJson)
                }
                val bubbleShape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isIncoming) 4.dp else 16.dp,
                    bottomEnd = if (isIncoming) 16.dp else 4.dp
                )
                Column(
                    horizontalAlignment = if (isIncoming) Alignment.Start else Alignment.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(msg.id, canReply) {
                            if (!canReply) return@pointerInput
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    if (kotlin.math.abs(totalDrag) > 56f) {
                                        replyTarget = msg
                                    }
                                    totalDrag = 0f
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                }
                            )
                        }
                ) {
                    Surface(
                        color = if (isIncoming) MaterialTheme.colorScheme.surfaceContainer
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (isIncoming) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onPrimary,
                        shape = bubbleShape,
                        modifier = Modifier
                            .widthIn(max = bubbleMaxWidth)
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
                            QuotedReplyPreviewCard(
                                messageId = msg.id,
                                sender = msg.replyToSender,
                                snippet = msg.replyToSnippet,
                                isIncoming = isIncoming,
                                onClick = {
                                    val targetIdx = messages.indexOfFirst { it.id == msg.replyToMessageId }
                                    if (targetIdx >= 0) {
                                        scope.launch { listState.animateScrollToItem(targetIdx) }
                                    }
                                }
                            )

                            msg.mmsSubject?.takeIf { it.isNotBlank() }?.let { subj ->
                                Text(
                                    text = "Subject: $subj",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(bottom = 4.dp)
                                        .testTag("mms_subject_header_${msg.id}")
                                )
                            }

                            if (msg.deliveryStatus == "PENDING_DOWNLOAD") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .testTag("mms_download_card_${msg.id}")
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.mms_offline_notice),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        OutlinedButton(
                                            onClick = { onDownloadMms(msg.id) },
                                            modifier = Modifier.testTag("download_mms_button_${msg.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(R.string.mms_download_cta))
                                        }
                                    }
                                }
                            }

                            attachments.forEach { att ->
                                MmsAttachmentBubbleContent(
                                    attachment = att,
                                    isIncoming = isIncoming,
                                    systemSmsHelper = systemSmsHelper
                                )
                            }

                            Text(
                                text = msg.body,
                                style = smsMessageBodyStyle(
                                    MaterialTheme.typography.bodyLarge,
                                    smsTextPrefs.fontSize,
                                    smsTextPrefs.bold
                                ),
                                modifier = Modifier.testTag("thread_message_text_${msg.id}")
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

                    if (reactions.isNotEmpty()) {
                        ReactionPillsRow(
                            messageId = msg.id,
                            reactions = reactions,
                            onToggleReaction = { emoji -> onToggleReaction(msg.id, emoji) }
                        )
                    }

                    if (activeEmojiBarMessageId == msg.id && rcsChatsEnabled) {
                        QuickEmojiReactionBar(
                            messageId = msg.id,
                            onSelectEmoji = { emoji ->
                                activeEmojiBarMessageId = null
                                onToggleReaction(msg.id, emoji)
                            }
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                            .testTag("thread_metadata_row_${msg.id}")
                    ) {
                        Text(
                            text = "${formatFullDateTime(msg.timestampMillis)} • ${msg.classificationSource}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isIncoming && msg.deliveryStatus != "NONE") {
                            DeliveryStatusIndicator(
                                messageId = msg.id,
                                deliveryStatus = msg.deliveryStatus,
                                onRetry = { onRetryMessage(msg.id) }
                            )
                        }
                        if (canReply) {
                            IconButton(
                                onClick = { replyTarget = msg },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("reply_msg_button_${msg.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Reply,
                                    contentDescription = "Reply to message",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        if (rcsChatsEnabled) {
                            IconButton(
                                onClick = {
                                    activeEmojiBarMessageId =
                                        if (activeEmojiBarMessageId == msg.id) null else msg.id
                                },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("react_msg_button_${msg.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AddReaction,
                                    contentDescription = "React to message",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
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

    if (showAttachmentSheet) {
        AttachmentPickerBottomSheet(
            onDismiss = { showAttachmentSheet = false },
            onPickMedia = {
                mediaPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
            onPickContact = { showContactVCardDialog = true },
            onPickLocation = { showLocationDialog = true }
        )
    }

    if (showContactVCardDialog) {
        ContactVCardPickerDialog(
            contactCache = contactCache,
            onDismiss = { showContactVCardDialog = false },
            onSelectContact = { selected ->
                showContactVCardDialog = false
                val vCard = RcsInteropCodec.buildVCardContent(selected.displayName, selected.phoneNumber)
                val safeFile = "${selected.displayName.replace(Regex("[^A-Za-z0-9]"), "_")}.vcf"
                val staged = systemSmsHelper?.stageTextAttachment("text/x-vcard", safeFile, vCard)?.getOrNull()
                    ?: PendingMmsAttachment(
                        contentType = "text/x-vcard",
                        fileName = safeFile,
                        sizeBytes = vCard.length.toLong(),
                        textContent = vCard
                    )
                pendingAttachments.add(staged)
            }
        )
    }

    if (showLocationDialog) {
        OfflineLocationTextDialog(
            onDismiss = { showLocationDialog = false },
            onConfirmLocation = { note ->
                showLocationDialog = false
                val content = "Location: $note"
                val staged = systemSmsHelper?.stageTextAttachment("text/plain", "location.txt", content)?.getOrNull()
                    ?: PendingMmsAttachment(
                        contentType = "text/plain",
                        fileName = "location.txt",
                        sizeBytes = content.length.toLong(),
                        textContent = content
                    )
                pendingAttachments.add(staged)
            }
        )
    }
}
