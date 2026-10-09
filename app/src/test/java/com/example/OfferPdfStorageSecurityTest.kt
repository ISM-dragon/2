package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.pdf.OfferPdfStorage
import java.io.File
import java.nio.file.Files
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

    /**
     * The PDF viewer entry point hands a temporary read grant to another app
     * (`OffersViewModel.openPdf`). It resolves through [OfferPdfStorage], so the same three shapes
     * that must never be attached must also never be shared: a file outside the offers directory
     * reached through a symlink, a non-PDF file inside it, and a PDF in a nested directory.
     */
    @Test
    fun viewerGrantIsLimitedToRealPdfFilesDirectlyInsideOffers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val offersDirectory = File(context.filesDir, "offers").apply { mkdirs() }
        val outsidePdf = File(context.filesDir, "outside.pdf").apply { writeBytes(PDF_HEADER) }
        val nestedPdf = File(offersDirectory, "nested").let { directory ->
            directory.mkdirs()
            File(directory, "nested.pdf").apply { writeBytes(PDF_HEADER) }
        }
        val notAPdf = File(offersDirectory, "notes.txt").apply { writeText("not a document") }
        val escapingLink = File(offersDirectory, "escape.pdf")

        val linkCreated = try {
            Files.createSymbolicLink(escapingLink.toPath(), outsidePdf.toPath())
            true
        } catch (_: Exception) {
            // Some filesystems refuse symlinks; the other two assertions still hold.
            false
        }

        assertNull("nested PDFs are not shareable", OfferPdfStorage.resolveExistingPdf(context, nestedPdf.path))
        assertNull("non-PDF files are not shareable", OfferPdfStorage.resolveExistingPdf(context, notAPdf.path))
        if (linkCreated) {
            assertNull(
                "a link inside offers/ that resolves outside it is not shareable",
                OfferPdfStorage.resolveExistingPdf(context, escapingLink.path)
            )
        }
        val generated = OfferPdfStorage.createPdfFile(context, "offer-7").apply { writeBytes(PDF_HEADER) }
        assertNotNull("a generated PDF is still shareable", OfferPdfStorage.resolveExistingPdf(context, generated.path))
    }

    private companion object {
        val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 0x31)
    }
}
