package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*

@Database(
    entities = [
        PropertyEntity::class,
        PropertyImageEntity::class,
        MarketDataEntity::class,
        RentEstimateEntity::class,
        TaxRecordEntity::class,
        SalesHistoryEntity::class,
        ComparablePropertyEntity::class,
        FinancialAnalysisEntity::class,
        FinancingScenarioEntity::class,
        SavedPropertyEntity::class,
        SavedDealEntity::class,
        AIConversationEntity::class,
        AIMessageEntity::class,
        OfferEntity::class,
        OfferDocumentEntity::class,
        AutomationRuleEntity::class,
        AutomationRunEntity::class,
        AutomationLogEntity::class,
        AutomationStateEntity::class,
        AutomationJobEntity::class,
        ApiConfigurationEntity::class,
        GmailConfigurationEntity::class,
        OfferTemplateEntity::class,
        OfferEmailSendEntity::class,
        OfferAuditEventEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun propertyDao(): PropertyDao
    abstract fun financialDao(): FinancialDao
    abstract fun offerDao(): OfferDao
    abstract fun automationDao(): AutomationDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun configDao(): ConfigDao

    companion object {
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `offer_email_sends` (
                        `idempotencyKey` TEXT NOT NULL,
                        `offerId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `startedAt` INTEGER,
                        `sentAt` INTEGER,
                        `messageId` TEXT,
                        `nextAttemptAt` INTEGER,
                        `lastError` TEXT,
                        `lastFailureKind` TEXT,
                        PRIMARY KEY(`idempotencyKey`)
                    )""".trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offer_email_sends_offerId` ON `offer_email_sends` (`offerId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_email_sends_status` ON `offer_email_sends` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_email_sends_nextAttemptAt` ON `offer_email_sends` (`nextAttemptAt`)")
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS `offer_audit_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `offerId` TEXT NOT NULL,
                        `idempotencyKey` TEXT,
                        `eventType` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `status` TEXT,
                        `details` TEXT
                    )""".trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_audit_events_offerId_timestamp` ON `offer_audit_events` (`offerId`, `timestamp`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_audit_events_idempotencyKey` ON `offer_audit_events` (`idempotencyKey`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "real_estate_ai.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
