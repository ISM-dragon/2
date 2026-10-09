package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.*
import com.example.data.local.migration.DatabaseMigrations
import com.example.data.local.migration.LegacyV2Schema
import com.example.data.repository.PropertyImportRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End to end migration test for the full 1 -> 2 -> 3 -> 4 chain.
 *
 * A real v2 database file is built from the [LegacyV2Schema] catalog, seeded with legacy rows -
 * including automation jobs written by the pre-durability engine - and then opened through Room with
 * [DatabaseMigrations.ALL]. Opening performs Room's own schema validation, so this test fails
 * whenever a migration misses a column, index, foreign key or leaves an unexpected table behind -
 * the same failures users would hit on upgrade instead of the destructive fallback that used to be
 * registered on the builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val dbName = "migration-under-test.db"

    @Test
    fun `migrating a v2 database preserves data and adopts the v4 schema`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        createV2Database(context)

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        try {
            val property = runBlocking { database.propertyDao().getPropertyById("legacy-1") }
            assertNotNull("the legacy property was lost by the migration", property)
            property!!

            // v4 identity columns exist and are backfilled; canonicalKey stays NULL on purpose so
            // the dedup reconcile pass (not the migration) decides identity.
            assertTrue("normalizedAddress was not backfilled", property.normalizedAddress.isNotBlank())
            assertNull("migration must leave canonicalKey to the reconcile pass", property.canonicalKey)
            assertEquals("", property.unitNumber)

            // satellites survive the table rebuild
            assertEquals(6200.0, runBlocking { database.propertyDao().getTaxRecord("legacy-1") }!!.annualTaxAmount, 0.001)
            assertEquals(4200.0, runBlocking { database.propertyDao().getRentEstimate("legacy-1") }!!.estimatedRent, 0.001)
            assertEquals(1, runBlocking { database.propertyDao().getImagesListForProperty("legacy-1") }.size)
            assertEquals(1, runBlocking { database.propertyDao().getSalesHistoryListForProperty("legacy-1") }.size)

            // comparable_properties was folded into property_comps (duplicates collapsed)
            val comps = runBlocking { database.propertyDao().getCompsListForProperty("legacy-1") }
            assertEquals(1, comps.size)
            assertFalse(
                "comparable_properties must be dropped after the fold",
                tableExists(database, "comparable_properties")
            )

            // orphaned children are filtered out while copying (no dangling rows)
            assertTrue(
                runBlocking { database.propertyDao().getAllTaxRecordsList() }
                    .none { it.propertyId == "ghost-property" }
            )

            // v4 tables and default sources are created/seeded by the migration
            val sourceIds = runBlocking { database.propertySourceDao().getEnabledSources() }.map { it.id }
            PropertySourceDefaults.ALL.forEach { id ->
                assertTrue("source $id missing after migration", id in sourceIds)
            }
            assertTrue(runBlocking { database.propertySourceDao().getProvenanceForProperty("legacy-1") }.isNotEmpty())
            assertNotNull(runBlocking { database.propertyFinancialDao().getFinancials("legacy-1") })

            // v3 (automation durability) upgraded the legacy job in place: same row, no wipe,
            // and a unique idempotency key so the durable worker can never run it twice.
            val job = runBlocking { database.automationDao().getJobById("legacy-job") }
            assertNotNull("legacy automation job was lost by the v2 -> v3 upgrade", job)
            assertTrue("legacy job has no idempotency key", job!!.idempotencyKey.isNotBlank())
            // The job belongs to the legacy property it was created for, not to itself.
            assertEquals("legacy-1", job.propertyId)

            val rules = runBlocking { database.automationDao().getRules() }
            assertNotNull(rules)
            assertEquals(30, rules!!.retryBackoffBaseSeconds)
            assertTrue("automation rules lease policy missing", rules.jobLeaseTtlMinutes > 0)
            assertTrue("automation_executions table missing", tableExists(database, "automation_executions"))

            // Verify all new v4 tables exist
            val expectedV4Tables = listOf(
                "offer_email_sends", "offer_audit_events", "property_ai_analysis",
                "property_source_links", "source_health", "property_enrichments",
                "property_import_jobs", "property_sources", "property_provenance",
                "property_financials", "property_comps"
            )
            for (table in expectedV4Tables) {
                assertTrue("expected v4 table $table is missing", tableExists(database, table))
            }

            assertEquals(4, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `migrating a v1 database normalizes schema and completes v1 to v4 migration chain`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        createV1Database(context)

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        try {
            val property = runBlocking { database.propertyDao().getPropertyById("v1-prop-1") }
            assertNotNull("the legacy v1 property was lost by the migration", property)
            assertEquals("100 Main St", property!!.address)
            assertEquals(350_000.0, property.price, 0.001)

            val tax = runBlocking { database.propertyDao().getTaxRecord("v1-prop-1") }
            assertNotNull("the legacy v1 tax record was lost", tax)
            assertEquals(4000.0, tax!!.annualTaxAmount, 0.001)

            // Check version 4 reached
            assertEquals(4, database.openHelper.readableDatabase.version)
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `missing migration without fallback throws IllegalStateException loudly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        createV2Database(context)

        // Only register Migration1To2; missing 2->3 and 3->4
        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(DatabaseMigrations.ALL[0])
            .allowMainThreadQueries()
            .build()

        try {
            database.openHelper.readableDatabase
            fail("Expected IllegalStateException due to missing migration path without destructive fallback")
        } catch (e: IllegalStateException) {
            assertTrue("Exception should mention missing migration", e.message?.contains("migration", ignoreCase = true) == true)
        } finally {
            try { database.close() } catch (_: Exception) {}
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `v4 new tables exist and support durable operations and queries`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        try {
            // Property AI analysis
            val aiAnalysis = PropertyAiAnalysisEntity(
                propertyId = "prop-ai-1",
                summary = "Strong investment profile",
                investmentThesis = "High rental yield in emerging market",
                strengthsJson = "[\"Good location\"]",
                weaknessesJson = "[]",
                risksJson = "[]",
                redFlagsJson = "[]",
                recommendedStrategy = "BRRRR",
                recommendedOfferRange = "$300k-$320k",
                questionsForSellerJson = "[]",
                dueDiligenceJson = "[]",
                confidence = 0.92,
                evidenceJson = "{}",
                analyzedAt = 1000L
            )
            database.intelligenceDao().insertAiAnalysis(aiAnalysis)
            val fetchedAi = database.intelligenceDao().getAiAnalysis("prop-ai-1")
            assertNotNull(fetchedAi)
            assertEquals("BRRRR", fetchedAi!!.recommendedStrategy)

            // Property Source Links
            val link = PropertySourceLinkEntity(
                propertyId = "prop-ai-1",
                source = "ZILLOW",
                sourceUrl = "https://example.com/zillow/1",
                listingId = "z-100",
                isPrimary = true,
                lastSyncedAt = 1000L
            )
            database.intelligenceDao().insertSourceLink(link)
            val fetchedLink = database.intelligenceDao().findSourceByUrl("https://example.com/zillow/1")
            assertNotNull(fetchedLink)
            assertEquals("prop-ai-1", fetchedLink!!.propertyId)

            // Source Health
            val health = SourceHealthEntity(
                source = "ZILLOW",
                successCount = 10,
                failureCount = 1,
                successRate = 0.909,
                failureRate = 0.091,
                averageLatencyMs = 250L,
                lastSuccessAt = 1000L,
                lastFailureAt = 900L,
                parserVersion = "v1.0",
                healthStatus = "HEALTHY",
                lastErrorReason = null
            )
            database.intelligenceDao().insertOrUpdateSourceHealth(health)
            val fetchedHealth = database.intelligenceDao().getSourceHealth("ZILLOW")
            assertNotNull(fetchedHealth)
            assertEquals("HEALTHY", fetchedHealth!!.healthStatus)

            // Offer Email Sends
            val send = OfferEmailSendEntity(
                idempotencyKey = "send-offer-1",
                offerId = "offer-test-1",
                status = OfferEmailSendStatus.PENDING,
                attemptCount = 0,
                createdAt = 1000L,
                updatedAt = 1000L
            )
            database.offerDao().insertEmailSendIfAbsent(send)
            val fetchedSend = database.offerDao().getEmailSend("send-offer-1")
            assertNotNull(fetchedSend)
            assertEquals("offer-test-1", fetchedSend!!.offerId)

            // Offer Audit Events
            val event = OfferAuditEventEntity(
                offerId = "offer-test-1",
                idempotencyKey = "send-offer-1",
                eventType = "REGISTERED",
                timestamp = 1000L,
                status = "SUCCESS",
                details = "Registered send intent"
            )
            database.offerDao().insertAuditEvent(event)
            val allEvents = database.offerDao().getAuditTrailForOffer("offer-test-1").first()
            assertEquals(1, allEvents.size)
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `reconcile claims canonical keys and merges duplicate legacy rows`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        // legacy-1 and legacy-2 are the same house entered twice in v2, which v2 allowed.
        createV2Database(context)

        val database = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*DatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()

        try {
            val importer = PropertyImportRepository(
                database = database,
                propertyDao = database.propertyDao(),
                sourceDao = database.propertySourceDao(),
                enrichmentDao = database.propertyEnrichmentDao(),
                financialDao = database.propertyFinancialDao(),
                analysisDao = database.financialDao()
            )

            val summary = runBlocking { importer.reconcileCanonicalKeys() }

            assertEquals(2, summary.scanned)
            assertTrue("no row claimed the canonical key", summary.keyed >= 1)
            assertEquals(1, summary.mergedDuplicates)
            assertEquals(1, runBlocking { database.propertyDao().getPropertiesCount() })

            // the older row survives so offers/conversations keep pointing at a live property
            val survivor = runBlocking { database.propertyDao().getPropertyById("legacy-1") }
            assertNotNull(survivor)
            assertNotNull(survivor!!.canonicalKey)
            assertNull(runBlocking { database.propertyDao().getPropertyById("legacy-2") })

            // the loser's satellites moved to the survivor instead of disappearing
            assertNotNull(runBlocking { database.propertyDao().getTaxRecord("legacy-1") })
            assertTrue(
                runBlocking { database.propertySourceDao().getProvenanceForProperty("legacy-1") }.isNotEmpty()
            )
        } finally {
            database.close()
            context.deleteDatabase(dbName)
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun tableExists(database: AppDatabase, table: String): Boolean {
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { cursor -> return cursor.moveToFirst() }
    }

    private fun createV1Database(context: Context) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            legacy.execSQL(
                "CREATE TABLE `properties` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`address` TEXT NOT NULL, `city` TEXT NOT NULL, `state` TEXT NOT NULL, `zipCode` TEXT NOT NULL, " +
                "`price` REAL NOT NULL, `propertyType` TEXT NOT NULL, `bedrooms` INTEGER NOT NULL, " +
                "`bathrooms` REAL NOT NULL, `squareFeet` INTEGER NOT NULL, `yearBuilt` INTEGER NOT NULL, " +
                "`lotSizeSqFt` INTEGER NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`primaryImageUrl` TEXT NOT NULL, `scannedAt` INTEGER NOT NULL, `isSaved` INTEGER NOT NULL, " +
                "`isSavedDeal` INTEGER NOT NULL, `dealScore` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            legacy.execSQL(
                "INSERT INTO `properties` (`id`, `title`, `address`, `city`, `state`, `zipCode`, `price`, " +
                "`propertyType`, `bedrooms`, `bathrooms`, `squareFeet`, `yearBuilt`, `lotSizeSqFt`, " +
                "`description`, `status`, `primaryImageUrl`, `scannedAt`, `isSaved`, `isSavedDeal`, `dealScore`) " +
                "VALUES ('v1-prop-1', 'Main Home', '100 Main St', 'Austin', 'TX', '78701', 350000.0, " +
                "'Single Family', 3, 2.0, 1500, 2010, 4000, 'v1 desc', 'Active', '', 1000, 0, 0, 50)"
            )
            legacy.execSQL(
                "CREATE TABLE `tax_records` (`propertyId` TEXT NOT NULL, `annualTaxAmount` REAL NOT NULL, " +
                "`assessmentYear` INTEGER NOT NULL, `assessedValue` REAL NOT NULL, `taxDelinquent` INTEGER NOT NULL, " +
                "PRIMARY KEY(`propertyId`))"
            )
            legacy.execSQL(
                "INSERT INTO `tax_records` (`propertyId`, `annualTaxAmount`, `assessmentYear`, `assessedValue`, `taxDelinquent`) " +
                "VALUES ('v1-prop-1', 4000.0, 2024, 320000.0, 0)"
            )
            legacy.version = 1
        } finally {
            legacy.close()
        }
    }

    private fun createV2Database(context: Context) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val legacy = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            LegacyV2Schema.TABLES.forEach { shape ->
                legacy.execSQL(shape.createTableSql)
                shape.indexSql.forEach { legacy.execSQL(it) }
            }

            insertRow(
                legacy, "properties",
                mapOf(
                    "id" to "legacy-1",
                    "sourceType" to "ON_MARKET",
                    "title" to "South Congress Duplex",
                    "address" to "2418 South Congress Avenue",
                    "city" to "Austin",
                    "state" to "TX",
                    "zipCode" to "78704",
                    "latitude" to 30.2415,
                    "longitude" to -97.7551,
                    "price" to 485_000.0,
                    "propertyType" to "Multi-Family",
                    "bedrooms" to 4,
                    "bathrooms" to 3.0,
                    "squareFeet" to 2250,
                    "yearBuilt" to 2017,
                    "lotSizeSqFt" to 6500,
                    "description" to "legacy",
                    "status" to "Active",
                    "primaryImageUrl" to "",
                    "scannedAt" to 1000L,
                    "isSaved" to 0,
                    "isSavedDeal" to 0,
                    "dealScore" to 0
                )
            )
            // Same house entered twice, one day apart.
            insertRow(
                legacy, "properties",
                mapOf(
                    "id" to "legacy-2",
                    "address" to "2418 South Congress Ave",
                    "city" to "Austin",
                    "state" to "TX",
                    "zipCode" to "78704",
                    "price" to 479_000.0,
                    "propertyType" to "Multi-Family",
                    "status" to "Active",
                    "scannedAt" to 2000L
                )
            )
            insertRow(legacy, "property_images", mapOf("propertyId" to "legacy-1", "imageUrl" to "https://example.com/1.jpg", "isPrimary" to 1))
            insertRow(legacy, "market_data", mapOf("propertyId" to "legacy-1", "estimatedValue" to 500_000.0))
            insertRow(legacy, "rent_estimates", mapOf("propertyId" to "legacy-1", "estimatedRent" to 4200.0, "rentRangeLow" to 3900.0, "rentRangeHigh" to 4500.0, "rentConfidenceScore" to 88.0, "grossYield" to 10.4))
            insertRow(legacy, "tax_records", mapOf("propertyId" to "legacy-1", "annualTaxAmount" to 6200.0, "assessmentYear" to 2025, "assessedValue" to 410_000.0, "taxDelinquent" to 0))
            insertRow(legacy, "sales_history", mapOf("propertyId" to "legacy-1", "date" to "2021-05-01", "price" to 380_000.0, "event" to "SOLD"))
            // The exact same comp twice: the v3 fold must collapse them.
            insertRow(legacy, "comparable_properties", mapOf("targetPropertyId" to "legacy-1", "compAddress" to "2402 S Congress Ave", "compPrice" to 470_000.0, "compBeds" to 4, "compBaths" to 3.0, "compSqFt" to 2180, "distanceMiles" to 0.3, "saleDate" to "2025-03-14", "adjustmentAmount" to 0.0))
            insertRow(legacy, "comparable_properties", mapOf("targetPropertyId" to "legacy-1", "compAddress" to "2402 S Congress Ave", "compPrice" to 471_000.0, "compBeds" to 4, "compBaths" to 3.0, "compSqFt" to 2180, "distanceMiles" to 0.3, "saleDate" to "2025-03-14", "adjustmentAmount" to 0.0))
            // An orphan child that v2 happily stored: must not survive the v4 foreign keys.
            insertRow(legacy, "tax_records", mapOf("propertyId" to "ghost-property", "annualTaxAmount" to 1.0))

            // Automation state written before the durable execution model existed.
            insertRow(
                legacy, "automation_jobs",
                mapOf(
                    "jobId" to "legacy-job",
                    "runId" to 1,
                    "propertyId" to "legacy-1",
                    "propertyAddress" to "2418 South Congress Avenue",
                    "currentState" to "DISCOVERED",
                    "lastSuccessfulState" to "DISCOVERED",
                    "attempts" to 0,
                    "maxRetries" to 3,
                    "createdAt" to 1000L,
                    "updatedAt" to 1000L
                )
            )
            insertRow(legacy, "automation_runs", mapOf("id" to 1, "startTime" to 1000L, "endTime" to 2000L, "status" to "COMPLETED", "summary" to "legacy run"))
            insertRow(legacy, "automation_logs", mapOf("id" to 1, "runId" to 1, "timestamp" to 1000L, "level" to "INFO", "tag" to "START", "message" to "legacy"))
            insertRow(legacy, "automation_state", mapOf("id" to 1, "lastActivityTime" to 1000L))
            insertRow(legacy, "automation_rules", mapOf("id" to "default"))

            legacy.version = 2
        } finally {
            legacy.close()
        }
    }

    /**
     * Inserts a row into a legacy table. Columns without an override get a deterministic value for
     * their declared type; overrides for columns the historical table does not have are ignored so
     * the same fixture works for any pre-v3 shape.
     */
    private fun insertRow(db: SQLiteDatabase, table: String, overrides: Map<String, Any?>) {
        val shape = LegacyV2Schema.TABLES.first { it.tableName == table }
        val declaredTypes = Regex("`([A-Za-z_][A-Za-z0-9_]*)`\\s+(TEXT|INTEGER|REAL|BLOB|NUMERIC)")
            .findAll(shape.createTableSql)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val columns = shape.columns.filter { it != "id" || overrides.containsKey("id") }
        val values = columns.map { column ->
            if (overrides.containsKey(column)) literal(overrides[column]) else defaultLiteral(declaredTypes[column])
        }
        db.execSQL(
            "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) VALUES (${values.joinToString()})"
        )
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is Number -> value.toString()
        is Boolean -> if (value) "1" else "0"
        else -> "'" + value.toString().replace("'", "''") + "'"
    }

    private fun defaultLiteral(declaredType: String?): String = when (declaredType) {
        "INTEGER", "REAL", "NUMERIC" -> "0"
        "BLOB" -> "X'00'"
        else -> "'legacy'"
    }
}
