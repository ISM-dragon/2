package com.example.data.local.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Data layer migrations.
 *
 * Strategy (deliberate, no destructive fallback):
 *  - Every migration is additive or rebuilding: rows are copied forward, never dropped.
 *  - The pre-v3 rebuild uses the classic Room "create new, copy, drop old, rename" pattern,
 *    which is the only way to add NOT NULL columns, foreign keys and indices to an existing
 *    SQLite table without leaving DEFAULT clauses behind that Room's schema validation rejects.
 *  - `Migration1To2` normalizes *any* pre-v2 shape to the exact v2 shape; it exists because the
 *    repository never shipped a real 1 -> 2 migration (version 2 was released with
 *    fallbackToDestructiveMigration), so old installs must be repaired instead of wiped.
 *  - Foreign keys are enforced by SQLite, so orphan rows are filtered out while copying.
 *
 * The `LegacyV2Schema` catalog is the *only* place that knows the historical v2 shape; it is
 * checked by `tools/room_schema_guard.py` against the v2 entities from git history.
 */
internal data class LegacyTableShape(
    val tableName: String,
    val columns: List<String>,
    val createTableSql: String,
    val indexSql: List<String>
)

/** Exact schema of database version 2 (generated from the v2 entities, verified by tools/room_schema_guard.py). */
internal object LegacyV2Schema {
    val TABLES: List<LegacyTableShape> = listOf(
        LegacyTableShape(
            tableName = "ai_conversations",
            columns = listOf("id", "propertyId", "title", "createdAt", "updatedAt"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `ai_conversations` (`id` TEXT NOT NULL, `propertyId` TEXT, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "ai_messages",
            columns = listOf("id", "conversationId", "role", "content", "timestamp"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `ai_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "api_configurations",
            columns = listOf("slotIndex", "label", "apiKey", "model", "projectId", "status", "lastRequestTime", "cooldownUntil", "usageCount", "errorCount", "lastErrorMessage"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `api_configurations` (`slotIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `apiKey` TEXT NOT NULL, `model` TEXT NOT NULL, `projectId` TEXT NOT NULL, `status` TEXT NOT NULL, `lastRequestTime` INTEGER NOT NULL, `cooldownUntil` INTEGER NOT NULL, `usageCount` INTEGER NOT NULL, `errorCount` INTEGER NOT NULL, `lastErrorMessage` TEXT, PRIMARY KEY(`slotIndex`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "automation_jobs",
            columns = listOf("jobId", "runId", "propertyId", "propertyAddress", "currentState", "lastSuccessfulState", "failedStep", "analysisId", "offerId", "emailMessageId", "recipientEmail", "attempts", "maxRetries", "lastError", "blockageReason", "createdAt", "updatedAt"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `automation_jobs` (`jobId` TEXT NOT NULL, `runId` INTEGER NOT NULL, `propertyId` TEXT NOT NULL, `propertyAddress` TEXT NOT NULL, `currentState` TEXT NOT NULL, `lastSuccessfulState` TEXT NOT NULL, `failedStep` TEXT, `analysisId` TEXT, `offerId` TEXT, `emailMessageId` TEXT, `recipientEmail` TEXT, `attempts` INTEGER NOT NULL, `maxRetries` INTEGER NOT NULL, `lastError` TEXT, `blockageReason` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`jobId`))""",
            indexSql = listOf(
                """CREATE INDEX IF NOT EXISTS `index_automation_jobs_propertyId` ON `automation_jobs` (`propertyId`)""",
                """CREATE INDEX IF NOT EXISTS `index_automation_jobs_currentState` ON `automation_jobs` (`currentState`)""",
                """CREATE INDEX IF NOT EXISTS `index_automation_jobs_runId` ON `automation_jobs` (`runId`)""",
            )
        ),
        LegacyTableShape(
            tableName = "automation_logs",
            columns = listOf("id", "runId", "timestamp", "level", "tag", "message"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `automation_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER, `timestamp` INTEGER NOT NULL, `level` TEXT NOT NULL, `tag` TEXT NOT NULL, `message` TEXT NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "automation_rules",
            columns = listOf("id", "maxPurchasePrice", "minCashFlow", "minCapRate", "minDscr", "minCashOnCash", "allowedLocations", "allowedPropertyTypes", "maxRenovationCost", "minEstimatedRent", "maxRiskScore", "offerDiscountPercent", "scanIntervalMinutes", "maxPropertiesPerCycle", "maxAnalysesPerRun", "maxOffersPerRun", "maxEmailsPerRun", "maxRetries", "autoGenerateOffers", "autoSendOffers", "consecutiveFailureThreshold"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `automation_rules` (`id` TEXT NOT NULL, `maxPurchasePrice` REAL NOT NULL, `minCashFlow` REAL NOT NULL, `minCapRate` REAL NOT NULL, `minDscr` REAL NOT NULL, `minCashOnCash` REAL NOT NULL, `allowedLocations` TEXT NOT NULL, `allowedPropertyTypes` TEXT NOT NULL, `maxRenovationCost` REAL NOT NULL, `minEstimatedRent` REAL NOT NULL, `maxRiskScore` INTEGER NOT NULL, `offerDiscountPercent` REAL NOT NULL, `scanIntervalMinutes` INTEGER NOT NULL, `maxPropertiesPerCycle` INTEGER NOT NULL, `maxAnalysesPerRun` INTEGER NOT NULL, `maxOffersPerRun` INTEGER NOT NULL, `maxEmailsPerRun` INTEGER NOT NULL, `maxRetries` INTEGER NOT NULL, `autoGenerateOffers` INTEGER NOT NULL, `autoSendOffers` INTEGER NOT NULL, `consecutiveFailureThreshold` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "automation_runs",
            columns = listOf("id", "startTime", "endTime", "propertiesFound", "propertiesAnalyzed", "dealsQualified", "offersCreated", "offersSent", "status", "summary"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `automation_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `propertiesFound` INTEGER NOT NULL, `propertiesAnalyzed` INTEGER NOT NULL, `dealsQualified` INTEGER NOT NULL, `offersCreated` INTEGER NOT NULL, `offersSent` INTEGER NOT NULL, `status` TEXT NOT NULL, `summary` TEXT NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "automation_state",
            columns = listOf("id", "isEnabled", "currentOperation", "currentPropertyAddress", "currentStage", "successfulJobs", "failedJobs", "lastError", "lastSuccessfulAction", "lastActivityTime"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `automation_state` (`id` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, `currentOperation` TEXT NOT NULL, `currentPropertyAddress` TEXT NOT NULL, `currentStage` TEXT NOT NULL, `successfulJobs` INTEGER NOT NULL, `failedJobs` INTEGER NOT NULL, `lastError` TEXT, `lastSuccessfulAction` TEXT NOT NULL, `lastActivityTime` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "comparable_properties",
            columns = listOf("id", "targetPropertyId", "compAddress", "compPrice", "compBeds", "compBaths", "compSqFt", "distanceMiles", "saleDate", "adjustmentAmount"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `comparable_properties` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetPropertyId` TEXT NOT NULL, `compAddress` TEXT NOT NULL, `compPrice` REAL NOT NULL, `compBeds` INTEGER NOT NULL, `compBaths` REAL NOT NULL, `compSqFt` INTEGER NOT NULL, `distanceMiles` REAL NOT NULL, `saleDate` TEXT NOT NULL, `adjustmentAmount` REAL NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "financial_analyses",
            columns = listOf("propertyId", "purchasePrice", "closingCosts", "renovationCost", "monthlyRent", "otherMonthlyIncome", "vacancyRatePct", "propertyTaxAnnual", "insuranceAnnual", "maintenancePct", "managementPct", "utilitiesMonthly", "downPaymentPct", "interestRatePct", "loanTermYears", "grossRentalIncome", "effectiveRentalIncome", "operatingExpensesMonthly", "noiAnnual", "monthlyDebtService", "monthlyCashFlow", "annualCashFlow", "capRate", "cashOnCashReturn", "dscr", "breakEvenOccupancyPct", "totalCashRequired", "calculatedAt", "isQualified", "dealScore", "qualificationSummary"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `financial_analyses` (`propertyId` TEXT NOT NULL, `purchasePrice` REAL NOT NULL, `closingCosts` REAL NOT NULL, `renovationCost` REAL NOT NULL, `monthlyRent` REAL NOT NULL, `otherMonthlyIncome` REAL NOT NULL, `vacancyRatePct` REAL NOT NULL, `propertyTaxAnnual` REAL NOT NULL, `insuranceAnnual` REAL NOT NULL, `maintenancePct` REAL NOT NULL, `managementPct` REAL NOT NULL, `utilitiesMonthly` REAL NOT NULL, `downPaymentPct` REAL NOT NULL, `interestRatePct` REAL NOT NULL, `loanTermYears` INTEGER NOT NULL, `grossRentalIncome` REAL NOT NULL, `effectiveRentalIncome` REAL NOT NULL, `operatingExpensesMonthly` REAL NOT NULL, `noiAnnual` REAL NOT NULL, `monthlyDebtService` REAL NOT NULL, `monthlyCashFlow` REAL NOT NULL, `annualCashFlow` REAL NOT NULL, `capRate` REAL NOT NULL, `cashOnCashReturn` REAL NOT NULL, `dscr` REAL NOT NULL, `breakEvenOccupancyPct` REAL NOT NULL, `totalCashRequired` REAL NOT NULL, `calculatedAt` INTEGER NOT NULL, `isQualified` INTEGER NOT NULL, `dealScore` INTEGER NOT NULL, `qualificationSummary` TEXT NOT NULL, PRIMARY KEY(`propertyId`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "financing_scenarios",
            columns = listOf("id", "propertyId", "scenarioName", "downPaymentPct", "interestRatePct", "loanTermYears", "monthlyPayment", "cashRequired", "monthlyCashFlow", "cashOnCash", "dscr"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `financing_scenarios` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `scenarioName` TEXT NOT NULL, `downPaymentPct` REAL NOT NULL, `interestRatePct` REAL NOT NULL, `loanTermYears` INTEGER NOT NULL, `monthlyPayment` REAL NOT NULL, `cashRequired` REAL NOT NULL, `monthlyCashFlow` REAL NOT NULL, `cashOnCash` REAL NOT NULL, `dscr` REAL NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "gmail_configuration",
            columns = listOf("id", "isConnected", "authStatus", "accountEmail", "senderName", "signature", "defaultSubjectTemplate", "defaultCc", "accessToken", "refreshToken", "expiresAt", "lastError"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `gmail_configuration` (`id` INTEGER NOT NULL, `isConnected` INTEGER NOT NULL, `authStatus` TEXT NOT NULL, `accountEmail` TEXT NOT NULL, `senderName` TEXT NOT NULL, `signature` TEXT NOT NULL, `defaultSubjectTemplate` TEXT NOT NULL, `defaultCc` TEXT NOT NULL, `accessToken` TEXT, `refreshToken` TEXT, `expiresAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "market_data",
            columns = listOf("propertyId", "estimatedValue", "neighborhoodAppreciationRate", "medianAreaPrice", "averageDaysOnMarket", "pricePerSqFt", "marketDemand"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `market_data` (`propertyId` TEXT NOT NULL, `estimatedValue` REAL NOT NULL, `neighborhoodAppreciationRate` REAL NOT NULL, `medianAreaPrice` REAL NOT NULL, `averageDaysOnMarket` INTEGER NOT NULL, `pricePerSqFt` REAL NOT NULL, `marketDemand` TEXT NOT NULL, PRIMARY KEY(`propertyId`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "offer_documents",
            columns = listOf("id", "offerId", "fileName", "filePath", "fileSizeBytes", "createdAt"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `offer_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `offerId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `filePath` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "offer_templates",
            columns = listOf("id", "templateName", "headerTitle", "earnestMoneyPercent", "defaultInspectionDays", "defaultClosingDays", "standardTerms", "standardConditions"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `offer_templates` (`id` TEXT NOT NULL, `templateName` TEXT NOT NULL, `headerTitle` TEXT NOT NULL, `earnestMoneyPercent` REAL NOT NULL, `defaultInspectionDays` INTEGER NOT NULL, `defaultClosingDays` INTEGER NOT NULL, `standardTerms` TEXT NOT NULL, `standardConditions` TEXT NOT NULL, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "offers",
            columns = listOf("id", "propertyId", "recipientName", "recipientEmail", "offerPrice", "earnestMoney", "inspectionPeriodDays", "closingPeriodDays", "contingencies", "terms", "conditions", "expirationDate", "generatedLetterContent", "pdfPath", "status", "createdAt", "sentAt", "lastError"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `offers` (`id` TEXT NOT NULL, `propertyId` TEXT NOT NULL, `recipientName` TEXT NOT NULL, `recipientEmail` TEXT NOT NULL, `offerPrice` REAL NOT NULL, `earnestMoney` REAL NOT NULL, `inspectionPeriodDays` INTEGER NOT NULL, `closingPeriodDays` INTEGER NOT NULL, `contingencies` TEXT NOT NULL, `terms` TEXT NOT NULL, `conditions` TEXT NOT NULL, `expirationDate` TEXT NOT NULL, `generatedLetterContent` TEXT NOT NULL, `pdfPath` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sentAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "properties",
            columns = listOf("id", "sourceType", "title", "address", "city", "state", "zipCode", "latitude", "longitude", "price", "propertyType", "bedrooms", "bathrooms", "squareFeet", "yearBuilt", "lotSizeSqFt", "description", "status", "primaryImageUrl", "scannedAt", "isSaved", "isSavedDeal", "dealScore"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `properties` (`id` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `title` TEXT NOT NULL, `address` TEXT NOT NULL, `city` TEXT NOT NULL, `state` TEXT NOT NULL, `zipCode` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `price` REAL NOT NULL, `propertyType` TEXT NOT NULL, `bedrooms` INTEGER NOT NULL, `bathrooms` REAL NOT NULL, `squareFeet` INTEGER NOT NULL, `yearBuilt` INTEGER NOT NULL, `lotSizeSqFt` INTEGER NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `primaryImageUrl` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, `isSaved` INTEGER NOT NULL, `isSavedDeal` INTEGER NOT NULL, `dealScore` INTEGER NOT NULL, PRIMARY KEY(`id`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "property_images",
            columns = listOf("id", "propertyId", "imageUrl", "caption", "isPrimary"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `property_images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `imageUrl` TEXT NOT NULL, `caption` TEXT NOT NULL, `isPrimary` INTEGER NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "rent_estimates",
            columns = listOf("propertyId", "estimatedRent", "rentRangeLow", "rentRangeHigh", "rentConfidenceScore", "grossYield"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `rent_estimates` (`propertyId` TEXT NOT NULL, `estimatedRent` REAL NOT NULL, `rentRangeLow` REAL NOT NULL, `rentRangeHigh` REAL NOT NULL, `rentConfidenceScore` REAL NOT NULL, `grossYield` REAL NOT NULL, PRIMARY KEY(`propertyId`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "sales_history",
            columns = listOf("id", "propertyId", "date", "price", "event"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `sales_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `date` TEXT NOT NULL, `price` REAL NOT NULL, `event` TEXT NOT NULL)""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "saved_deals",
            columns = listOf("dealId", "propertyId", "qualificationReason", "targetOfferPrice", "expectedRoi", "savedAt"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `saved_deals` (`dealId` TEXT NOT NULL, `propertyId` TEXT NOT NULL, `qualificationReason` TEXT NOT NULL, `targetOfferPrice` REAL NOT NULL, `expectedRoi` REAL NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`dealId`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "saved_properties",
            columns = listOf("propertyId", "savedAt", "notes", "tag"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `saved_properties` (`propertyId` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`propertyId`))""",
            indexSql = listOf(
            )
        ),
        LegacyTableShape(
            tableName = "tax_records",
            columns = listOf("propertyId", "annualTaxAmount", "assessmentYear", "assessedValue", "taxDelinquent"),
            createTableSql = """CREATE TABLE IF NOT EXISTS `tax_records` (`propertyId` TEXT NOT NULL, `annualTaxAmount` REAL NOT NULL, `assessmentYear` INTEGER NOT NULL, `assessedValue` REAL NOT NULL, `taxDelinquent` INTEGER NOT NULL, PRIMARY KEY(`propertyId`))""",
            indexSql = listOf(
            )
        ),
    )

    val CREATE_STATEMENTS: List<String> = TABLES.flatMap { shape ->
        listOf(shape.createTableSql) + shape.indexSql
    }
}

/**
 * v1 -> v2 normalizer.
 *
 * v1 installs were never migrated (the previous release destroyed them through
 * `fallbackToDestructiveMigration`), therefore this migration repairs whatever shape it finds:
 * missing tables are created, tables that lost columns are rebuilt to the v2 contract while
 * copying every column that does exist, and missing indices are created. Nothing is deleted.
 */
private class Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (shape in LegacyV2Schema.TABLES) {
            val existing = columnNames(db, shape.tableName)
            when {
                existing.isEmpty() -> {
                    db.execSQL(shape.createTableSql)
                    shape.indexSql.forEach { db.execSQL(it) }
                }
                shape.columns.any { it !in existing } -> rebuildToV2Shape(db, shape)
                else -> shape.indexSql.forEach { db.execSQL(it) }
            }
        }
    }
}

/** Rebuilds a legacy table to the exact v2 shape, preserving every column that exists. */
private fun rebuildToV2Shape(db: SupportSQLiteDatabase, shape: LegacyTableShape) {
    val legacyName = "${shape.tableName}__legacy_v1"
    db.execSQL("DROP TABLE IF EXISTS `$legacyName`")
    db.execSQL("ALTER TABLE `${shape.tableName}` RENAME TO `$legacyName`")
    db.execSQL(shape.createTableSql)
    val existing = columnNames(db, legacyName)
    val target = readColumnInfo(db, shape.tableName)
    val targetList = target.joinToString(", ") { "`${it.name}`" }
    val selectList = target.joinToString(", ") { column ->
        if (column.name in existing) "`${column.name}`" else fallbackLiteral(column)
    }
    db.execSQL("INSERT INTO `${shape.tableName}` ($targetList) SELECT $selectList FROM `$legacyName`")
    db.execSQL("DROP TABLE `$legacyName`")
    shape.indexSql.forEach { db.execSQL(it) }
}

private data class ColumnInfo(val name: String, val type: String, val notNull: Boolean)

private fun readColumnInfo(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
    val columns = mutableListOf<ColumnInfo>()
    db.query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        val typeIndex = cursor.getColumnIndexOrThrow("type")
        val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
        while (cursor.moveToNext()) {
            columns += ColumnInfo(
                name = cursor.getString(nameIndex) ?: "",
                type = cursor.getString(typeIndex) ?: "TEXT",
                notNull = cursor.getInt(notNullIndex) == 1
            )
        }
    }
    return columns
}

private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
    readColumnInfo(db, table).map { it.name }.toSet()

private fun fallbackLiteral(column: ColumnInfo): String {
    if (!column.notNull) return "NULL"
    val type = column.type.uppercase()
    return when {
        "INT" in type -> "0"
        "REAL" in type || "DOUB" in type || "FLOA" in type -> "0.0"
        else -> "''"
    }
}

/**
 * v2 -> v3: canonical US property model, provenance, import jobs, comps, enrichments and
 * asset level financial facts. Purely additive for existing data: every legacy column is copied
 * forward, orphans are filtered (they cannot satisfy the new foreign keys) and no DEFAULT clause
 * is introduced so the resulting schema validates against the entities.
 */
private class Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Index additions that ship with the canonical property model, kept in this step so the
        // v2 -> v3 block stays byte-for-byte equal to the automation schema already released.
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_currentState_attempts` ON `automation_jobs` (`currentState`, `attempts`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_updatedAt` ON `automation_jobs` (`updatedAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_logs_runId` ON `automation_logs` (`runId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_logs_timestamp` ON `automation_logs` (`timestamp`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_logs_level_timestamp` ON `automation_logs` (`level`, `timestamp`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_sources` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sourceKind` TEXT NOT NULL, `adapterKey` TEXT NOT NULL, `description` TEXT NOT NULL, `isEnabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `requiresAttribution` INTEGER NOT NULL, `attributionText` TEXT NOT NULL, `licenseNotes` TEXT NOT NULL, `maxRequestsPerMinute` INTEGER NOT NULL, `refreshIntervalMinutes` INTEGER NOT NULL, `lastSyncAt` INTEGER NOT NULL, `lastSyncStatus` TEXT NOT NULL, `lastSyncError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_property_sources_adapterKey` ON `property_sources` (`adapterKey`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_sources_sourceKind` ON `property_sources` (`sourceKind`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_sources_isEnabled` ON `property_sources` (`isEnabled`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_sources_priority` ON `property_sources` (`priority`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_import_jobs` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `status` TEXT NOT NULL, `triggerKind` TEXT NOT NULL, `cursor` TEXT, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `recordsFetched` INTEGER NOT NULL, `recordsInserted` INTEGER NOT NULL, `recordsUpdated` INTEGER NOT NULL, `recordsMerged` INTEGER NOT NULL, `recordsSkipped` INTEGER NOT NULL, `recordsFailed` INTEGER NOT NULL, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`sourceId`) REFERENCES `property_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_import_jobs_sourceId_status` ON `property_import_jobs` (`sourceId`, `status`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_import_jobs_status_startedAt` ON `property_import_jobs` (`status`, `startedAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_import_jobs_startedAt` ON `property_import_jobs` (`startedAt`)""")
        db.execSQL("""ALTER TABLE `properties` RENAME TO `properties__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `properties` (`id` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `title` TEXT NOT NULL, `address` TEXT NOT NULL, `city` TEXT NOT NULL, `state` TEXT NOT NULL, `zipCode` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `price` REAL NOT NULL, `propertyType` TEXT NOT NULL, `bedrooms` INTEGER NOT NULL, `bathrooms` REAL NOT NULL, `squareFeet` INTEGER NOT NULL, `yearBuilt` INTEGER NOT NULL, `lotSizeSqFt` INTEGER NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `primaryImageUrl` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, `isSaved` INTEGER NOT NULL, `isSavedDeal` INTEGER NOT NULL, `dealScore` INTEGER NOT NULL, `unitNumber` TEXT NOT NULL, `normalizedAddress` TEXT NOT NULL, `canonicalKey` TEXT, `county` TEXT NOT NULL, `countyFips` TEXT NOT NULL, `apn` TEXT NOT NULL, `mlsNumber` TEXT NOT NULL, `propertySubType` TEXT NOT NULL, `halfBathrooms` INTEGER NOT NULL, `stories` INTEGER NOT NULL, `garageSpaces` INTEGER NOT NULL, `hasPool` INTEGER NOT NULL, `hoaMonthly` REAL NOT NULL, `primarySourceId` TEXT, `listingStatusUpdatedAt` INTEGER NOT NULL, `lastVerifiedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`primarySourceId`) REFERENCES `property_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""INSERT INTO `properties` (`id`, `sourceType`, `title`, `address`, `city`, `state`, `zipCode`, `latitude`, `longitude`, `price`, `propertyType`, `bedrooms`, `bathrooms`, `squareFeet`, `yearBuilt`, `lotSizeSqFt`, `description`, `status`, `primaryImageUrl`, `scannedAt`, `isSaved`, `isSavedDeal`, `dealScore`, `unitNumber`, `normalizedAddress`, `canonicalKey`, `county`, `countyFips`, `apn`, `mlsNumber`, `propertySubType`, `halfBathrooms`, `stories`, `garageSpaces`, `hasPool`, `hoaMonthly`, `primarySourceId`, `listingStatusUpdatedAt`, `lastVerifiedAt`)
SELECT `l`.`id`, `l`.`sourceType`, `l`.`title`, `l`.`address`, `l`.`city`, `l`.`state`, `l`.`zipCode`, `l`.`latitude`, `l`.`longitude`, `l`.`price`, `l`.`propertyType`, `l`.`bedrooms`, `l`.`bathrooms`, `l`.`squareFeet`, `l`.`yearBuilt`, `l`.`lotSizeSqFt`, `l`.`description`, `l`.`status`, `l`.`primaryImageUrl`, `l`.`scannedAt`, `l`.`isSaved`, `l`.`isSavedDeal`, `l`.`dealScore`, '', '', NULL, '', '', '', '', '', 0, 0, 0, 0, 0.0, NULL, 0, 0
FROM `properties__v2` AS `l`""")
        db.execSQL("""DROP TABLE `properties__v2`""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_properties_canonicalKey` ON `properties` (`canonicalKey`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_primarySourceId` ON `properties` (`primarySourceId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_state_city` ON `properties` (`state`, `city`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_zipCode` ON `properties` (`zipCode`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_countyFips` ON `properties` (`countyFips`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_apn` ON `properties` (`apn`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_mlsNumber` ON `properties` (`mlsNumber`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_status` ON `properties` (`status`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_scannedAt` ON `properties` (`scannedAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_dealScore` ON `properties` (`dealScore`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_properties_isSaved` ON `properties` (`isSaved`)""")
        db.execSQL("""ALTER TABLE `property_images` RENAME TO `property_images__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_images` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `imageUrl` TEXT NOT NULL, `caption` TEXT NOT NULL, `isPrimary` INTEGER NOT NULL, FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `property_images` (`id`, `propertyId`, `imageUrl`, `caption`, `isPrimary`)
SELECT `l`.`id`, `l`.`propertyId`, `l`.`imageUrl`, `l`.`caption`, `l`.`isPrimary`
FROM `property_images__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `property_images__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_images_propertyId` ON `property_images` (`propertyId`)""")
        db.execSQL("""ALTER TABLE `market_data` RENAME TO `market_data__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `market_data` (`propertyId` TEXT NOT NULL, `estimatedValue` REAL NOT NULL, `neighborhoodAppreciationRate` REAL NOT NULL, `medianAreaPrice` REAL NOT NULL, `averageDaysOnMarket` INTEGER NOT NULL, `pricePerSqFt` REAL NOT NULL, `marketDemand` TEXT NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `market_data` (`propertyId`, `estimatedValue`, `neighborhoodAppreciationRate`, `medianAreaPrice`, `averageDaysOnMarket`, `pricePerSqFt`, `marketDemand`)
SELECT `l`.`propertyId`, `l`.`estimatedValue`, `l`.`neighborhoodAppreciationRate`, `l`.`medianAreaPrice`, `l`.`averageDaysOnMarket`, `l`.`pricePerSqFt`, `l`.`marketDemand`
FROM `market_data__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `market_data__v2`""")
        db.execSQL("""ALTER TABLE `rent_estimates` RENAME TO `rent_estimates__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `rent_estimates` (`propertyId` TEXT NOT NULL, `estimatedRent` REAL NOT NULL, `rentRangeLow` REAL NOT NULL, `rentRangeHigh` REAL NOT NULL, `rentConfidenceScore` REAL NOT NULL, `grossYield` REAL NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `rent_estimates` (`propertyId`, `estimatedRent`, `rentRangeLow`, `rentRangeHigh`, `rentConfidenceScore`, `grossYield`)
SELECT `l`.`propertyId`, `l`.`estimatedRent`, `l`.`rentRangeLow`, `l`.`rentRangeHigh`, `l`.`rentConfidenceScore`, `l`.`grossYield`
FROM `rent_estimates__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `rent_estimates__v2`""")
        db.execSQL("""ALTER TABLE `tax_records` RENAME TO `tax_records__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `tax_records` (`propertyId` TEXT NOT NULL, `annualTaxAmount` REAL NOT NULL, `assessmentYear` INTEGER NOT NULL, `assessedValue` REAL NOT NULL, `taxDelinquent` INTEGER NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `tax_records` (`propertyId`, `annualTaxAmount`, `assessmentYear`, `assessedValue`, `taxDelinquent`)
SELECT `l`.`propertyId`, `l`.`annualTaxAmount`, `l`.`assessmentYear`, `l`.`assessedValue`, `l`.`taxDelinquent`
FROM `tax_records__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `tax_records__v2`""")
        db.execSQL("""ALTER TABLE `sales_history` RENAME TO `sales_history__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sales_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `date` TEXT NOT NULL, `price` REAL NOT NULL, `event` TEXT NOT NULL, FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `sales_history` (`id`, `propertyId`, `date`, `price`, `event`)
SELECT `l`.`id`, `l`.`propertyId`, `l`.`date`, `l`.`price`, `l`.`event`
FROM `sales_history__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `sales_history__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_sales_history_propertyId_date` ON `sales_history` (`propertyId`, `date`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_comps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `targetPropertyId` TEXT NOT NULL, `compPropertyId` TEXT, `sourceId` TEXT, `compAddress` TEXT NOT NULL, `compPrice` REAL NOT NULL, `compBeds` INTEGER NOT NULL, `compBaths` REAL NOT NULL, `compSqFt` INTEGER NOT NULL, `distanceMiles` REAL NOT NULL, `saleDate` TEXT NOT NULL, `adjustmentAmount` REAL NOT NULL, `compUnit` TEXT NOT NULL, `compCity` TEXT NOT NULL, `compState` TEXT NOT NULL, `compZipCode` TEXT NOT NULL, `compPropertyType` TEXT NOT NULL, `compYearBuilt` INTEGER NOT NULL, `compLotSizeSqFt` INTEGER NOT NULL, `compStatus` TEXT NOT NULL, `compLatitude` REAL NOT NULL, `compLongitude` REAL NOT NULL, `pricePerSqFt` REAL NOT NULL, `adjustedPrice` REAL NOT NULL, `similarityScore` REAL NOT NULL, `adjustmentsJson` TEXT NOT NULL, `isActiveListing` INTEGER NOT NULL, FOREIGN KEY(`targetPropertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`compPropertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`sourceId`) REFERENCES `property_sources`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_property_comps_targetPropertyId_compAddress_saleDate` ON `property_comps` (`targetPropertyId`, `compAddress`, `saleDate`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_comps_targetPropertyId_similarityScore` ON `property_comps` (`targetPropertyId`, `similarityScore`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_comps_compPropertyId` ON `property_comps` (`compPropertyId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_comps_sourceId` ON `property_comps` (`sourceId`)""")
        db.execSQL("""INSERT INTO `property_comps` (`targetPropertyId`, `compPropertyId`, `sourceId`, `compAddress`,
    `compPrice`, `compBeds`, `compBaths`, `compSqFt`, `distanceMiles`, `saleDate`, `adjustmentAmount`,
    `compUnit`, `compCity`, `compState`, `compZipCode`, `compPropertyType`, `compYearBuilt`,
    `compLotSizeSqFt`, `compStatus`, `compLatitude`, `compLongitude`, `pricePerSqFt`, `adjustedPrice`,
    `similarityScore`, `adjustmentsJson`, `isActiveListing`)
SELECT `c`.`targetPropertyId`, NULL, NULL, `c`.`compAddress`, `c`.`compPrice`, `c`.`compBeds`,
    `c`.`compBaths`, `c`.`compSqFt`, `c`.`distanceMiles`, `c`.`saleDate`, `c`.`adjustmentAmount`,
    '', '', '', '', '', 0, 0, 'SOLD', 0.0, 0.0,
    CASE WHEN `c`.`compSqFt` > 0 THEN `c`.`compPrice` / `c`.`compSqFt` ELSE 0.0 END,
    `c`.`compPrice` + `c`.`adjustmentAmount`, 0.0, '', 0
FROM `comparable_properties` AS `c`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `c`.`targetPropertyId`)
GROUP BY `c`.`targetPropertyId`, `c`.`compAddress`, `c`.`saleDate`""")
        db.execSQL("""DROP TABLE `comparable_properties`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_provenance` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `externalId` TEXT NOT NULL, `externalUrl` TEXT NOT NULL, `ingestionMethod` TEXT NOT NULL, `ingestJobId` TEXT, `confidence` REAL NOT NULL, `isPrimaryForProperty` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, `sourceUpdatedAt` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, `rawPayloadHash` TEXT NOT NULL, `rawPayloadRef` TEXT NOT NULL, FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`sourceId`) REFERENCES `property_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`ingestJobId`) REFERENCES `property_import_jobs`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_property_provenance_sourceId_externalId` ON `property_provenance` (`sourceId`, `externalId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_provenance_propertyId_isPrimaryForProperty` ON `property_provenance` (`propertyId`, `isPrimaryForProperty`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_provenance_ingestJobId` ON `property_provenance` (`ingestJobId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_provenance_rawPayloadHash` ON `property_provenance` (`rawPayloadHash`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_provenance_lastSeenAt` ON `property_provenance` (`lastSeenAt`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_enrichments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `enrichmentType` TEXT NOT NULL, `provider` TEXT NOT NULL, `valueNumeric` REAL, `valueText` TEXT NOT NULL, `unit` TEXT NOT NULL, `confidence` REAL NOT NULL, `effectiveAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `isCurrent` INTEGER NOT NULL, `ingestedAt` INTEGER NOT NULL, `payloadHash` TEXT NOT NULL, `rawPayloadRef` TEXT NOT NULL, `provenanceId` INTEGER, `notes` TEXT NOT NULL, FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`provenanceId`) REFERENCES `property_provenance`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_property_enrichments_propertyId_enrichmentType_provider` ON `property_enrichments` (`propertyId`, `enrichmentType`, `provider`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_enrichments_propertyId_enrichmentType` ON `property_enrichments` (`propertyId`, `enrichmentType`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_enrichments_provenanceId` ON `property_enrichments` (`provenanceId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_enrichments_expiresAt` ON `property_enrichments` (`expiresAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_enrichments_isCurrent` ON `property_enrichments` (`isCurrent`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `property_financials` (`propertyId` TEXT NOT NULL, `assessedValue` REAL NOT NULL, `assessmentYear` INTEGER NOT NULL, `annualPropertyTax` REAL NOT NULL, `effectiveTaxRatePct` REAL NOT NULL, `annualInsurance` REAL NOT NULL, `hoaMonthly` REAL NOT NULL, `capitalReserveMonthly` REAL NOT NULL, `maintenanceReserveMonthly` REAL NOT NULL, `monthlyRentEstimate` REAL NOT NULL, `rentEstimateLow` REAL NOT NULL, `rentEstimateHigh` REAL NOT NULL, `rentConfidence` REAL NOT NULL, `marketRentPerSqFt` REAL NOT NULL, `grossRentMultiplier` REAL NOT NULL, `grossYieldPct` REAL NOT NULL, `operatingExpenseRatioPct` REAL NOT NULL, `vacancyRatePct` REAL NOT NULL, `dataSource` TEXT NOT NULL, `provenanceId` INTEGER, `lastUpdatedAt` INTEGER NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`provenanceId`) REFERENCES `property_provenance`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_financials_lastUpdatedAt` ON `property_financials` (`lastUpdatedAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_financials_provenanceId` ON `property_financials` (`provenanceId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_property_financials_assessmentYear` ON `property_financials` (`assessmentYear`)""")
        db.execSQL("""ALTER TABLE `financial_analyses` RENAME TO `financial_analyses__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `financial_analyses` (`propertyId` TEXT NOT NULL, `purchasePrice` REAL NOT NULL, `closingCosts` REAL NOT NULL, `renovationCost` REAL NOT NULL, `monthlyRent` REAL NOT NULL, `otherMonthlyIncome` REAL NOT NULL, `vacancyRatePct` REAL NOT NULL, `propertyTaxAnnual` REAL NOT NULL, `insuranceAnnual` REAL NOT NULL, `maintenancePct` REAL NOT NULL, `managementPct` REAL NOT NULL, `utilitiesMonthly` REAL NOT NULL, `downPaymentPct` REAL NOT NULL, `interestRatePct` REAL NOT NULL, `loanTermYears` INTEGER NOT NULL, `grossRentalIncome` REAL NOT NULL, `effectiveRentalIncome` REAL NOT NULL, `operatingExpensesMonthly` REAL NOT NULL, `noiAnnual` REAL NOT NULL, `monthlyDebtService` REAL NOT NULL, `monthlyCashFlow` REAL NOT NULL, `annualCashFlow` REAL NOT NULL, `capRate` REAL NOT NULL, `cashOnCashReturn` REAL NOT NULL, `dscr` REAL NOT NULL, `breakEvenOccupancyPct` REAL NOT NULL, `totalCashRequired` REAL NOT NULL, `calculatedAt` INTEGER NOT NULL, `isQualified` INTEGER NOT NULL, `dealScore` INTEGER NOT NULL, `qualificationSummary` TEXT NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `financial_analyses` (`propertyId`, `purchasePrice`, `closingCosts`, `renovationCost`, `monthlyRent`, `otherMonthlyIncome`, `vacancyRatePct`, `propertyTaxAnnual`, `insuranceAnnual`, `maintenancePct`, `managementPct`, `utilitiesMonthly`, `downPaymentPct`, `interestRatePct`, `loanTermYears`, `grossRentalIncome`, `effectiveRentalIncome`, `operatingExpensesMonthly`, `noiAnnual`, `monthlyDebtService`, `monthlyCashFlow`, `annualCashFlow`, `capRate`, `cashOnCashReturn`, `dscr`, `breakEvenOccupancyPct`, `totalCashRequired`, `calculatedAt`, `isQualified`, `dealScore`, `qualificationSummary`)
SELECT `l`.`propertyId`, `l`.`purchasePrice`, `l`.`closingCosts`, `l`.`renovationCost`, `l`.`monthlyRent`, `l`.`otherMonthlyIncome`, `l`.`vacancyRatePct`, `l`.`propertyTaxAnnual`, `l`.`insuranceAnnual`, `l`.`maintenancePct`, `l`.`managementPct`, `l`.`utilitiesMonthly`, `l`.`downPaymentPct`, `l`.`interestRatePct`, `l`.`loanTermYears`, `l`.`grossRentalIncome`, `l`.`effectiveRentalIncome`, `l`.`operatingExpensesMonthly`, `l`.`noiAnnual`, `l`.`monthlyDebtService`, `l`.`monthlyCashFlow`, `l`.`annualCashFlow`, `l`.`capRate`, `l`.`cashOnCashReturn`, `l`.`dscr`, `l`.`breakEvenOccupancyPct`, `l`.`totalCashRequired`, `l`.`calculatedAt`, `l`.`isQualified`, `l`.`dealScore`, `l`.`qualificationSummary`
FROM `financial_analyses__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `financial_analyses__v2`""")
        db.execSQL("""ALTER TABLE `financing_scenarios` RENAME TO `financing_scenarios__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `financing_scenarios` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `propertyId` TEXT NOT NULL, `scenarioName` TEXT NOT NULL, `downPaymentPct` REAL NOT NULL, `interestRatePct` REAL NOT NULL, `loanTermYears` INTEGER NOT NULL, `monthlyPayment` REAL NOT NULL, `cashRequired` REAL NOT NULL, `monthlyCashFlow` REAL NOT NULL, `cashOnCash` REAL NOT NULL, `dscr` REAL NOT NULL, FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `financing_scenarios` (`id`, `propertyId`, `scenarioName`, `downPaymentPct`, `interestRatePct`, `loanTermYears`, `monthlyPayment`, `cashRequired`, `monthlyCashFlow`, `cashOnCash`, `dscr`)
SELECT `l`.`id`, `l`.`propertyId`, `l`.`scenarioName`, `l`.`downPaymentPct`, `l`.`interestRatePct`, `l`.`loanTermYears`, `l`.`monthlyPayment`, `l`.`cashRequired`, `l`.`monthlyCashFlow`, `l`.`cashOnCash`, `l`.`dscr`
FROM `financing_scenarios__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `financing_scenarios__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_financing_scenarios_propertyId` ON `financing_scenarios` (`propertyId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_financing_scenarios_propertyId_scenarioName` ON `financing_scenarios` (`propertyId`, `scenarioName`)""")
        db.execSQL("""ALTER TABLE `saved_properties` RENAME TO `saved_properties__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `saved_properties` (`propertyId` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`propertyId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `saved_properties` (`propertyId`, `savedAt`, `notes`, `tag`)
SELECT `l`.`propertyId`, `l`.`savedAt`, `l`.`notes`, `l`.`tag`
FROM `saved_properties__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `saved_properties__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_saved_properties_savedAt` ON `saved_properties` (`savedAt`)""")
        db.execSQL("""ALTER TABLE `saved_deals` RENAME TO `saved_deals__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `saved_deals` (`dealId` TEXT NOT NULL, `propertyId` TEXT NOT NULL, `qualificationReason` TEXT NOT NULL, `targetOfferPrice` REAL NOT NULL, `expectedRoi` REAL NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`dealId`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `saved_deals` (`dealId`, `propertyId`, `qualificationReason`, `targetOfferPrice`, `expectedRoi`, `savedAt`)
SELECT `l`.`dealId`, `l`.`propertyId`, `l`.`qualificationReason`, `l`.`targetOfferPrice`, `l`.`expectedRoi`, `l`.`savedAt`
FROM `saved_deals__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `properties` AS `p` WHERE `p`.`id` = `l`.`propertyId`)""")
        db.execSQL("""DROP TABLE `saved_deals__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_saved_deals_propertyId` ON `saved_deals` (`propertyId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_saved_deals_savedAt` ON `saved_deals` (`savedAt`)""")
        db.execSQL("""ALTER TABLE `ai_conversations` RENAME TO `ai_conversations__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_conversations` (`id` TEXT NOT NULL, `propertyId` TEXT, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`propertyId`) REFERENCES `properties`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("""INSERT INTO `ai_conversations` (`id`, `propertyId`, `title`, `createdAt`, `updatedAt`)
SELECT `l`.`id`, `l`.`propertyId`, `l`.`title`, `l`.`createdAt`, `l`.`updatedAt`
FROM `ai_conversations__v2` AS `l`""")
        db.execSQL("""DROP TABLE `ai_conversations__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_ai_conversations_propertyId` ON `ai_conversations` (`propertyId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_ai_conversations_updatedAt` ON `ai_conversations` (`updatedAt`)""")
        db.execSQL("""ALTER TABLE `ai_messages` RENAME TO `ai_messages__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `ai_conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `ai_messages` (`id`, `conversationId`, `role`, `content`, `timestamp`)
SELECT `l`.`id`, `l`.`conversationId`, `l`.`role`, `l`.`content`, `l`.`timestamp`
FROM `ai_messages__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `ai_conversations` AS `c` WHERE `c`.`id` = `l`.`conversationId`)""")
        db.execSQL("""DROP TABLE `ai_messages__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_ai_messages_conversationId_timestamp` ON `ai_messages` (`conversationId`, `timestamp`)""")
        db.execSQL("""ALTER TABLE `offer_documents` RENAME TO `offer_documents__v2`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `offer_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `offerId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `filePath` TEXT NOT NULL, `fileSizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`offerId`) REFERENCES `offers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""INSERT INTO `offer_documents` (`id`, `offerId`, `fileName`, `filePath`, `fileSizeBytes`, `createdAt`)
SELECT `l`.`id`, `l`.`offerId`, `l`.`fileName`, `l`.`filePath`, `l`.`fileSizeBytes`, `l`.`createdAt`
FROM `offer_documents__v2` AS `l`
WHERE EXISTS (SELECT 1 FROM `offers` AS `o` WHERE `o`.`id` = `l`.`offerId`)""")
        db.execSQL("""DROP TABLE `offer_documents__v2`""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_offer_documents_offerId` ON `offer_documents` (`offerId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_offers_propertyId_createdAt` ON `offers` (`propertyId`, `createdAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_offers_status_createdAt` ON `offers` (`status`, `createdAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_offers_createdAt` ON `offers` (`createdAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_propertyId` ON `automation_jobs` (`propertyId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_currentState` ON `automation_jobs` (`currentState`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_runId` ON `automation_jobs` (`runId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_runs_startTime` ON `automation_runs` (`startTime`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_runs_status_startTime` ON `automation_runs` (`status`, `startTime`)""")
        db.execSQL("""INSERT OR IGNORE INTO `property_sources` (`id`, `name`, `sourceKind`, `adapterKey`, `description`,
    `isEnabled`, `priority`, `requiresAttribution`, `attributionText`, `licenseNotes`,
    `maxRequestsPerMinute`, `refreshIntervalMinutes`, `lastSyncAt`, `lastSyncStatus`, `lastSyncError`,
    `createdAt`, `updatedAt`) VALUES
    ('src-mls-default', 'MLS Feed (demo seed dataset)', 'MLS', 'mls.demo.v1',
     'On-market listings delivered by the bundled MLS adapter.', 1, 10, 1,
     'Listing data courtesy of the MLS.', '', 60, 15, 0, 'NEVER', NULL, 0, 0),
    ('src-wholesale-default', 'Off-Market Wholesale Feed', 'WHOLESALER', 'wholesale.demo.v1',
     'Off-market and distressed inventory from the wholesale adapter.', 1, 40, 0, '', '', 30, 60, 0,
     'NEVER', NULL, 0, 0),
    ('src-county-records-default', 'County Public Records', 'PUBLIC_RECORDS', 'county.records.v1',
     'Assessor / recorder facts (taxes, deeds, liens).', 1, 20, 0, '', 'Public domain data.', 20, 1440,
     0, 'NEVER', NULL, 0, 0),
    ('src-internal-default', 'Internal Seed Dataset', 'INTERNAL', 'internal.seed.v1',
     'Content shipped with the app for offline demos.', 1, 90, 0, '', '', 0, 0, 0, 'NEVER', NULL, 0, 0),
    ('src-user-entered-default', 'Manual Entry', 'USER_ENTERED', 'manual.entry.v1',
     'Properties added by the investor from the Discover screen.', 1, 5, 0, '', '', 0, 0, 0, 'NEVER',
     NULL, 0, 0)""")
        db.execSQL("""UPDATE `properties` SET `primarySourceId` = CASE
    WHEN `sourceType` = 'ON_MARKET' THEN 'src-mls-default'
    WHEN `sourceType` IN ('OFF_MARKET', 'WHOLESALE') THEN 'src-wholesale-default'
    WHEN `sourceType` = 'FORECLOSURE' THEN 'src-county-records-default'
    ELSE 'src-internal-default' END
WHERE `primarySourceId` IS NULL""")
        db.execSQL("""UPDATE `properties` SET `normalizedAddress` = `address` WHERE `normalizedAddress` = ''""")
        db.execSQL("""UPDATE `properties` SET `lastVerifiedAt` = `scannedAt` WHERE `lastVerifiedAt` = 0""")
        db.execSQL("""INSERT INTO `property_provenance` (`propertyId`, `sourceId`, `externalId`, `externalUrl`,
    `ingestionMethod`, `ingestJobId`, `confidence`, `isPrimaryForProperty`, `fetchedAt`,
    `sourceUpdatedAt`, `firstSeenAt`, `lastSeenAt`, `rawPayloadHash`, `rawPayloadRef`)
SELECT `p`.`id`,
    CASE
      WHEN `p`.`sourceType` = 'ON_MARKET' THEN 'src-mls-default'
      WHEN `p`.`sourceType` IN ('OFF_MARKET', 'WHOLESALE') THEN 'src-wholesale-default'
      WHEN `p`.`sourceType` = 'FORECLOSURE' THEN 'src-county-records-default'
      ELSE 'src-internal-default' END,
    `p`.`id`, '', 'SEED', NULL, 1.0, 1, `p`.`scannedAt`, 0, `p`.`scannedAt`, `p`.`scannedAt`, '', ''
FROM `properties` AS `p`""")
        db.execSQL("""INSERT INTO `property_financials` (`propertyId`, `assessedValue`, `assessmentYear`,
    `annualPropertyTax`, `effectiveTaxRatePct`, `annualInsurance`, `hoaMonthly`,
    `capitalReserveMonthly`, `maintenanceReserveMonthly`, `monthlyRentEstimate`, `rentEstimateLow`,
    `rentEstimateHigh`, `rentConfidence`, `marketRentPerSqFt`, `grossRentMultiplier`, `grossYieldPct`,
    `operatingExpenseRatioPct`, `vacancyRatePct`, `dataSource`, `provenanceId`, `lastUpdatedAt`)
SELECT `p`.`id`, COALESCE(`t`.`assessedValue`, 0.0), COALESCE(`t`.`assessmentYear`, 0),
    COALESCE(`t`.`annualTaxAmount`, 0.0),
    CASE WHEN COALESCE(`t`.`assessedValue`, 0.0) > 0 AND COALESCE(`t`.`annualTaxAmount`, 0.0) > 0
         THEN (`t`.`annualTaxAmount` / `t`.`assessedValue`) * 100.0 ELSE 0.0 END,
    0.0, `p`.`hoaMonthly`, 0.0, 0.0,
    COALESCE(`r`.`estimatedRent`, 0.0), COALESCE(`r`.`rentRangeLow`, 0.0),
    COALESCE(`r`.`rentRangeHigh`, 0.0), COALESCE(`r`.`rentConfidenceScore`, 0.0),
    CASE WHEN `p`.`squareFeet` > 0 AND COALESCE(`r`.`estimatedRent`, 0.0) > 0
         THEN `r`.`estimatedRent` / `p`.`squareFeet` ELSE 0.0 END,
    CASE WHEN COALESCE(`r`.`estimatedRent`, 0.0) > 0 AND `p`.`price` > 0
         THEN `p`.`price` / (`r`.`estimatedRent` * 12.0) ELSE 0.0 END,
    COALESCE(`r`.`grossYield`, 0.0), 0.0, 0.0,
    CASE WHEN `t`.`propertyId` IS NOT NULL THEN 'RECORDS' ELSE 'ESTIMATE' END,
    (SELECT `prov`.`id` FROM `property_provenance` AS `prov`
      WHERE `prov`.`propertyId` = `p`.`id` ORDER BY `prov`.`isPrimaryForProperty` DESC LIMIT 1),
    `p`.`scannedAt`
FROM `properties` AS `p`
LEFT JOIN `tax_records` AS `t` ON `t`.`propertyId` = `p`.`id`
LEFT JOIN `rent_estimates` AS `r` ON `r`.`propertyId` = `p`.`id`""")
    }
}

/**
 * v2 -> v3: the durable automation execution model (jobs, executions, logs, rules, runs, state).
 *
 * Everything here is additive: new columns land with the Kotlin-side defaults from
 * [AutomationRuleEntity] / [AutomationRunEntity] / [AutomationStateEntity], and legacy job rows are
 * given a unique idempotency key before the unique index is created. Nothing is dropped or rebuilt,
 * so upgrade paths never lose user data.
 */
private class Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // ── automation_executions ───────────────────────────────────────────────────────────────
        db.execSQL("""CREATE TABLE IF NOT EXISTS `automation_executions` (`idempotencyKey` TEXT NOT NULL, `jobId` TEXT, `runId` INTEGER NOT NULL, `effect` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt` INTEGER NOT NULL, `resultRef` TEXT, `error` TEXT, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`idempotencyKey`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_executions_jobId` ON `automation_executions` (`jobId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_executions_runId` ON `automation_executions` (`runId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_executions_status` ON `automation_executions` (`status`)""")

        // ── automation_jobs: retry, lease and idempotency bookkeeping ───────────────────────────
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `idempotencyKey` TEXT NOT NULL DEFAULT ''""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `failureKind` TEXT""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `nextAttemptAt` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `leaseOwner` TEXT""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `leaseExpiresAt` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `recoveryCount` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `lastRecoveredAt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `startedAt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_jobs` ADD COLUMN `completedAt` INTEGER""")
        // Legacy rows share the '' default, so they get a per-row key before the unique index lands.
        db.execSQL("""UPDATE `automation_jobs` SET `idempotencyKey` = 'legacy:' || `jobId` WHERE `idempotencyKey` = ''""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_automation_jobs_idempotencyKey` ON `automation_jobs` (`idempotencyKey`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_leaseExpiresAt` ON `automation_jobs` (`leaseExpiresAt`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_automation_jobs_nextAttemptAt` ON `automation_jobs` (`nextAttemptAt`)""")

        // ── automation_logs: correlation and state-transition context ───────────────────────────
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `jobId` TEXT""")
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `correlationId` TEXT""")
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `stateBefore` TEXT""")
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `stateAfter` TEXT""")
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `attempt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_logs` ADD COLUMN `durationMs` INTEGER""")

        // ── automation_rules: backoff, recovery and lease policy ────────────────────────────────
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `retryBackoffBaseSeconds` INTEGER NOT NULL DEFAULT 30""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `retryBackoffMaxMinutes` INTEGER NOT NULL DEFAULT 30""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `staleRunTimeoutMinutes` INTEGER NOT NULL DEFAULT 15""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `maxRecoveryAttempts` INTEGER NOT NULL DEFAULT 20""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `autoResumeInterruptedJobs` INTEGER NOT NULL DEFAULT 1""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `maxJobsPerCycle` INTEGER NOT NULL DEFAULT 25""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `jobLeaseTtlMinutes` INTEGER NOT NULL DEFAULT 5""")
        db.execSQL("""ALTER TABLE `automation_rules` ADD COLUMN `cycleLeaseTtlMinutes` INTEGER NOT NULL DEFAULT 3""")

        // ── automation_runs: per-run counters and durable worker metadata ───────────────────────
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `jobsProcessed` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `jobsRecovered` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `jobsFailed` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `jobsBlocked` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `trigger` TEXT NOT NULL DEFAULT 'MANUAL'""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `correlationId` TEXT NOT NULL DEFAULT ''""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `workerRunAttempt` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `failureReason` TEXT""")
        db.execSQL("""ALTER TABLE `automation_runs` ADD COLUMN `heartbeatAt` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""UPDATE `automation_runs` SET `heartbeatAt` = `startTime`""")

        // ── automation_state: kill switch, run lease and recovery bookkeeping ───────────────────
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `killSwitchEngaged` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `killSwitchReason` TEXT""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `killSwitchEngagedAt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `engineStartedAt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `activeRunId` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `cycleLeaseOwner` TEXT""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `cycleLeaseExpiresAt` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `lastRecoveryAt` INTEGER""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `recoveredJobsTotal` INTEGER NOT NULL DEFAULT 0""")
        db.execSQL("""ALTER TABLE `automation_state` ADD COLUMN `lastWorkerEnqueuedAt` INTEGER""")
    }
}

/** All migrations, registered by [com.example.data.local.AppDatabase]. */
object DatabaseMigrations {
    val ALL: Array<Migration> = arrayOf(Migration1To2(), Migration2To3(), Migration3To4())
}
