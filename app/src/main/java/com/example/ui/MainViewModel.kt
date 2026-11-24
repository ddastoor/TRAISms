package com.example.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.crypto.AppSettings
import com.example.data.crypto.BackupSchedule
import com.example.data.crypto.LeftSwipeAction
import com.example.data.crypto.SmsFontSize
import com.example.data.db.CategoryUnreadCount
import com.example.data.db.SmsCategory
import com.example.data.db.SmsMessageEntity
import com.example.data.db.ThreadSummary
import com.example.data.sms.RestoreSummary
import com.example.data.sms.SyncProgressState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssignCategoryRequest(
    val threadKey: String,
    val rawAddress: String,
    val senderTitle: String,
    val currentCategory: SmsCategory
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val container: AppContainer
) : ViewModel() {

    val repository = container.smsRepository
    val systemSmsHelper = container.systemSmsHelper
    val contactCache = container.contactCache
    private val settingsStore = container.settingsStore
    private val workScheduler = container.workScheduler
    private val regionDetector = container.regionDetector

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    private val _selectedCategory = MutableStateFlow(SmsCategory.PERSONAL)
    val selectedCategory: StateFlow<SmsCategory> = _selectedCategory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isDefaultSmsApp = MutableStateFlow(systemSmsHelper.isDefaultSmsApp())
    val isDefaultSmsApp: StateFlow<Boolean> = _isDefaultSmsApp.asStateFlow()

    private val _deviceSystemSmsCount = MutableStateFlow(0)

    private val _isBackupInProgress = MutableStateFlow(false)
    val isBackupInProgress: StateFlow<Boolean> = _isBackupInProgress.asStateFlow()

    private val _pendingAssignCategory = MutableStateFlow<AssignCategoryRequest?>(null)
    val pendingAssignCategory: StateFlow<AssignCategoryRequest?> = _pendingAssignCategory.asStateFlow()

    val syncProgress: StateFlow<SyncProgressState> = repository.syncProgress

    val activeThreads: StateFlow<List<ThreadSummary>> = combine(
        _selectedCategory,
        _searchQuery
    ) { cat, query ->
        cat to query.trim()
    }.flatMapLatest { (cat, query) ->
        if (query.isEmpty()) {
            repository.observeCategoryThreads(cat)
        } else {
            repository.searchActiveThreads(query)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val archivedThreads: StateFlow<List<ThreadSummary>> = repository.observeArchivedThreads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val categoryCounts: StateFlow<Map<SmsCategory, CategoryUnreadCount>> = repository.observeCategoryCounts()
        .map { list -> list.associateBy { it.category } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap()
        )

    val archivedMessageCount: StateFlow<Int> = repository.observeArchivedMessageCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val totalMessageCount: StateFlow<Int> = repository.observeTotalMessageCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    val activeRegionBadge: StateFlow<String> = settings
        .map { s ->
            val (profile, label) = regionDetector.detectActiveRegion(s.regionOverride)
            "${profile.code} • $label"
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "AUTO"
        )

    // Strictly true ONLY on an emulator when both device SMS provider and Room DB have 0 messages
    val showEmulatorDemoOption: StateFlow<Boolean> = combine(
        totalMessageCount,
        _deviceSystemSmsCount
    ) { roomCount, sysCount ->
        systemSmsHelper.isRunningOnEmulator() && roomCount == 0 && sysCount == 0
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = systemSmsHelper.isRunningOnEmulator()
    )

    init {
        refreshPermissionsAndSyncIfAuthorized()
    }

    fun refreshPermissionsAndSyncIfAuthorized() {
        val defaultHeld = systemSmsHelper.isDefaultSmsApp()
        _isDefaultSmsApp.value = defaultHeld
        viewModelScope.launch {
            if (systemSmsHelper.hasReadSmsPermission()) {
                _deviceSystemSmsCount.value = systemSmsHelper.getSystemSmsCount()
                repository.syncDeviceSmsInChunks()
            } else {
                _deviceSystemSmsCount.value = 0
            }
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(onboardingDismissed = true) }
        }
    }

    fun selectCategory(category: SmsCategory) {
        _selectedCategory.value = category
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun observeThreadMessages(threadKey: String): Flow<List<SmsMessageEntity>> =
        repository.observeThreadMessages(threadKey)

    fun markThreadRead(threadKey: String) {
        viewModelScope.launch {
            repository.markThreadAsRead(threadKey)
        }
    }

    fun setThreadArchived(threadKey: String, archived: Boolean) {
        viewModelScope.launch {
            repository.setThreadArchived(threadKey, archived)
        }
    }

    fun setThreadsArchived(threadKeys: List<String>, archived: Boolean) {
        viewModelScope.launch {
            repository.setThreadsArchived(threadKeys, archived)
        }
    }

    fun requestAssignCategory(request: AssignCategoryRequest) {
        _pendingAssignCategory.value = request
    }

    fun dismissAssignCategory() {
        _pendingAssignCategory.value = null
    }

    fun assignSenderCategory(
        threadKey: String,
        senderTitle: String,
        targetCategory: SmsCategory,
        savePermanentRule: Boolean
    ) {
        viewModelScope.launch {
            repository.assignSenderCategory(
                threadKey = threadKey,
                displaySender = senderTitle,
                targetCategory = targetCategory,
                savePermanentRule = savePermanentRule
            )
        }
    }

    fun sendSms(
        recipient: String,
        body: String,
        onResult: (Result<String>) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.sendOutgoingSms(recipient, body)
            onResult(result)
        }
    }

    fun deleteSingleMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteSingleMessage(messageId)
        }
    }

    fun deleteThread(threadKey: String) {
        viewModelScope.launch {
            repository.deleteThreadPermanently(threadKey)
        }
    }

    fun deleteThreads(threadKeys: List<String>) {
        viewModelScope.launch {
            repository.deleteThreadsPermanently(threadKeys)
        }
    }

    suspend fun countActiveMessagesInCategory(category: SmsCategory): Int =
        repository.countActiveMessagesInCategory(category)

    fun deleteAllInCategory(category: SmsCategory, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val deleted = repository.deleteAllActiveInCategory(category)
            onDone(deleted)
        }
    }

    fun deleteAllMessagesInApp() {
        viewModelScope.launch {
            repository.deleteAllMessagesInApp()
            _deviceSystemSmsCount.value = 0
        }
    }

    fun seedDemoInbox() {
        viewModelScope.launch {
            repository.seedEmulatorDemoInbox()
        }
    }

    // Settings updates
    fun updatePromoAutoDeleteDays(days: Int) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(promoAutoDeleteDays = days) }
            workScheduler.syncPromotionalCleanupSchedule(days)
            if (days > 0) {
                repository.purgeExpiredPromotionalMessages(days)
            }
        }
    }

    fun updateLeftSwipeAction(action: LeftSwipeAction) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(leftSwipeAction = action) }
        }
    }

    fun updateUnarchiveOnNewSms(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(unarchiveOnNewSms = enabled) }
        }
    }

    fun updateSmsFontSize(size: SmsFontSize) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(smsFontSize = size) }
        }
    }

    fun updateSmsFontBold(bold: Boolean) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(smsFontBold = bold) }
        }
    }

    fun updateRegionOverride(regionCode: String) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(regionOverride = regionCode) }
            repository.rerunCategorizationOnAllMessages()
        }
    }

    fun saveDriveDestination(uriString: String, fileName: String) {
        viewModelScope.launch {
            var newSchedule: BackupSchedule? = null
            var customDays = 7
            settingsStore.updateSettings { current ->
                // Default to Weekly once both Drive URI and passphrase are set
                val resolvedSchedule = if (current.backupSchedule == BackupSchedule.OFF &&
                    uriString.isNotBlank() &&
                    current.backupPassphrase.isNotBlank()
                ) {
                    BackupSchedule.WEEKLY
                } else {
                    current.backupSchedule
                }
                newSchedule = resolvedSchedule
                customDays = current.customBackupDays
                current.copy(
                    persistedDriveUri = uriString,
                    driveFileName = fileName,
                    backupSchedule = resolvedSchedule
                )
            }
            newSchedule?.let { workScheduler.syncBackupSchedule(it, customDays) }
        }
    }

    fun saveBackupPassphrase(passphrase: String) {
        viewModelScope.launch {
            var newSchedule: BackupSchedule? = null
            var customDays = 7
            settingsStore.updateSettings { current ->
                // Default to Weekly once both Drive URI and passphrase are set
                val resolvedSchedule = if (current.backupSchedule == BackupSchedule.OFF &&
                    current.persistedDriveUri.isNotBlank() &&
                    passphrase.isNotBlank()
                ) {
                    BackupSchedule.WEEKLY
                } else {
                    current.backupSchedule
                }
                newSchedule = resolvedSchedule
                customDays = current.customBackupDays
                current.copy(
                    backupPassphrase = passphrase,
                    backupSchedule = resolvedSchedule
                )
            }
            newSchedule?.let { workScheduler.syncBackupSchedule(it, customDays) }
        }
    }

    fun updateBackupSchedule(schedule: BackupSchedule, customDays: Int) {
        viewModelScope.launch {
            val safeDays = customDays.coerceAtLeast(1)
            settingsStore.updateSettings {
                it.copy(backupSchedule = schedule, customBackupDays = safeDays)
            }
            workScheduler.syncBackupSchedule(schedule, safeDays)
        }
    }

    fun runManualBackupNow(onComplete: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            _isBackupInProgress.value = true
            try {
                val snap = settingsStore.getSettingsSnapshot()
                if (snap.persistedDriveUri.isBlank() || snap.backupPassphrase.isBlank()) {
                    onComplete(Result.failure(IllegalStateException("Backup URI or passphrase missing.")))
                    return@launch
                }
                val uri = Uri.parse(snap.persistedDriveUri)
                val result = repository.exportEncryptedBackupToUri(uri, snap.backupPassphrase)
                onComplete(result)
            } finally {
                _isBackupInProgress.value = false
            }
        }
    }

    fun runRestoreFromUri(
        sourceUri: Uri,
        passphrase: String,
        onComplete: (Result<RestoreSummary>) -> Unit
    ) {
        viewModelScope.launch {
            _isBackupInProgress.value = true
            try {
                val result = repository.restoreEncryptedBackupFromUri(sourceUri, passphrase)
                onComplete(result)
            } finally {
                _isBackupInProgress.value = false
            }
        }
    }

    fun updateAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.updateSettings { it.copy(appLockEnabled = enabled) }
        }
    }

    fun updateBlockScreenshots(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.updateSettings {
                it.copy(
                    blockScreenshots = enabled,
                    userForcedFlagSecureInStream = enabled
                )
            }
        }
    }

    fun rerunCategorizationOnAll(onComplete: (Int) -> Unit) {
        viewModelScope.launch {
            val count = repository.rerunCategorizationOnAllMessages()
            onComplete(count)
        }
    }

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(container) as T
                }
            }
    }
}
