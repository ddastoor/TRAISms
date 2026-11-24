package com.example.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupSchedule
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.SmsFontSize
import com.example.data.sms.SystemSmsHelper
import com.example.ui.common.GlobalDeleteAllMessagesDialog
import com.example.ui.onboarding.RestrictedSettingsGuidanceCard
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.EmeraldTertiaryContainer
import com.example.ui.theme.smsMessageBodyStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isDefaultSmsApp: Boolean,
    detectedRegionLabel: String,
    totalMessageCount: Int,
    systemSmsHelper: SystemSmsHelper,
    onRefreshRoleStatus: () -> Unit,
    onUpdatePromoAutoDelete: (Int) -> Unit,
    onUpdateLeftSwipeAction: (LeftSwipeAction) -> Unit,
    onUpdateUnarchiveOnNewSms: (Boolean) -> Unit,
    onUpdateRegionOverride: (String) -> Unit,
    onUpdateBackupSchedule: (BackupSchedule, Int) -> Unit,
    onNavigateToBackupTab: () -> Unit,
    onToggleAppLock: (Boolean) -> Unit,
    onToggleBlockScreenshots: (Boolean) -> Unit,
    onRerunCategorization: ((Int) -> Unit) -> Unit,
    onDeleteAllMessagesInApp: () -> Unit,
    onUpdateSmsFontSize: (SmsFontSize) -> Unit = {},
    onUpdateSmsFontBold: (Boolean) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showGlobalDeleteDialog by remember { mutableStateOf(false) }
    var customBackupDaysDraft by remember(settings.customBackupDays) {
        mutableStateOf(settings.customBackupDays.toString())
    }

    var showRestrictedSettingsGuide by rememberSaveable {
        mutableStateOf(systemSmsHelper.isSmsRoleRestricted())
    }

    val defaultRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = systemSmsHelper.isDefaultSmsApp()
        showRestrictedSettingsGuide = !granted
        onRefreshRoleStatus()
    }

    val promoPresets = listOf(
        0 to "Off",
        1 to "1 Day",
        3 to "3 Days",
        7 to "7 Days",
        15 to "15 Days",
        30 to "30 Days"
    )

    val regionOptions = listOf(
        "AUTO" to "Auto-Detect ($detectedRegionLabel)",
        "IN" to "India (TRAI DLT -P/-S/-T/-G + UPI)",
        "US" to "United States (Shortcodes + Zelle/IRS)",
        "GB" to "United Kingdom (NHS/HMRC/GOVUK)",
        "CA" to "Canada (Interac/CRA/ServiceCanada)",
        "EU_EN" to "Ireland & Europe (SEPA/HSE/GovIE)",
        "GLOBAL_EN" to "Universal English"
    )

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    title = {
                        Text(
                            text = stringResource(R.string.nav_settings),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { innerPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("settings_screen")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
            ) {
                // 1. DEFAULT SMS STATUS CARD
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDefaultSmsApp) EmeraldTertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isDefaultSmsApp) EmeraldTertiary.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isDefaultSmsApp) Icons.Outlined.CheckCircle else Icons.Outlined.Sms,
                                contentDescription = null,
                                tint = if (isDefaultSmsApp) EmeraldTertiary else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (isDefaultSmsApp) {
                                        stringResource(R.string.settings_default_sms_active)
                                    } else {
                                        "Not Default SMS App"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isDefaultSmsApp) EmeraldTertiary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isDefaultSmsApp) {
                                        "Full real-time SMS delivery, sending, and provider cleanup enabled."
                                    } else {
                                        "Set TRAISms as Default to receive, send, and auto-clean carrier SMS."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDefaultSmsApp) EmeraldTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (!isDefaultSmsApp) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Button(
                                onClick = {
                                    val roleIntent = systemSmsHelper.createDefaultSmsRequestIntent()
                                    if (roleIntent != null) {
                                        defaultRoleLauncher.launch(roleIntent)
                                    } else {
                                        showRestrictedSettingsGuide = true
                                    }
                                },
                                modifier = Modifier.testTag("settings_set_default_sms_button")
                            ) {
                                Text(stringResource(R.string.settings_set_default_btn))
                            }
                        }
                    }
                }

                if (!isDefaultSmsApp) {
                    if (showRestrictedSettingsGuide || systemSmsHelper.isSmsRoleRestricted()) {
                        RestrictedSettingsGuidanceCard(systemSmsHelper = systemSmsHelper)
                    } else {
                        TextButton(
                            onClick = { showRestrictedSettingsGuide = true },
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .testTag("settings_show_restricted_guide_button")
                        ) {
                            Text(
                                text = stringResource(R.string.restricted_settings_show_help),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }

                // SMS TEXT APPEARANCE CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.FormatSize,
                    title = stringResource(R.string.settings_sms_text_title),
                    subtitle = stringResource(R.string.settings_sms_text_subtitle)
                ) {
                    Text(
                        text = stringResource(R.string.settings_sms_font_size_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmsFontSize.entries.forEach { size ->
                            FilterChip(
                                selected = settings.smsFontSize == size,
                                onClick = { onUpdateSmsFontSize(size) },
                                label = { Text(size.label) },
                                modifier = Modifier.testTag("sms_font_size_${size.name.lowercase()}")
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_sms_font_bold_title),
                        subtitle = stringResource(R.string.settings_sms_font_bold_subtitle),
                        checked = settings.smsFontBold,
                        onCheckedChange = onUpdateSmsFontBold,
                        testTag = "sms_font_bold_switch"
                    )

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sms_font_live_preview")
                    ) {
                        Text(
                            text = stringResource(R.string.settings_sms_font_preview_sample),
                            style = smsMessageBodyStyle(
                                MaterialTheme.typography.bodyLarge,
                                settings.smsFontSize,
                                settings.smsFontBold
                            ),
                            modifier = Modifier
                                .padding(12.dp)
                                .testTag("sms_font_preview_text")
                        )
                    }
                }

                // 2. PROMOTIONAL AUTO-DELETE CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.AutoDelete,
                    title = "Promotional Auto-Delete",
                    subtitle = "Strictly restricted to unarchived Promotional (-P) messages. Never touches Personal, Transactional, Service, OTP, Gov, Unknown, Other, or Archived."
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        promoPresets.forEach { (days, label) ->
                            FilterChip(
                                selected = settings.promoAutoDeleteDays == days,
                                onClick = {
                                    onUpdatePromoAutoDelete(days)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (days == 0) "Promotional auto-delete disabled"
                                            else "Promotional SMS older than $label will auto-delete"
                                        )
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("promo_preset_$days")
                            )
                        }
                    }
                }

                // 3. SWIPE GESTURES & ARCHIVE BEHAVIOR CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.Swipe,
                    title = "Swipe Gestures & Archive",
                    subtitle = "Configure inbox swipe actions and incoming archive behavior."
                ) {
                    Text(
                        text = "Left Swipe Action (Inbox)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.leftSwipeAction == LeftSwipeAction.ARCHIVE,
                            onClick = { onUpdateLeftSwipeAction(LeftSwipeAction.ARCHIVE) },
                            label = { Text("Archive (Default)") },
                            modifier = Modifier.testTag("swipe_left_archive_chip")
                        )
                        FilterChip(
                            selected = settings.leftSwipeAction == LeftSwipeAction.DELETE,
                            onClick = { onUpdateLeftSwipeAction(LeftSwipeAction.DELETE) },
                            label = { Text("Delete") },
                            modifier = Modifier.testTag("swipe_left_delete_chip")
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Right Swipe Action", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "Assign Sender Category Rule (Modal Sheet)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SettingsToggleRow(
                        title = "Unarchive thread on new incoming SMS",
                        subtitle = "Automatically return archived conversations to their category tab when a new message arrives.",
                        checked = settings.unarchiveOnNewSms,
                        onCheckedChange = onUpdateUnarchiveOnNewSms,
                        testTag = "unarchive_on_new_sms_switch"
                    )
                }

                // 4. CATEGORIZATION REGION CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.Public,
                    title = "Categorization Region",
                    subtitle = "100% offline regional priors & TRAI header rules."
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        regionOptions.forEach { (code, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onUpdateRegionOverride(code) }
                                    .padding(vertical = 4.dp)
                                    .testTag("region_option_${code.lowercase()}")
                            ) {
                                RadioButton(
                                    selected = settings.regionOverride == code,
                                    onClick = { onUpdateRegionOverride(code) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // 5. BACKUP SCHEDULE CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.Backup,
                    title = "Backup Schedule",
                    subtitle = "Automatic AES-256-GCM encrypted snapshots via Google Drive SAF."
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BackupSchedule.entries.forEach { schedule ->
                            FilterChip(
                                selected = settings.backupSchedule == schedule,
                                onClick = {
                                    onUpdateBackupSchedule(schedule, settings.customBackupDays)
                                },
                                label = { Text(schedule.label) }
                            )
                        }
                    }

                    if (settings.backupSchedule == BackupSchedule.CUSTOM) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customBackupDaysDraft,
                                onValueChange = { customBackupDaysDraft = it.filter { c -> c.isDigit() } },
                                singleLine = true,
                                label = { Text("Every N Days (min 1)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val days = customBackupDaysDraft.toIntOrNull()?.coerceAtLeast(1) ?: 7
                                    customBackupDaysDraft = days.toString()
                                    onUpdateBackupSchedule(BackupSchedule.CUSTOM, days)
                                }
                            ) {
                                Text("Save")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onNavigateToBackupTab,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_open_backup_tab_button")
                    ) {
                        Text("Open Google Drive Backup & Restore Center")
                    }
                }

                // 6. SECURITY HARDENING CONTROLS
                SettingsGroupCard(
                    icon = Icons.Outlined.Security,
                    title = "Security Hardening",
                    subtitle = "SQLCipher 256-bit DB encryption & Tink Hardware Keystore are always active."
                ) {
                    SettingsToggleRow(
                        title = "App Lock (Biometric / Device PIN)",
                        subtitle = "Require fingerprint, face, or device PIN on launch and redact notification previews.",
                        checked = settings.appLockEnabled,
                        onCheckedChange = onToggleAppLock,
                        testTag = "app_lock_switch",
                        leadingIcon = Icons.Outlined.Fingerprint
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    val isEmulatorEnv = remember { systemSmsHelper.isRunningOnEmulator() || com.example.BuildConfig.DEBUG }
                    val effectiveFlagSecure = if (isEmulatorEnv) {
                        settings.blockScreenshots && settings.userForcedFlagSecureInStream
                    } else {
                        settings.blockScreenshots
                    }

                    SettingsToggleRow(
                        title = "Block Screenshots & Recents Preview (FLAG_SECURE)",
                        subtitle = if (isEmulatorEnv) {
                            "Auto-paused in Streaming Emulator so your browser preview stays visible (enforced by default on physical release devices). Toggle ON only on a physical screen."
                        } else {
                            "Enabled by default to shield OTPs and bank alerts from screen capture or task-switcher snapshots."
                        },
                        checked = effectiveFlagSecure,
                        onCheckedChange = onToggleBlockScreenshots,
                        testTag = "flag_secure_switch"
                    )
                }

                // 7. DANGER ZONE CARD
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Maintenance & Danger Zone",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedButton(
                            onClick = {
                                onRerunCategorization { updatedCount ->
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Re-categorized $updatedCount SMS messages")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("rerun_categorization_button")
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Re-run Categorization on All Messages")
                        }

                        Button(
                            onClick = { showGlobalDeleteDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("danger_zone_delete_all_button")
                        ) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.delete_all_in_app))
                        }
                    }
                }

                // 8. ABOUT & APP VERSION CARD
                SettingsGroupCard(
                    icon = Icons.Outlined.Info,
                    title = "About TRAISms",
                    subtitle = "App version and offline build details"
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Version ${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("settings_app_version_text")
                        )
                        Text(
                            text = "Package: ${com.example.BuildConfig.APPLICATION_ID} • 100% Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showGlobalDeleteDialog) {
        GlobalDeleteAllMessagesDialog(
            totalCount = totalMessageCount,
            onDismiss = { showGlobalDeleteDialog = false },
            onConfirmDeleteAll = {
                showGlobalDeleteDialog = false
                onDeleteAllMessagesInApp()
                scope.launch {
                    snackbarHostState.showSnackbar("All SMS messages wiped permanently")
                }
            }
        )
    }
}

@Composable
private fun SettingsGroupCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
