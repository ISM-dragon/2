package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_sources` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `propertyId` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `sourceUrl` TEXT NOT NULL,
                `listingId` TEXT,
                `isPrimary` INTEGER NOT NULL,
                `lastSyncedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_sources_propertyId` ON `property_sources` (`propertyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_sources_sourceUrl` ON `property_sources` (`sourceUrl`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_provenance` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `propertyId` TEXT NOT NULL,
                `field` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `tier` TEXT NOT NULL,
                `retrievedAt` INTEGER NOT NULL,
                `confidence` REAL NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_provenance_propertyId` ON `property_provenance` (`propertyId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_provenance_field` ON `property_provenance` (`field`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_import_jobs` (
                `jobId` TEXT PRIMARY KEY NOT NULL,
                `propertyId` TEXT,
                `source` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `startedAt` INTEGER,
                `completedAt` INTEGER,
                `errorCode` TEXT,
                `errorMessage` TEXT,
                `retryCount` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_import_jobs_url` ON `property_import_jobs` (`url`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_import_jobs_propertyId` ON `property_import_jobs` (`propertyId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_comps` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `targetPropertyId` TEXT NOT NULL,
                `address` TEXT NOT NULL,
                `city` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `zipCode` TEXT NOT NULL,
                `price` REAL NOT NULL,
                `bedrooms` INTEGER NOT NULL,
                `bathrooms` REAL NOT NULL,
                `squareFeet` INTEGER NOT NULL,
                `distanceMiles` REAL NOT NULL,
                `pricePerSqFt` REAL NOT NULL,
                `similarityScore` INTEGER NOT NULL,
                `saleDate` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_property_comps_targetPropertyId` ON `property_comps` (`targetPropertyId`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_enrichment` (
                `propertyId` TEXT PRIMARY KEY NOT NULL,
                `floodZone` TEXT NOT NULL,
                `floodRiskLevel` TEXT NOT NULL,
                `censusTract` TEXT NOT NULL,
                `medianHouseholdIncome` REAL NOT NULL,
                `schoolRating` INTEGER NOT NULL,
                `crimeIndex` TEXT NOT NULL,
                `walkScore` INTEGER NOT NULL,
                `rentBenchmark` REAL NOT NULL,
                `marketAppreciationRate` REAL NOT NULL,
                `taxAssessmentValue` REAL NOT NULL,
                `lastEnrichedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_financials` (
                `propertyId` TEXT PRIMARY KEY NOT NULL,
                `strategy` TEXT NOT NULL,
                `financingType` TEXT NOT NULL,
                `purchasePrice` REAL NOT NULL,
                `downPayment` REAL NOT NULL,
                `loanAmount` REAL NOT NULL,
                `interestRate` REAL NOT NULL,
                `monthlyRent` REAL NOT NULL,
                `operatingExpensesMonthly` REAL NOT NULL,
                `netOperatingIncomeAnnual` REAL NOT NULL,
                `monthlyDebtService` REAL NOT NULL,
                `monthlyCashFlow` REAL NOT NULL,
                `annualCashFlow` REAL NOT NULL,
                `capRate` REAL NOT NULL,
                `cashOnCashReturn` REAL NOT NULL,
                `dscr` REAL NOT NULL,
                `ltv` REAL NOT NULL,
                `totalCashRequired` REAL NOT NULL,
                `dealScore` INTEGER NOT NULL,
                `calculatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `property_ai_analysis` (
                `propertyId` TEXT PRIMARY KEY NOT NULL,
                `summary` TEXT NOT NULL,
                `investmentThesis` TEXT NOT NULL,
                `strengthsJson` TEXT NOT NULL,
                `weaknessesJson` TEXT NOT NULL,
                `risksJson` TEXT NOT NULL,
                `redFlagsJson` TEXT NOT NULL,
                `recommendedStrategy` TEXT NOT NULL,
                `recommendedOfferRange` TEXT NOT NULL,
                `questionsForSellerJson` TEXT NOT NULL,
                `dueDiligenceJson` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `evidenceJson` TEXT NOT NULL,
                `analyzedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `source_health` (
                `source` TEXT PRIMARY KEY NOT NULL,
                `successCount` INTEGER NOT NULL,
                `failureCount` INTEGER NOT NULL,
                `successRate` REAL NOT NULL,
                `failureRate` REAL NOT NULL,
                `averageLatencyMs` INTEGER NOT NULL,
                `lastSuccessAt` INTEGER,
                `lastFailureAt` INTEGER,
                `parserVersion` TEXT NOT NULL,
                `healthStatus` TEXT NOT NULL,
                `lastErrorReason` TEXT
            )
            """.trimIndent()
        )
    }
}

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
        PropertySourceLinkEntity::class,
        PropertyProvenanceEntity::class,
        PropertyImportJobEntity::class,
        PropertyCompEntity::class,
        PropertyEnrichmentEntity::class,
        PropertyFinancialEntity::class,
        PropertyAiAnalysisEntity::class,
        SourceHealthEntity::class
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
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
