package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.crypto.TinkKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SmsMessageEntity::class,
        SenderCategoryRuleEntity::class,
        MmsAttachmentEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(SmsTypeConverters::class)
abstract class TraiSmsDatabase : RoomDatabase() {

    abstract fun smsDao(): SmsDao
    abstract fun senderRuleDao(): SenderRuleDao

    /**
     * Defers Keystore passphrase derivation and native `libsqlcipher.so` loading until Room
     * opens the database on a background IO thread, keeping MainActivity first-frame startup instant.
     */
    private class LazySqlCipherOpenHelperFactory(
        private val tinkKeyManager: TinkKeyManager
    ) : SupportSQLiteOpenHelper.Factory {

        @Volatile
        private var delegate: SupportSQLiteOpenHelper.Factory? = null

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val activeFactory = delegate ?: synchronized(this) {
                delegate ?: createUnderlyingFactory().also { delegate = it }
            }
            return activeFactory.create(configuration)
        }

        private fun createUnderlyingFactory(): SupportSQLiteOpenHelper.Factory {
            val passphrase = tinkKeyManager.getOrCreateDatabasePassphrase()
            return try {
                System.loadLibrary("sqlcipher")
                SupportOpenHelperFactory(passphrase.copyOf())
            } catch (_: Throwable) {
                FrameworkSQLiteOpenHelperFactory()
            } finally {
                passphrase.fill(0)
            }
        }
    }

    companion object {
        private const val DB_NAME = "traisms_encrypted_vault.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN transportType TEXT NOT NULL DEFAULT 'SMS'")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN deliveryStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN systemMmsId INTEGER")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN mmsSubject TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN mmsContentLocation TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN mmsTransactionId TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN mmsSizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN groupRecipientsJson TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN replyToMessageId INTEGER")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN replyToSender TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN replyToSnippet TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN reactionsJson TEXT")
                db.execSQL("ALTER TABLE sms_messages ADD COLUMN isHiddenReaction INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mms_attachments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `messageId` INTEGER NOT NULL,
                        `partIndex` INTEGER NOT NULL,
                        `contentType` TEXT NOT NULL,
                        `fileName` TEXT,
                        `localFilePath` TEXT,
                        `contentId` TEXT,
                        `sizeBytes` INTEGER NOT NULL,
                        `textContent` TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mms_attachments_messageId` ON `mms_attachments` (`messageId`)"
                )
            }
        }

        @Volatile
        private var instance: TraiSmsDatabase? = null

        fun getInstance(context: Context, tinkKeyManager: TinkKeyManager): TraiSmsDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance?.let { return it }
                val appContext = context.applicationContext
                Room.databaseBuilder(
                    appContext,
                    TraiSmsDatabase::class.java,
                    DB_NAME
                )
                    .openHelperFactory(LazySqlCipherOpenHelperFactory(tinkKeyManager))
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
