package com.example.ui.backup

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupSchedule
import com.example.data.sms.RestoreSummary
import com.example.ui.common.formatFullDateTime
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.EmeraldTertiaryContainer
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackupScreen(
    settings: AppSettings,
    totalMessagesInDb: Int,
    isBackupInProgress: Boolean,
    onSaveDriveDestination: (uriString: String, displayName: String) -> Unit,
    onSaveBackupPassphrase: (String) -> Unit,
    onUpdateBackupSchedule: (BackupSchedule, Int) -> Unit,
    onRunManualBackupNow: ((Result<Int>) -> Unit) -> Unit,
    onRunRestoreFromUri: (Uri, String, (Result<RestoreSummary>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var passphraseDraft by remember(settings.backupPassphrase) {
        mutableStateOf(settings.backupPassphrase)
    }
    var showPassphrase by remember { mutableStateOf(false) }
    var customDaysInput by remember(settings.customBackupDays) {
        mutableStateOf(settings.customBackupDays.toString())
    }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassphraseInput by remember { mutableStateOf("") }
    var showRestorePassphraseText by remember { mutableStateOf(false) }

    // Step 1 SAF CreateDocument("application/octet-stream") — compatible with Google Drive DocumentsProvider
    val createBackupDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            val displayName = resolveDocumentDisplayName(context, uri) ?: "TRAISms_Encrypted_Backup.trai"
            onSaveDriveDestination(uri.toString(), displayName)
            scope.launch {
                snackbarHostState.showSnackbar("Linked backup file: $displayName")
            }
        }
    }

    // SAF OpenDocument for restoring an encrypted .trai archive
    val openRestoreDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            restorePassphraseInput = settings.backupPassphrase
            pendingRestoreUri = uri
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
                    title = {
                        Text(
                            text = stringResource(R.string.backup_title),
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
                .testTag("backup_screen")
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
            ) {
                // Offline SAF Security Banner
                Surface(
                    color = EmeraldTertiaryContainer,
                    contentColor = EmeraldTertiary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "AES-256-GCM + PBKDF2 (600,000 Iterations)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Google Drive authentication and cloud sync run inside the Android OS & Google Drive app via SAF — TRAISms keeps zero internet permissions.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // STEP 1 CARD: Google Drive Destination (SAF)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.backup_step1_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(
                            text = stringResource(R.string.backup_step1_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (settings.persistedDriveUri.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Linked File: ${settings.driveFileName.ifBlank { "TRAISms_Encrypted_Backup.trai" }}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = settings.persistedDriveUri,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                createBackupDocLauncher.launch("TRAISms_Encrypted_Backup.trai")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("choose_drive_backup_file_button")
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_choose_file_btn))
                        }
                    }
                }

                // STEP 2 CARD: Backup Encryption Password
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.backup_step2_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Text(
                            text = "Stored wrapped via Google Tink Hardware Keystore so unattended scheduled backups can encrypt automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = passphraseDraft,
                            onValueChange = { passphraseDraft = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.backup_passphrase_hint)) },
                            visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPassphrase = !showPassphrase }) {
                                    Icon(
                                        imageVector = if (showPassphrase) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = "Toggle password visibility"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("backup_passphrase_input")
                        )

                        Button(
                            onClick = {
                                onSaveBackupPassphrase(passphraseDraft.trim())
                                scope.launch {
                                    snackbarHostState.showSnackbar("Backup encryption passphrase saved securely")
                                }
                            },
                            enabled = passphraseDraft.trim().length >= 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("save_backup_passphrase_button")
                        ) {
                            Text("${stringResource(R.string.backup_save_passphrase)} (Min 6 chars)")
                        }
                    }
                }

                // STATUS & MANUAL ACTIONS CARD
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
                        Text(
                            text = "3. Status & Manual Actions",
                            style = MaterialTheme.typography.titleMedium
                        )

                        StatusMetricRow("Vault Messages Ready", "$totalMessagesInDb SMS")
                        StatusMetricRow("Last Backup Time", formatFullDateTime(settings.lastBackupEpochMillis))
                        StatusMetricRow("Last Backed Up Count", "${settings.lastBackupCount} SMS")
                        StatusMetricRow(
                            label = "Last Status",
                            value = settings.lastBackupStatus,
                            modifier = Modifier.testTag("backup_status_row_last_status")
                        )

                        val canRunBackup = settings.persistedDriveUri.isNotBlank() &&
                            settings.backupPassphrase.isNotBlank() &&
                            !isBackupInProgress

                        Button(
                            onClick = {
                                onRunManualBackupNow { res ->
                                    scope.launch {
                                        val msg = res.fold(
                                            onSuccess = { count -> "Encrypted & backed up $count SMS messages to Drive file" },
                                            onFailure = { err -> "Backup failed: ${err.message}" }
                                        )
                                        snackbarHostState.showSnackbar(msg)
                                    }
                                }
                            },
                            enabled = canRunBackup,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .testTag("backup_now_button")
                        ) {
                            if (isBackupInProgress) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Encrypting Snapshot…")
                            } else {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.backup_now_btn))
                            }
                        }

                        if (!canRunBackup && !isBackupInProgress) {
                            Text(
                                text = "Complete Step 1 (Drive file) and Step 2 (Passphrase) above to enable Back Up Now.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                openRestoreDocLauncher.launch(arrayOf("*/*"))
                            },
                            enabled = !isBackupInProgress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("restore_from_backup_button")
                        ) {
                            Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.restore_from_backup_btn))
                        }
                    }
                }

                // AUTO-BACKUP SCHEDULING CARD (mirrored with Settings)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Auto-Backup Schedule",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BackupSchedule.entries.forEach { option ->
                                FilterChip(
                                    selected = settings.backupSchedule == option,
                                    onClick = {
                                        onUpdateBackupSchedule(option, settings.customBackupDays)
                                    },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("backup_schedule_${option.name.lowercase()}")
                                )
                            }
                        }

                        if (settings.backupSchedule == BackupSchedule.CUSTOM) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = customDaysInput,
                                    onValueChange = { customDaysInput = it.filter { ch -> ch.isDigit() } },
                                    singleLine = true,
                                    label = { Text("Interval (Days, min 1)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        val parsed = customDaysInput.toIntOrNull()?.coerceAtLeast(1) ?: 7
                                        customDaysInput = parsed.toString()
                                        onUpdateBackupSchedule(BackupSchedule.CUSTOM, parsed)
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Scheduled every $parsed day(s)")
                                        }
                                    }
                                ) {
                                    Text("Apply")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Restore Passphrase Verification Dialog
    pendingRestoreUri?.let { uriToRestore ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Decrypt & Restore Backup") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Enter the encryption passphrase used when creating this .trai backup. Existing messages are deduplicated by SHA-256 hash so duplicates are never created.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = restorePassphraseInput,
                        onValueChange = { restorePassphraseInput = it },
                        singleLine = true,
                        label = { Text("Backup Passphrase") },
                        visualTransformation = if (showRestorePassphraseText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showRestorePassphraseText = !showRestorePassphraseText }) {
                                Icon(
                                    imageVector = if (showRestorePassphraseText) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Toggle visibility"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restore_passphrase_dialog_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = restorePassphraseInput
                        pendingRestoreUri = null
                        onRunRestoreFromUri(uriToRestore, pass) { result ->
                            scope.launch {
                                val msg = result.fold(
                                    onSuccess = { summary ->
                                        "Restored ${summary.insertedCount} SMS (${summary.skippedDuplicates} duplicates skipped, ${summary.restoredRulesCount} rules)"
                                    },
                                    onFailure = { err ->
                                        err.message ?: "Restore failed"
                                    }
                                )
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
                    },
                    enabled = restorePassphraseInput.isNotBlank(),
                    modifier = Modifier.testTag("confirm_restore_button")
                ) {
                    Text("Verify & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StatusMetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f)
        )
    }
}

private fun resolveDocumentDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
