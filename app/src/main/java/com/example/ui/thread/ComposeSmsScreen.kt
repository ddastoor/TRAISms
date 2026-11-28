package com.example.ui.thread

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.PendingMmsAttachment
import com.example.data.sms.RcsInteropCodec
import com.example.data.sms.SimpleContact
import com.example.data.sms.SystemSmsHelper
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSmsTextPreferences
import com.example.ui.theme.smsMessageBodyStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeSmsScreen(
    initialRecipient: String,
    initialBody: String,
    contactCache: ContactLookupCache,
    onBack: () -> Unit,
    onSendSms: (recipient: String, body: String, onResult: (Result<String>) -> Unit) -> Unit,
    systemSmsHelper: SystemSmsHelper? = null,
    onSendMms: ((recipients: List<String>, body: String, subject: String?, attachments: List<PendingMmsAttachment>, onResult: (Result<String>) -> Unit) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val smsTextPrefs = LocalSmsTextPreferences.current

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var recipient by rememberSaveable(initialRecipient) { mutableStateOf(initialRecipient) }
    var messageBody by rememberSaveable(initialBody) { mutableStateOf(initialBody) }
    var mmsSubject by rememberSaveable { mutableStateOf("") }
    var showSubjectInput by rememberSaveable { mutableStateOf(false) }
    val groupRecipients = remember { mutableStateListOf<String>() }
    val pendingAttachments = remember { mutableStateListOf<PendingMmsAttachment>() }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showContactVCardDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }

    var contactSuggestions by remember { mutableStateOf<List<SimpleContact>>(emptyList()) }
    val bodyFocusRequester = remember { FocusRequester() }

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

    LaunchedEffect(recipient) {
        contactSuggestions = withContext(Dispatchers.IO) {
            contactCache.searchContacts(recipient)
        }
    }

    val allRecipients = (groupRecipients + listOfNotNull(recipient.trim().takeIf { it.isNotEmpty() })).distinct()
    val isMmsMode = allRecipients.size > 1 || pendingAttachments.isNotEmpty() || mmsSubject.isNotBlank()
    val segments = ((messageBody.length.coerceAtLeast(1) - 1) / 160) + 1

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
                            modifier = Modifier.testTag("compose_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    title = {
                        Text(
                            text = stringResource(R.string.compose_new_sms),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    PendingAttachmentsStrip(
                        attachments = pendingAttachments,
                        onRemoveAt = { idx -> pendingAttachments.removeAt(idx) },
                        modifier = Modifier.testTag("compose_pending_attachments_strip")
                    )

                    if (showSubjectInput) {
                        OutlinedTextField(
                            value = mmsSubject,
                            onValueChange = { mmsSubject = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.mms_subject_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .testTag("compose_mms_subject_input")
                        )
                    }

                    OutlinedTextField(
                        value = messageBody,
                        onValueChange = { messageBody = it },
                        textStyle = smsMessageBodyStyle(
                            MaterialTheme.typography.bodyLarge,
                            smsTextPrefs.fontSize,
                            smsTextPrefs.bold
                        ),
                        label = {
                            Text(
                                text = stringResource(R.string.reply_placeholder),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        minLines = 1,
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(bodyFocusRequester)
                            .testTag("compose_body_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            IconButton(
                                onClick = { showAttachmentSheet = true },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("compose_attach_button")
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
                                    .testTag("compose_toggle_subject_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Subject,
                                    contentDescription = "Toggle MMS subject",
                                    tint = if (showSubjectInput) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isMmsMode) {
                                    "MMS • ${pendingAttachments.size} file(s)"
                                } else {
                                    "${messageBody.length} chars • $segments SMS part(s)"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("compose_char_counter")
                            )
                        }
                        val canSend = allRecipients.isNotEmpty() &&
                            (messageBody.isNotBlank() || pendingAttachments.isNotEmpty() || mmsSubject.isNotBlank())
                        Button(
                            onClick = {
                                if (!canSend) return@Button
                                val onComplete: (Result<String>) -> Unit = { result ->
                                    if (result.isFailure) {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                result.exceptionOrNull()?.message ?: "Failed to send message"
                                            )
                                        }
                                    }
                                }
                                if (isMmsMode && onSendMms != null) {
                                    onSendMms(
                                        allRecipients,
                                        messageBody,
                                        mmsSubject.trim().takeIf { it.isNotEmpty() },
                                        pendingAttachments.toList(),
                                        onComplete
                                    )
                                } else {
                                    onSendSms(allRecipients.first(), messageBody, onComplete)
                                }
                            },
                            enabled = canSend,
                            modifier = Modifier.testTag("compose_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isMmsMode) "Send MMS" else "Send SMS",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.recipient_label)) },
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    trailingIcon = if (recipient.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { recipient = "" },
                                modifier = Modifier.testTag("compose_recipient_clear")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.clear_recipient_cd)
                                )
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { runCatching { bodyFocusRequester.requestFocus() } }
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("compose_recipient_input")
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(
                    onClick = {
                        val trimmed = recipient.trim()
                        if (trimmed.isNotEmpty() && trimmed !in groupRecipients) {
                            groupRecipients.add(trimmed)
                            recipient = ""
                        }
                    },
                    enabled = recipient.isNotBlank(),
                    modifier = Modifier.testTag("compose_add_group_recipient_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Add group recipient"
                    )
                }
            }

            if (groupRecipients.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("compose_group_recipients_row")
                ) {
                    itemsIndexed(groupRecipients) { index, addr ->
                        InputChip(
                            selected = true,
                            onClick = { groupRecipients.removeAt(index) },
                            label = { Text(addr) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove $addr",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .testTag("compose_remove_group_recipient_$index")
                                )
                            }
                        )
                    }
                }
            }

            if (contactSuggestions.isNotEmpty()) {
                Text(
                    text = "Contacts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("compose_contacts_list")
                ) {
                    items(
                        items = contactSuggestions,
                        key = { "${it.displayName}_${it.phoneNumber}" }
                    ) { contact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    recipient = contact.phoneNumber
                                    runCatching { bodyFocusRequester.requestFocus() }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Text(
                                    text = contact.displayName.take(2).uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = contact.phoneNumber,
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(start = 66.dp)
                        )
                    }
                }
            } else if (recipient.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { bodyFocusRequester.requestFocus() } }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("compose_direct_recipient_hint")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.compose_send_directly_to, recipient.trim()),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.compose_direct_recipient_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
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
