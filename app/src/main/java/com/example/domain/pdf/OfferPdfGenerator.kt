package com.example.domain.pdf

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.data.local.entity.OfferEntity
import com.example.data.local.entity.PropertyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OfferPdfGenerator {

    suspend fun generateOfferPdf(
        context: Context,
        offer: OfferEntity,
        property: PropertyEntity
    ): File = withContext(Dispatchers.IO) {
        val pdfFile = OfferPdfStorage.createPdfFile(context, offer.id)

        val document = PdfDocument()
        // Track the current open page so the finally block can finish it if an exception
        // occurs between startPage and finishPage.  A null value means no page is currently
        // open (either no page was ever started, or the last page was finished).
        var page: PdfDocument.Page? = null
        try {
            val pageWidth = 595
            val pageHeight = 842 // A4 standard dimensions at 72 DPI
            val left = 40f
            val right = 555f
            val contentWidth = right - left

            val titlePaint = Paint().apply {
                color = Color.rgb(15, 23, 42) // Slate 900
                textSize = 16f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }

            val subtitlePaint = Paint().apply {
                color = Color.rgb(2, 132, 199) // Sky 600
                textSize = 10f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }

            val sectionPaint = Paint().apply {
                color = Color.rgb(30, 41, 59)
                textSize = 11.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }

            val bodyPaint = Paint().apply {
                color = Color.rgb(51, 65, 85) // Slate 700
                textSize = 9.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                isAntiAlias = true
            }

            val boldBodyPaint = Paint().apply {
                color = Color.rgb(15, 23, 42)
                textSize = 9.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }

            val linePaint = Paint().apply {
                color = Color.rgb(203, 213, 225) // Slate 300
                strokeWidth = 1f
            }

            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
            page = document.startPage(pageInfo)
            var canvas = page!!.canvas
            var y = 45f

            fun checkPageBreak(requiredHeight: Float) {
                if (y + requiredHeight > pageHeight - 50f) {
                    document.finishPage(page!!)
                    page = null // Mark as finished so the finally block skips a redundant finishPage.
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = document.startPage(pageInfo)
                    canvas = page!!.canvas
                    y = 45f
                    canvas.drawText("Offer ID: ${offer.id} | Page $pageNumber", right - 140f, 30f, bodyPaint)
                    canvas.drawLine(left, 35f, right, 35f, linePaint)
                }
            }

            // Top Header
            canvas.drawText("REAL ESTATE PURCHASE OFFER & LETTER OF INTENT", left, y, titlePaint)
            y += 16f
            canvas.drawText("CONFIDENTIAL & LEGALLY BINDING PURCHASE AGREEMENT PROPOSAL", left, y, subtitlePaint)
            y += 12f
            canvas.drawLine(left, y, right, y, linePaint)
            y += 18f

            // Metadata Table
            val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
            val dateStr = dateFormat.format(Date(offer.createdAt))
            canvas.drawText("Date: $dateStr", left, y, bodyPaint)
            canvas.drawText("Offer Reference: ${offer.id}", 330f, y, boldBodyPaint)
            y += 14f
            canvas.drawText("Expiration: ${offer.expirationDate}", left, y, bodyPaint)
            canvas.drawText("Status: ${offer.status}", 330f, y, boldBodyPaint)
            y += 20f

            // Section 1: Parties
            checkPageBreak(50f)
            canvas.drawText("1. PARTIES & REPRESENTATION", left, y, sectionPaint)
            y += 14f
            canvas.drawText("Seller / Agent: ${offer.recipientName} (${offer.recipientEmail.ifBlank { "Unspecified Email" }})", left + 10f, y, bodyPaint)
            y += 14f
            canvas.drawText("Buyer Entity: Real Estate AI Principal Investment Trust and/or Assigns", left + 10f, y, bodyPaint)
            y += 20f

            // Section 2: Subject Property
            checkPageBreak(60f)
            canvas.drawText("2. SUBJECT PROPERTY IDENTIFICATION", left, y, sectionPaint)
            y += 14f
            canvas.drawText("Legal / Physical Address: ${property.address}, ${property.city}, ${property.state} ${property.zipCode}", left + 10f, y, boldBodyPaint)
            y += 14f
            canvas.drawText("Property Type: ${property.propertyType} | ${property.bedrooms} Beds, ${property.bathrooms} Baths | ${property.squareFeet} Sq Ft", left + 10f, y, bodyPaint)
            y += 14f
            canvas.drawText("Current List Price: $${String.format("%,.0f", property.price)} USD", left + 10f, y, bodyPaint)
            y += 20f

            // Section 3: Commercial Terms
            checkPageBreak(80f)
            canvas.drawText("3. FINANCIAL & COMMERCIAL PURCHASE TERMS", left, y, sectionPaint)
            y += 14f
            canvas.drawText("Purchase Offer Consideration:", left + 10f, y, boldBodyPaint)
            canvas.drawText("$${String.format("%,.0f", offer.offerPrice)} USD", left + 200f, y, boldBodyPaint)
            y += 14f
            canvas.drawText("Initial Earnest Money Deposit:", left + 10f, y, bodyPaint)
            canvas.drawText("$${String.format("%,.0f", offer.earnestMoney)} USD (to Title within 3 business days)", left + 200f, y, bodyPaint)
            y += 14f
            canvas.drawText("Inspection Due Diligence Period:", left + 10f, y, bodyPaint)
            canvas.drawText("${offer.inspectionPeriodDays} Days from mutual contract execution", left + 200f, y, bodyPaint)
            y += 14f
            canvas.drawText("Closing Timeline:", left + 10f, y, bodyPaint)
            canvas.drawText("${offer.closingPeriodDays} Days post inspection satisfaction", left + 200f, y, bodyPaint)
            y += 20f

            // Section 4: Special Conditions & Contingencies
            checkPageBreak(60f)
            canvas.drawText("4. SPECIAL CONDITIONS & CONTINGENCIES", left, y, sectionPaint)
            y += 14f
            val termsRaw = (offer.terms + "\n" + offer.conditions).split("\n").filter { it.isNotBlank() }
            for (tLine in termsRaw) {
                val wrapped = wrapText(tLine, bodyPaint, contentWidth - 20f)
                for ((idx, wLine) in wrapped.withIndex()) {
                    checkPageBreak(14f)
                    val bullet = if (idx == 0) "• " else "  "
                    canvas.drawText("$bullet$wLine", left + 10f, y, bodyPaint)
                    y += 13f
                }
            }
            y += 10f

            // Section 5: Letter of Intent Statement
            checkPageBreak(60f)
            canvas.drawText("5. PURCHASE LETTER OF INTENT STATEMENT", left, y, sectionPaint)
            y += 14f
            val letterWrapped = wrapText(offer.generatedLetterContent, bodyPaint, contentWidth - 15f)
            for (wLine in letterWrapped) {
                checkPageBreak(14f)
                if (wLine.isBlank()) {
                    y += 6f
                } else {
                    canvas.drawText(wLine, left + 10f, y, bodyPaint)
                    y += 12.5f
                }
            }
            y += 20f

            // Section 6: Signatures
            checkPageBreak(90f)
            canvas.drawLine(left, y, right, y, linePaint)
            y += 22f
            canvas.drawText("BUYER AUTHORIZED SIGNATURE", left, y, sectionPaint)
            canvas.drawText("SELLER ACCEPTANCE & SIGNATURE", 310f, y, sectionPaint)
            y += 35f
            canvas.drawLine(left, y, left + 200f, y, linePaint)
            canvas.drawLine(310f, y, 510f, y, linePaint)
            y += 13f
            canvas.drawText("Authorized Signatory, Real Estate AI Trust", left, y, bodyPaint)
            canvas.drawText("${offer.recipientName}, Seller / Representative", 310f, y, bodyPaint)
            y += 12f
            canvas.drawText("Date: $dateStr", left, y, bodyPaint)
            canvas.drawText("Date: ________________________", 310f, y, bodyPaint)

            document.finishPage(page!!)
            page = null // All content written; mark page as finished.

            FileOutputStream(pdfFile).use { out ->
                document.writeTo(out)
            }
        } catch (e: Exception) {
            // Remove any partial file so a corrupt artifact never survives on disk.
            try { pdfFile.delete() } catch (_: Exception) {}
            throw e
        } finally {
            // Best-effort: if a page was started but never finished (due to an exception),
            // finish it now so the document can be closed cleanly.
            page?.let { openPage ->
                try { document.finishPage(openPage) } catch (_: Exception) { /* already finished or invalid */ }
            }
            document.close()
        }

        pdfFile
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n")
        for (para in paragraphs) {
            if (para.isBlank()) {
                result.add("")
                continue
            }
            val words = para.split(" ")
            var currentLine = StringBuilder()
            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) <= maxWidth) {
                    currentLine = StringBuilder(testLine)
                } else {
                    if (currentLine.isNotEmpty()) {
                        result.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(word)
                }
            }
            if (currentLine.isNotEmpty()) {
                result.add(currentLine.toString())
            }
        }
        return result
    }
}
