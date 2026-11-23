package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.data.crypto.TinkKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SmsMessageEntity::class,
        SenderCategoryRuleEntity::class
    ],
    version = 1,
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
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
