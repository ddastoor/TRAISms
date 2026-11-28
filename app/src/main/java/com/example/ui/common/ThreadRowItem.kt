package com.example.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.LeftSwipeAction
import com.example.data.db.ThreadSummary
import com.example.ui.theme.CategoryOtpBg
import com.example.ui.theme.CategoryOtpFg
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.EmeraldTertiaryContainer
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.LocalSmsTextPreferences
import com.example.ui.theme.smsSnippetBodyStyle

private data class SwipeActionVisualSpec(
    val bgColor: Color,
    val label: String,
    val icon: ImageVector,
    val tint: Color
)

@Composable
private fun resolveSwipeActionVisualSpec(
    action: LeftSwipeAction,
    isArchiveScreen: Boolean
): SwipeActionVisualSpec = when (action) {
    LeftSwipeAction.ARCHIVE -> if (isArchiveScreen) {
        SwipeActionVisualSpec(
            bgColor = EmeraldTertiaryContainer,
            label = "Unarchive",
            icon = Icons.Outlined.Unarchive,
            tint = EmeraldTertiary
        )
    } else {
        SwipeActionVisualSpec(
            bgColor = MaterialTheme.colorScheme.primaryContainer,
            label = "Archive",
            icon = Icons.Outlined.Archive,
            tint = MaterialTheme.colorScheme.primary
        )
    }
    LeftSwipeAction.DELETE -> SwipeActionVisualSpec(
        bgColor = MaterialTheme.colorScheme.errorContainer,
        label = "Delete",
        icon = Icons.Outlined.DeleteOutline,
        tint = MaterialTheme.colorScheme.error
    )
    LeftSwipeAction.ASSIGN_CATEGORY -> SwipeActionVisualSpec(
        bgColor = MaterialTheme.colorScheme.secondaryContainer,
        label = "Assign Category",
        icon = Icons.Outlined.DriveFileMove,
        tint = MaterialTheme.colorScheme.onSecondaryContainer
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadRowItem(
    thread: ThreadSummary,
    isArchiveScreen: Boolean,
    leftSwipeAction: LeftSwipeAction,
    rightSwipeAction: LeftSwipeAction = LeftSwipeAction.ASSIGN_CATEGORY,
    showCategoryPill: Boolean,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onCopyOtp: (String) -> Unit,
    onSwipeLeftAction: (ThreadSummary) -> Unit,
    onSwipeRightAssignCategory: (ThreadSummary) -> Unit = {},
    onSwipeRightAction: ((ThreadSummary) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val smsTextPrefs = LocalSmsTextPreferences.current
    val currentThread by rememberUpdatedState(thread)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeftAction)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRightAction)
    val currentOnSwipeRightLegacy by rememberUpdatedState(onSwipeRightAssignCategory)

    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.35f },
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnSwipeLeft(currentThread)
                    false // Snap cleanly while list updates via reactive Room Flow
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val rightHandler = currentOnSwipeRight
                    if (rightHandler != null) {
                        rightHandler(currentThread)
                    } else {
                        currentOnSwipeRightLegacy(currentThread)
                    }
                    false // Snap back cleanly after executing configured right-swipe action
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .fillMaxWidth()
            .testTag("thread_row_${thread.threadKey}"),
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
            val isEndToStart = direction == SwipeToDismissBoxValue.EndToStart

            val activeSpec = when {
                isStartToEnd -> resolveSwipeActionVisualSpec(
                    action = rightSwipeAction,
                    isArchiveScreen = isArchiveScreen
                )
                isEndToStart -> resolveSwipeActionVisualSpec(
                    action = leftSwipeAction,
                    isArchiveScreen = isArchiveScreen
                )
                else -> null
            }
            val bgColor = activeSpec?.bgColor ?: MaterialTheme.colorScheme.surfaceContainerLow

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 20.dp)
            ) {
                if (isStartToEnd && activeSpec != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = activeSpec.icon,
                            contentDescription = activeSpec.label,
                            tint = activeSpec.tint
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = activeSpec.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = activeSpec.tint
                        )
                    }
                } else if (isEndToStart && activeSpec != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Text(
                            text = activeSpec.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = activeSpec.tint
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = activeSpec.icon,
                            contentDescription = activeSpec.label,
                            tint = activeSpec.tint
                        )
                    }
                }
            }
        },
        content = {
            val hasUnread = thread.unreadCount > 0
            val senderDisplay = thread.contactName ?: thread.rawAddress
            val style = thread.category.visualStyle()

            Surface(
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    // Sender Avatar
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else style.containerColor
                            )
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                text = buildSenderAvatarLabel(senderDisplay),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = style.contentColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(13.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = senderDisplay,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatSmsTimestamp(thread.latestTimestampMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasUnread) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (thread.contactName != null) {
                            Text(
                                text = thread.rawAddress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = thread.latestBody,
                                style = smsSnippetBodyStyle(
                                    MaterialTheme.typography.bodyMedium,
                                    smsTextPrefs.fontSize,
                                    smsTextPrefs.bold,
                                    hasUnread
                                ),
                                color = if (hasUnread) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("thread_row_body_${thread.threadKey}")
                            )

                            if (hasUnread) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .defaultMinSize(minWidth = 22.dp, minHeight = 22.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondary)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = thread.unreadCount.coerceAtMost(99).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Quick Copy OTP chip, transport badge, attachment icon & optional category tag
                        if (thread.extractedOtp != null || showCategoryPill || thread.transportType != "SMS" || thread.hasAttachments) {
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (showCategoryPill) {
                                    CategoryBadgePill(category = thread.category)
                                }
                                if (thread.transportType != "SMS") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.testTag("thread_transport_badge_${thread.threadKey}")
                                    ) {
                                        Text(
                                            text = thread.transportType,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                if (thread.hasAttachments) {
                                    Icon(
                                        imageVector = Icons.Outlined.AttachFile,
                                        contentDescription = "Has attachments",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .testTag("thread_attachment_icon_${thread.threadKey}")
                                    )
                                }
                                thread.extractedOtp?.let { otpCode ->
                                    Surface(
                                        onClick = { onCopyOtp(otpCode) },
                                        color = CategoryOtpBg,
                                        contentColor = CategoryOtpFg,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("copy_otp_chip_${thread.threadKey}")
                                            .defaultMinSize(minHeight = 32.dp)
                                            .border(
                                                width = 1.dp,
                                                color = CategoryOtpFg.copy(alpha = 0.35f),
                                                shape = RoundedCornerShape(8.dp)
                                            )
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
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text(
                                                text = stringResource(R.string.copy_otp_chip, otpCode),
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFamily = JetBrainsMonoFamily,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
