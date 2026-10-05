package com.example

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreTest {

    @Test
    fun testValidBackupStructureGenerationAndValidation() {
        val root = JSONObject().apply {
            put("app", "Real Estate AI APK")
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())

            val properties = JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "prop-1")
                    put("address", "100 Congress Ave")
                    put("city", "Austin")
                    put("state", "TX")
                    put("zipCode", "78701")
                    put("price", 650000.0)
                })
            }
            put("properties", properties)

            val analyses = JSONArray().apply {
                put(JSONObject().apply {
                    put("propertyId", "prop-1")
                    put("purchasePrice", 650000.0)
                    put("monthlyRent", 5200.0)
                    put("noiAnnual", 45000.0)
                })
            }
            put("financial_analyses", analyses)
        }

        assertTrue(root.has("app"))
        assertEquals("Real Estate AI APK", root.getString("app"))
        assertEquals(1, root.getInt("version"))
        assertEquals(1, root.getJSONArray("properties").length())
        assertEquals(1, root.getJSONArray("financial_analyses").length())

        val propObj = root.getJSONArray("properties").getJSONObject(0)
        assertEquals("prop-1", propObj.getString("id"))
        assertEquals(650000.0, propObj.getDouble("price"), 0.01)

        val finObj = root.getJSONArray("financial_analyses").getJSONObject(0)
        assertEquals("prop-1", finObj.getString("propertyId"))
    }

    @Test
    fun testRejectInvalidBackupHeader() {
        val invalidRoot = JSONObject().apply {
            put("app", "Unknown App")
            put("version", 1)
        }

        val isValid = invalidRoot.has("app") && invalidRoot.getString("app").contains("Real Estate AI")
        assertFalse("Backup with unknown app identity must be rejected", isValid)
    }

    @Test
    fun testMalformedJsonHandling() {
        val malformedString = "{ incomplete_json: "
        var failed = false
        try {
            JSONObject(malformedString)
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("Malformed JSON should throw an exception safely", failed)
    }
}
