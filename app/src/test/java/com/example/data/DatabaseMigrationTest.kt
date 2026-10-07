package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.entity.PropertySourceDefaults
import com.example.data.local.migration.DatabaseMigrations
import com.example.data.local.migration.LegacyV2Schema
import com.example.data.repository.PropertyImportRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End to end migration test for 2 -> 3.
 *
 * A real v2 database file is built from the [LegacyV2Schema] catalog, seeded with legacy rows, and
 * then opened through Room with [DatabaseMigrations.ALL]. Opening performs Room's own schema
 * validation, so this test fails whenever the migration misses a column, index, foreign key or
 * leaves an unexpected table behind - the same failures users would hit on upgrade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseMigrationTest {

    private val dbName = "migration-under-test.db"

    @Test
    fun `migrating a v2 database preserves data and adopts the v3 schema`() {
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

            // v3 identity columns exist and are backfilled; canonicalKey stays NULL on purpose so
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

            // v3 tables and default sources are created/seeded by the migration
            val sourceIds = runBlocking { database.propertySourceDao().getEnabledSources() }.map { it.id }
            PropertySourceDefaults.ALL.forEach { id ->
                assertTrue("source $id missing after migration", id in sourceIds)
            }
            assertTrue(runBlocking { database.propertySourceDao().getProvenanceForProperty("legacy-1") }.isNotEmpty())
            assertNotNull(runBlocking { database.propertyFinancialDao().getFinancials("legacy-1") })

            assertEquals(3, database.openHelper.readableDatabase.version)
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
            // An orphan child that v2 happily stored: must not survive the v3 foreign keys.
            insertRow(legacy, "tax_records", mapOf("propertyId" to "ghost-property", "annualTaxAmount" to 1.0))

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
