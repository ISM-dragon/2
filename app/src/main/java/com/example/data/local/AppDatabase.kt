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
 *  - 2 -> 3: canonical US property model, source/provenance/import-job tracking, comps,
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
        ApiConfigurationEntity::class,
        GmailConfigurationEntity::class,
        OfferTemplateEntity::class
    ],
    version = 3,
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

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*DatabaseMigrations.ALL)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
