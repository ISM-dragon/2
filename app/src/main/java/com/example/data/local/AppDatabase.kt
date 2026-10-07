package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.data.local.migration.DatabaseMigrations

/**
 * The single source of truth for persisted state.
 *
 * Version history:
 *  - 1 -> 2: repair migration for pre-release installs (see [DatabaseMigrations]).
 *  - 2 -> 3: durable automation execution model (job leases, idempotency ledger, run counters).
 *  - 3 -> 4: canonical US property model, source/provenance/import-job tracking, comps,
 *    enrichments and asset-level financials.
 *
 * There is deliberately **no** `fallbackToDestructiveMigration`: every schema change must ship a
 * migration that copies data forward. If Room cannot find a migration path it will throw at open
 * time instead of silently wiping the investor's data.
 */
@Database(
    entities = [
        PropertyEntity::class,
        PropertyImageEntity::class,
        MarketDataEntity::class,
        RentEstimateEntity::class,
        TaxRecordEntity::class,
        SalesHistoryEntity::class,
        PropertyCompEntity::class,
        PropertySourceEntity::class,
        PropertyProvenanceEntity::class,
        PropertyImportJobEntity::class,
        PropertyEnrichmentEntity::class,
        PropertyFinancialEntity::class,
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
        AutomationExecutionEntity::class,
        ApiConfigurationEntity::class,
        GmailConfigurationEntity::class,
        OfferTemplateEntity::class,
        OfferEmailSendEntity::class,
        OfferAuditEventEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun propertyDao(): PropertyDao
    abstract fun financialDao(): FinancialDao
    abstract fun offerDao(): OfferDao
    abstract fun automationDao(): AutomationDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun configDao(): ConfigDao
    abstract fun propertySourceDao(): PropertySourceDao
    abstract fun propertyEnrichmentDao(): PropertyEnrichmentDao
    abstract fun propertyFinancialDao(): PropertyFinancialDao

    companion object {
        const val DATABASE_NAME = "real_estate_ai.db"
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `offer_email_sends` (`idempotencyKey` TEXT NOT NULL, `offerId` TEXT NOT NULL, `status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `startedAt` INTEGER, `sentAt` INTEGER, `messageId` TEXT, `nextAttemptAt` INTEGER, `lastError` TEXT, `lastFailureKind` TEXT, PRIMARY KEY(`idempotencyKey`))""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_offer_email_sends_offerId` ON `offer_email_sends` (`offerId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_email_sends_status` ON `offer_email_sends` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_email_sends_nextAttemptAt` ON `offer_email_sends` (`nextAttemptAt`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `offer_audit_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `offerId` TEXT NOT NULL, `idempotencyKey` TEXT, `eventType` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `status` TEXT, `details` TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_audit_events_offerId_timestamp` ON `offer_audit_events` (`offerId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offer_audit_events_idempotencyKey` ON `offer_audit_events` (`idempotencyKey`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*DatabaseMigrations.ALL, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
