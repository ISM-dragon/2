package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.pdf.OfferPdfStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfferPdfStorageSecurityTest {
    @Test
    fun generatedFileNameCannotEscapeOffersDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = OfferPdfStorage.createPdfFile(context, "../../backups/private")
        val offersDirectory = File(context.filesDir, "offers").canonicalFile

        assertEquals(offersDirectory, file.parentFile?.canonicalFile)
        assertTrue(file.name.matches(Regex("Offer_[0-9a-f]{64}\\.pdf")))
    }

    @Test
    fun onlyExistingPdfFilesDirectlyInsideOffersCanBeResolved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offersDirectory = File(context.filesDir, "offers").apply { mkdirs() }
        val validPdf = File(offersDirectory, "valid.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val privateFile = File(context.filesDir, "private.txt").apply { writeText("test data") }
        val traversalPath = File(offersDirectory, "../../private.txt").path

        assertNotNull(OfferPdfStorage.resolveExistingPdf(context, validPdf.path))
        assertNull(OfferPdfStorage.resolveExistingPdf(context, privateFile.path))
        assertNull(OfferPdfStorage.resolveExistingPdf(context, traversalPath))
        assertNull(OfferPdfStorage.resolveExistingPdf(context, null))
    }
}
