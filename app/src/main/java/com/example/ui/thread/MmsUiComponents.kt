package com.example.ui.thread

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.db.MmsAttachmentEntity
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.MessageReaction
import com.example.data.sms.PendingMmsAttachment
import com.example.data.sms.RcsInteropCodec
import com.example.data.sms.SimpleContact
import com.example.data.sms.SystemSmsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerBottomSheet(
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onPickContact: () -> Unit,
    onPickLocation: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("attachment_picker_sheet")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Add MMS Attachment",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            AttachmentOptionRow(
                icon = Icons.Outlined.Image,
                label = stringResource(R.string.attach_media_label),
                subtitle = "Select photos or videos from your device",
                testTag = "attach_option_media",
                onClick = {
                    onDismiss()
                    onPickMedia()
                }
            )
            AttachmentOptionRow(
                icon = Icons.Outlined.AttachFile,
                label = stringResource(R.string.attach_file_label),
                subtitle = "Attach audio, PDF, or document",
                testTag = "attach_option_file",
                onClick = {
                    onDismiss()
                    onPickFile()
                }
            )
            AttachmentOptionRow(
                icon = Icons.Outlined.ContactPage,
                label = stringResource(R.string.attach_contact_label),
                subtitle = "Share a saved contact as a .vcf card",
                testTag = "attach_option_contact",
                onClick = {
                    onDismiss()
                    onPickContact()
                }
            )
            AttachmentOptionRow(
                icon = Icons.Outlined.LocationOn,
                label = stringResource(R.string.attach_location_label),
                subtitle = "Share an offline location or landmark note",
                testTag = "attach_option_location",
                onClick = {
                    onDismiss()
                    onPickLocation()
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AttachmentOptionRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    testTag: String,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ContactVCardPickerDialog(
    contactCache: ContactLookupCache?,
    onDismiss: () -> Unit,
    onSelectContact: (SimpleContact) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<SimpleContact>>(emptyList()) }

    LaunchedEffect(query, contactCache) {
        contacts = withContext(Dispatchers.IO) {
            contactCache?.searchContacts(query).orEmpty()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("contact_vcard_picker_dialog"),
        title = { Text("Share Contact Card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search contacts") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (contacts.isEmpty()) {
                    Text(
                        text = "No saved contacts found or Contacts permission not granted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(contacts, key = { "${it.displayName}_${it.phoneNumber}" }) { c ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectContact(c) }
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContactPage,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(text = c.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = c.phoneNumber,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun OfflineLocationTextDialog(
    onDismiss: () -> Unit,
    onConfirmLocation: (String) -> Unit
) {
    var locationText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("location_text_share_dialog"),
        title = { Text("Share Offline Location Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "TRAISms uses zero location or internet permissions. Enter an address, landmark, or meeting spot to share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = locationText,
                    onValueChange = { locationText = it },
                    label = { Text("Address or meeting place") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("location_text_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = locationText.trim()
                    if (trimmed.isNotEmpty()) {
                        onConfirmLocation(trimmed)
                    }
                },
                enabled = locationText.isNotBlank(),
                modifier = Modifier.testTag("location_text_confirm_button")
            ) {
                Text("Attach")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PendingAttachmentsStrip(
    attachments: List<PendingMmsAttachment>,
    onRemoveAt: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        itemsIndexed(attachments) { index, att ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Icon(
                        imageVector = iconForMime(att.contentType),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = att.fileName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    IconButton(
                        onClick = { onRemoveAt(index) },
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("remove_pending_attachment_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Remove attachment",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MmsAttachmentBubbleContent(
    attachment: MmsAttachmentEntity,
    isIncoming: Boolean,
    systemSmsHelper: SystemSmsHelper?
) {
    val context = LocalContext.current
    val mime = attachment.contentType.lowercase(Locale.ROOT)
    var showFullScreenImage by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .testTag("mms_attachment_${attachment.id}")
    ) {
        when {
            mime.startsWith("image/") -> {
                val bitmap by produceState<Bitmap?>(initialValue = null, attachment.localFilePath) {
                    value = withContext(Dispatchers.IO) {
                        decodeSampledBitmap(attachment.localFilePath, maxDim = 900)
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = attachment.fileName ?: "MMS Image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 220.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showFullScreenImage = true }
                            .testTag("mms_image_preview_${attachment.id}")
                    )
                } else {
                    MediaFallbackCard(
                        icon = Icons.Outlined.Image,
                        title = attachment.fileName ?: "Photo",
                        subtitle = formatSize(attachment.sizeBytes),
                        isIncoming = isIncoming,
                        modifier = Modifier.testTag("mms_image_preview_${attachment.id}")
                    )
                }

                if (showFullScreenImage && bitmap != null) {
                    Dialog(onDismissRequest = { showFullScreenImage = false }) {
                        Surface(
                            color = Color.Black,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("mms_image_fullscreen_dialog")
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                IconButton(onClick = { showFullScreenImage = false }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = "Close",
                                        tint = Color.White
                                    )
                                }
                                Image(
                                    bitmap = bitmap!!.asImageBitmap(),
                                    contentDescription = attachment.fileName ?: "Full image",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 480.dp)
                                        .padding(bottom = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            mime.startsWith("video/") -> {
                MediaFallbackCard(
                    icon = Icons.Outlined.Videocam,
                    title = attachment.fileName ?: "Video clip",
                    subtitle = "Tap to play • ${formatSize(attachment.sizeBytes)}",
                    isIncoming = isIncoming,
                    modifier = Modifier
                        .clickable {
                            openFileExternally(context, systemSmsHelper, attachment.localFilePath, attachment.contentType)
                        }
                        .testTag("mms_video_card_${attachment.id}")
                )
            }

            mime.startsWith("audio/") -> {
                AudioAttachmentPlayerCard(
                    attachment = attachment,
                    isIncoming = isIncoming
                )
            }

            else -> {
                val vCardSummary = if (mime.contains("vcard") && !attachment.textContent.isNullOrBlank()) {
                    RcsInteropCodec.parseVCardSummary(attachment.textContent)
                } else null
                MediaFallbackCard(
                    icon = if (vCardSummary != null) Icons.Outlined.ContactPage else Icons.Outlined.Description,
                    title = vCardSummary?.first ?: attachment.fileName ?: "Attachment",
                    subtitle = vCardSummary?.second?.takeIf { it.isNotBlank() }
                        ?: "${attachment.contentType} • ${formatSize(attachment.sizeBytes)}",
                    isIncoming = isIncoming,
                    modifier = Modifier
                        .clickable {
                            openFileExternally(context, systemSmsHelper, attachment.localFilePath, attachment.contentType)
                        }
                        .testTag("mms_file_card_${attachment.id}")
                )
            }
        }
    }
}

@Composable
private fun AudioAttachmentPlayerCard(
    attachment: MmsAttachmentEntity,
    isIncoming: Boolean
) {
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember(attachment.localFilePath) { MediaPlayer() }

    DisposableEffect(attachment.localFilePath) {
        onDispose {
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
    }

    val containerColor = if (isIncoming) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
    }
    val contentColor = if (isIncoming) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mms_audio_card_${attachment.id}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            IconButton(
                onClick = {
                    val path = attachment.localFilePath
                    if (path.isNullOrBlank() || !File(path).exists()) return@IconButton
                    runCatching {
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            player.reset()
                            player.setDataSource(path)
                            player.setOnCompletionListener { isPlaying = false }
                            player.prepare()
                            player.start()
                            isPlaying = true
                        }
                    }.onFailure { isPlaying = false }
                },
                modifier = Modifier
                    .size(36.dp)
                    .testTag("mms_audio_play_${attachment.id}")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "Pause audio" else "Play audio",
                    tint = contentColor
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.fileName ?: "Audio clip",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.78f)
                )
            }
            Icon(
                imageVector = Icons.Outlined.AudioFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = contentColor.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun MediaFallbackCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isIncoming: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isIncoming) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
    }
    val contentColor = if (isIncoming) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun QuotedReplyPreviewCard(
    messageId: Long,
    sender: String?,
    snippet: String?,
    isIncoming: Boolean,
    onClick: () -> Unit
) {
    if (sender.isNullOrBlank() && snippet.isNullOrBlank()) return
    val bgColor = if (isIncoming) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f)
    }
    val fgColor = if (isIncoming) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val accent = if (isIncoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimary

    Surface(
        color = bgColor,
        contentColor = fgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag("quoted_reply_preview_$messageId")
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(30.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = sender ?: "Quoted message",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = snippet.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = fgColor.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionPillsRow(
    messageId: Long,
    reactions: List<MessageReaction>,
    onToggleReaction: (String) -> Unit
) {
    if (reactions.isEmpty()) return
    val grouped = remember(reactions) {
        reactions.groupBy { it.emoji }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .testTag("reaction_pills_row_$messageId")
    ) {
        grouped.forEach { (emoji, list) ->
            val reactedBySelf = list.any { it.isFromSelf }
            Surface(
                color = if (reactedBySelf) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .border(
                        1.dp,
                        if (reactedBySelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(14.dp)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onToggleReaction(emoji) }
                    .testTag("reaction_pill_${messageId}_$emoji")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.labelMedium)
                    if (list.size > 1) {
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = list.size.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickEmojiReactionBar(
    messageId: Long,
    onSelectEmoji: (String) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .padding(top = 4.dp)
            .testTag("emoji_reaction_bar_$messageId")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            RcsInteropCodec.QUICK_TAPBACK_EMOJIS.forEachIndexed { index, emoji ->
                Surface(
                    color = Color.Transparent,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onSelectEmoji(emoji) }
                        .testTag("reaction_emoji_${messageId}_$index")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusIndicator(
    messageId: Long,
    deliveryStatus: String,
    onRetry: () -> Unit
) {
    val label = RcsInteropCodec.formatDeliveryStatusLabel(deliveryStatus)
    if (label.isEmpty()) return
    val upper = deliveryStatus.uppercase(Locale.ROOT)
    val isFailed = upper == "FAILED"
    val color = when (upper) {
        "FAILED" -> MaterialTheme.colorScheme.error
        "READ" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (upper) {
        "SENDING" -> Icons.Outlined.Schedule
        "SENT" -> Icons.Outlined.Done
        "DELIVERED", "READ" -> Icons.Outlined.DoneAll
        "FAILED" -> Icons.Outlined.ErrorOutline
        else -> null
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.testTag("message_status_$messageId")
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(13.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        if (isFailed) {
            Text(
                text = "• Retry",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clickable(onClick = onRetry)
                    .testTag("retry_failed_msg_$messageId")
            )
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Retry",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun decodeSampledBitmap(filePath: String?, maxDim: Int): Bitmap? {
    if (filePath.isNullOrBlank()) return null
    val file = File(filePath)
    if (!file.exists()) return null
    return runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }.getOrNull()
}

private fun openFileExternally(
    context: android.content.Context,
    systemSmsHelper: SystemSmsHelper?,
    filePath: String?,
    mimeType: String
) {
    if (filePath.isNullOrBlank() || systemSmsHelper == null) return
    val file = File(filePath)
    if (!file.exists()) return
    runCatching {
        val uri = systemSmsHelper.buildFileProviderUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

private fun iconForMime(mime: String): ImageVector {
    val lower = mime.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("image/") -> Icons.Outlined.Image
        lower.startsWith("video/") -> Icons.Outlined.Videocam
        lower.startsWith("audio/") -> Icons.Outlined.AudioFile
        lower.contains("vcard") -> Icons.Outlined.ContactPage
        else -> Icons.Outlined.Description
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
