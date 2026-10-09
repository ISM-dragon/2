package com.example.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: plaintext backup exports must not accumulate without bound. */
class BackupRetentionTest {

    private fun backup(timestamp: Long) = "${BackupRetention.FILE_PREFIX}$timestamp${BackupRetention.FILE_SUFFIX}"

    @Test
    fun `timestamps are read only from backup export names`() {
        assertEquals(123L, BackupRetention.timestampOf(backup(123L)))
        assertNull(BackupRetention.timestampOf("notes.json"))
        assertNull(BackupRetention.timestampOf("real_estate_ai_backup_123.txt"))
        assertNull(BackupRetention.timestampOf("real_estate_ai_backup_.json"))
        assertNull(BackupRetention.timestampOf("real_estate_ai_backup_abc.json"))
    }

    @Test
    fun `the newest exports are kept and older ones are selected for deletion`() {
        val names = (1L..7L).map { backup(it * 1000L) }

        val toDelete = BackupRetention.namesToDelete(names, keep = 5)

        assertEquals(setOf(backup(1000L), backup(2000L)), toDelete.toSet())
        assertEquals(2, toDelete.size)
    }

    @Test
    fun `selection is by timestamp, not by list order`() {
        val names = listOf(backup(5000L), backup(1000L), backup(9000L))

        assertEquals(listOf(backup(1000L)), BackupRetention.namesToDelete(names, keep = 2))
    }

    @Test
    fun `files that are not backup exports are never selected`() {
        val names = listOf(backup(1L), backup(2L), "user_notes.json", "offer.pdf")

        assertTrue(BackupRetention.namesToDelete(names, keep = 1).all { it.startsWith(BackupRetention.FILE_PREFIX) })
        assertEquals(listOf(backup(1L)), BackupRetention.namesToDelete(names, keep = 1))
    }

    @Test
    fun `nothing is deleted while within the retention limit`() {
        val names = listOf(backup(1L), backup(2L))

        assertTrue(BackupRetention.namesToDelete(names).isEmpty())
    }
}
