package com.example.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.sms.SystemSmsHelper
import com.example.ui.theme.CategoryOtpBg
import com.example.ui.theme.CategoryOtpFg
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.EmeraldTertiaryContainer

@Composable
fun OnboardingScreen(
    systemSmsHelper: SystemSmsHelper,
    isDefaultSmsApp: Boolean,
    showEmulatorDemoSeed: Boolean,
    onRoleOrPermissionsUpdated: () -> Unit,
    onSeedDemoInbox: () -> Unit,
    onSkipToInbox: () -> Unit
) {
    var showRestrictedSettingsGuide by rememberSaveable {
        mutableStateOf(systemSmsHelper.isSmsRoleRestricted())
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRoleOrPermissionsUpdated()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (systemSmsHelper.isDefaultSmsApp()) {
            showRestrictedSettingsGuide = false
            // After Default SMS role is granted, request Contacts + Notifications permissions
            val perms = buildList {
                add(Manifest.permission.READ_CONTACTS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            permissionsLauncher.launch(perms)
        } else {
            showRestrictedSettingsGuide = true
            onRoleOrPermissionsUpdated()
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("onboarding_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
        ) {
            Surface(
                color = EmeraldTertiaryContainer,
                contentColor = EmeraldTertiary,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WifiOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.onboarding_badge),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Image(
                painter = painterResource(id = R.drawable.img_onboarding_hero_1795444375102),
                contentDescription = "Offline encrypted SMS vault illustration",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    SecurityPillarRow(
                        icon = Icons.Outlined.WifiOff,
                        title = "Zero Internet Permission",
                        description = "Network permissions are stripped at the Android Manifest level. Messages never leave your phone."
                    )
                    SecurityPillarRow(
                        icon = Icons.Outlined.Lock,
                        title = "SQLCipher + Tink Hardware Keystore",
                        description = "Your SMS vault and preferences are encrypted at rest with 256-bit keys."
                    )
                    SecurityPillarRow(
                        icon = Icons.Outlined.Shield,
                        title = "TRAI DLT & Local ML Classification",
                        description = "Instant on-device sorting across Personal, Transactional, Service, OTP, Promo, Gov & Unknown."
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (!isDefaultSmsApp) {
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
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("set_default_sms_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sms,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.onboarding_set_default),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (showRestrictedSettingsGuide || systemSmsHelper.isSmsRoleRestricted()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    RestrictedSettingsGuidanceCard(systemSmsHelper = systemSmsHelper)
                } else {
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { showRestrictedSettingsGuide = true },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("onboarding_show_restricted_guide_button")
                    ) {
                        Text(
                            text = stringResource(R.string.restricted_settings_show_help),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            } else {
                Surface(
                    color = EmeraldTertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldTertiary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "TRAISms is active as Default SMS Handler",
                            style = MaterialTheme.typography.titleSmall,
                            color = EmeraldTertiary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSkipToInbox,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("continue_to_inbox_button")
                ) {
                    Text(stringResource(R.string.onboarding_continue_readonly))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
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
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("grant_permissions_button")
            ) {
                Text(stringResource(R.string.onboarding_grant_permissions))
            }

            if (showEmulatorDemoSeed) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSeedDemoInbox,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("preview_demo_inbox_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_demo_seed),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                onClick = onSkipToInbox,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .testTag("skip_onboarding_button")
            ) {
                Text("Explore Inbox Now")
            }
        }
    }
}

@Composable
private fun SecurityPillarRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(8.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Prominent Material 3 guidance card for Android 15/16 (Pixel & sideloaded GitHub Releases APKs)
 * explaining why Enhanced Confirmation Mode (Restricted Settings) blocked `ROLE_SMS` and providing
 * exact 4-step unlock instructions plus one-tap **Open App Info** and **Open Default Apps** actions.
 */
@Composable
fun RestrictedSettingsGuidanceCard(
    systemSmsHelper: SystemSmsHelper,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val steps = listOf(
        stringResource(R.string.restricted_settings_step_1),
        stringResource(R.string.restricted_settings_step_2),
        stringResource(R.string.restricted_settings_step_3),
        stringResource(R.string.restricted_settings_step_4)
    )

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CategoryOtpBg
        ),
        border = BorderStroke(1.dp, CategoryOtpFg.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("restricted_settings_guidance_card")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = CategoryOtpFg,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.restricted_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CategoryOtpFg
                )
            }

            Text(
                text = stringResource(R.string.restricted_settings_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { index, stepText ->
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = CategoryOtpFg,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, CategoryOtpFg.copy(alpha = 0.4f)),
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stepText,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.restricted_settings_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }

            Button(
                onClick = {
                    launchExternalSettingsIntent(
                        context = context,
                        intent = systemSmsHelper.buildAppInfoSettingsIntent()
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_app_info_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.restricted_settings_open_app_info),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = {
                    launchExternalSettingsIntent(
                        context = context,
                        intent = systemSmsHelper.buildDefaultAppsSettingsIntent(),
                        fallbackIntent = systemSmsHelper.buildAppInfoSettingsIntent()
                    )
                },
                border = BorderStroke(1.dp, CategoryOtpFg.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("open_default_apps_settings_button")
            ) {
                Text(
                    text = stringResource(R.string.restricted_settings_open_default_apps),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

internal fun launchExternalSettingsIntent(
    context: Context,
    intent: Intent,
    fallbackIntent: Intent? = null
): Boolean {
    fun tryStart(target: Intent): Boolean {
        return runCatching {
            val launchIntent = Intent(target).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(launchIntent)
        }.isSuccess
    }
    return tryStart(intent) || (fallbackIntent != null && tryStart(fallbackIntent))
}
