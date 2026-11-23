package com.example.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.SmsCategory
import com.example.ui.theme.CategoryGovernmentBg
import com.example.ui.theme.CategoryGovernmentFg
import com.example.ui.theme.CategoryOtherBg
import com.example.ui.theme.CategoryOtherFg
import com.example.ui.theme.CategoryOtpBg
import com.example.ui.theme.CategoryOtpFg
import com.example.ui.theme.CategoryPersonalBg
import com.example.ui.theme.CategoryPersonalFg
import com.example.ui.theme.CategoryPromotionalBg
import com.example.ui.theme.CategoryPromotionalFg
import com.example.ui.theme.CategoryServiceBg
import com.example.ui.theme.CategoryServiceFg
import com.example.ui.theme.CategoryTransactionalBg
import com.example.ui.theme.CategoryTransactionalFg
import com.example.ui.theme.CategoryUnknownBg
import com.example.ui.theme.CategoryUnknownFg
import com.example.ui.theme.JetBrainsMonoFamily
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CategoryVisualStyle(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector
)

fun SmsCategory.visualStyle(): CategoryVisualStyle = when (this) {
    SmsCategory.PERSONAL -> CategoryVisualStyle(CategoryPersonalBg, CategoryPersonalFg, Icons.Outlined.Person)
    SmsCategory.TRANSACTIONAL -> CategoryVisualStyle(CategoryTransactionalBg, CategoryTransactionalFg, Icons.Outlined.AccountBalance)
    SmsCategory.SERVICE -> CategoryVisualStyle(CategoryServiceBg, CategoryServiceFg, Icons.Outlined.LocalShipping)
    SmsCategory.OTP -> CategoryVisualStyle(CategoryOtpBg, CategoryOtpFg, Icons.Outlined.Lock)
    SmsCategory.PROMOTIONAL -> CategoryVisualStyle(CategoryPromotionalBg, CategoryPromotionalFg, Icons.Outlined.Campaign)
    SmsCategory.GOVERNMENT -> CategoryVisualStyle(CategoryGovernmentBg, CategoryGovernmentFg, Icons.Outlined.Gavel)
    SmsCategory.UNKNOWN -> CategoryVisualStyle(CategoryUnknownBg, CategoryUnknownFg, Icons.Outlined.HelpOutline)
    SmsCategory.OTHER -> CategoryVisualStyle(CategoryOtherBg, CategoryOtherFg, Icons.Outlined.Category)
}

fun formatSmsTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMillis }

    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    if (sameDay) {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }

    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    return if (sameYear) {
        SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(epochMillis))
    } else {
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(epochMillis))
    }
}

fun formatFullDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Never"
    return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(epochMillis))
}

fun buildSenderAvatarLabel(displayTitle: String): String {
    val clean = displayTitle.trim()
    if (clean.isEmpty()) return "?"
    // Strip TRAI prefix like VM-HDFCBK-T -> HD
    val traiParts = clean.split('-', '_', ' ').filter { it.isNotBlank() }
    if (traiParts.size >= 2 && traiParts.first().length == 2 && traiParts.first().all { it.isLetter() }) {
        return traiParts[1].take(2).uppercase(Locale.ROOT)
    }
    val letters = clean.filter { it.isLetter() }
    if (letters.length >= 2) {
        val words = clean.split(' ').filter { it.isNotBlank() }
        return if (words.size >= 2) {
            "${words[0].first()}${words[1].first()}".uppercase(Locale.ROOT)
        } else {
            letters.take(2).uppercase(Locale.ROOT)
        }
    }
    return clean.takeLast(2)
}

@Composable
fun CategoryBadgePill(
    category: SmsCategory,
    modifier: Modifier = Modifier
) {
    val style = category.visualStyle()
    Surface(
        color = style.containerColor,
        contentColor = style.contentColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveSenderCategoryBottomSheet(
    senderTitle: String,
    currentCategory: SmsCategory,
    onDismiss: () -> Unit,
    onConfirm: (targetCategory: SmsCategory, alwaysApplyRule: Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by remember(currentCategory) { mutableStateOf(currentCategory) }
    var alwaysMoveRule by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("move_category_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.move_sender_category_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = senderTitle,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("move_category_sender_title")
            )
            Spacer(modifier = Modifier.height(12.dp))

            SmsCategory.orderedTabs.forEach { category ->
                val style = category.visualStyle()
                val isSelected = selectedCategory == category
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { selectedCategory = category }
                        .testTag("sheet_option_${category.name.lowercase(Locale.ROOT)}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(style.containerColor)
                        ) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.contentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = category.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedCategory = category },
                            modifier = Modifier.testTag("sheet_radio_${category.name.lowercase(Locale.ROOT)}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { alwaysMoveRule = !alwaysMoveRule }
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.always_move_sender_rule, senderTitle),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Switch(
                    checked = alwaysMoveRule,
                    onCheckedChange = { alwaysMoveRule = it },
                    modifier = Modifier.testTag("always_move_sender_switch")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(selectedCategory, alwaysMoveRule) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("confirm_move_category_button")
            ) {
                Text(text = stringResource(R.string.apply_category_button))
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Strict two-step global confirmation dialog requiring the user to type `DELETE`
 * before permanently wiping all SMS messages across the app and Android's `Telephony.Sms` provider.
 */
@Composable
fun GlobalDeleteAllMessagesDialog(
    totalCount: Int,
    onDismiss: () -> Unit,
    onConfirmDeleteAll: () -> Unit
) {
    var typedConfirmation by remember { mutableStateOf("") }
    val isConfirmed = typedConfirmation.trim() == "DELETE"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = "Delete All $totalCount Messages?",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "This permanently erases all $totalCount SMS messages across every category, Archive, and the Android system SMS database. This cannot be undone unless you have an encrypted .trai backup.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Type DELETE in uppercase below to confirm:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedTextField(
                    value = typedConfirmation,
                    onValueChange = { typedConfirmation = it },
                    singleLine = true,
                    placeholder = { Text("DELETE") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("global_delete_confirm_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmDeleteAll,
                enabled = isConfirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.testTag("global_delete_confirm_button")
            ) {
                Text("Wipe All SMS")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
