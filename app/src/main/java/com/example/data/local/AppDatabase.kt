package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import com.example.data.local.migration.DatabaseMigrations

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
        OfferEmailSendEntity::class,
        OfferAuditEventEntity::class,
        AutomationRuleEntity::class,
        AutomationRunEntity::class,
        AutomationLogEntity::class,
        AutomationStateEntity::class,
        AutomationJobEntity::class,
        AutomationExecutionEntity::class,
        ApiConfigurationEntity::class,
        GmailConfigurationEntity::class,
        OfferTemplateEntity::class,
        PropertySourceEntity::class,
        PropertyProvenanceEntity::class,
        PropertyImportJobEntity::class,
        PropertyEnrichmentEntity::class,
        PropertyFinancialEntity::class,
        PropertySourceLinkEntity::class,
        PropertyAiAnalysisEntity::class,
        SourceHealthEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun propertyDao(): PropertyDao
    abstract fun propertySourceDao(): PropertySourceDao
    abstract fun propertyEnrichmentDao(): PropertyEnrichmentDao
    abstract fun propertyFinancialDao(): PropertyFinancialDao
    abstract fun financialDao(): FinancialDao
    abstract fun offerDao(): OfferDao
    abstract fun automationDao(): AutomationDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun configDao(): ConfigDao
    abstract fun intelligenceDao(): IntelligenceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "real_estate_ai.db"
                )
                    .addMigrations(*DatabaseMigrations.ALL)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

