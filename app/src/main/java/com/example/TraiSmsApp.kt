package com.example

import android.app.Application
import android.content.Context
import com.example.data.crypto.EncryptedSettingsStore
import com.example.data.crypto.TinkKeyManager
import com.example.data.db.TraiSmsDatabase
import com.example.data.sms.ContactLookupCache
import com.example.data.sms.SmsNotificationHelper
import com.example.data.sms.SmsRepository
import com.example.data.sms.SystemSmsHelper
import com.example.domain.classifier.OfflineSmsMlClassifier
import com.example.domain.classifier.RegionProfileDetector
import com.example.domain.classifier.SmsCategorizationPipeline
import com.example.worker.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val tinkKeyManager by lazy { TinkKeyManager(appContext) }
    val settingsStore by lazy { EncryptedSettingsStore(appContext, tinkKeyManager) }
    val database by lazy { TraiSmsDatabase.getInstance(appContext, tinkKeyManager) }
    val regionDetector by lazy { RegionProfileDetector(appContext) }
    val mlClassifier by lazy { OfflineSmsMlClassifier(appContext) }
    val pipeline by lazy { SmsCategorizationPipeline(mlClassifier) }
    val contactCache by lazy { ContactLookupCache(appContext) }
    val systemSmsHelper by lazy { SystemSmsHelper(appContext) }
    val notificationHelper by lazy { SmsNotificationHelper(appContext) }
    val workScheduler by lazy { WorkScheduler(appContext) }

    val smsRepository by lazy {
        SmsRepository(
            context = appContext,
            smsDao = database.smsDao(),
            senderRuleDao = database.senderRuleDao(),
            settingsStore = settingsStore,
            regionDetector = regionDetector,
            pipeline = pipeline,
            contactCache = contactCache,
            systemSmsHelper = systemSmsHelper,
            notificationHelper = notificationHelper
        )
    }
}

class TraiSmsApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        appScope.launch {
            runCatching {
                val settings = container.settingsStore.getSettingsSnapshot()
                container.workScheduler.syncPromotionalCleanupSchedule(settings.promoAutoDeleteDays)
                container.workScheduler.syncBackupSchedule(settings.backupSchedule, settings.customBackupDays)
            }
        }
    }
}
