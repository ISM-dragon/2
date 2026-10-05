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
        val pdfDir = File(context.filesDir, "offers").apply { mkdirs() }
        val pdfFile = File(pdfDir, "Offer_${offer.id}.pdf")

        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 at 72 dpi
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(15, 23, 42) // Slate 900
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }

        val subtitlePaint = Paint().apply {
            color = Color.rgb(56, 189, 248) // Sky 400
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }

        val sectionPaint = Paint().apply {
            color = Color.rgb(30, 41, 59)
            textSize = 12f
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
            color = Color.rgb(226, 232, 240) // Slate 200
            strokeWidth = 1f
        }

        var y = 45f
        val left = 40f
        val right = 555f

        // Top Header
        canvas.drawText("REAL ESTATE PURCHASE OFFER & LETTER OF INTENT", left, y, titlePaint)
        y += 18f
        canvas.drawText("CONFIDENTIAL & LEGALLY BINDING PURCHASE AGREEMENT PROPOSAL", left, y, subtitlePaint)
        y += 12f
        canvas.drawLine(left, y, right, y, linePaint)
        y += 20f

        // Metadata table
        val dateFormat = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
        val dateStr = dateFormat.format(Date(offer.createdAt))
        canvas.drawText("Date: $dateStr", left, y, bodyPaint)
        canvas.drawText("Offer ID: ${offer.id}", 340f, y, boldBodyPaint)
        y += 15f
        canvas.drawText("Expiration: ${offer.expirationDate}", left, y, bodyPaint)
        canvas.drawText("Status: ${offer.status}", 340f, y, boldBodyPaint)
        y += 22f

        // Section: Parties
        canvas.drawText("1. PARTIES", left, y, sectionPaint)
        y += 14f
        canvas.drawText("Seller / Agent: ${offer.recipientName} (${offer.recipientEmail.ifBlank { "Unspecified Email" }})", left + 10f, y, bodyPaint)
        y += 14f
        canvas.drawText("Buyer: Real Estate AI Principal Investment Trust and/or Assigns", left + 10f, y, bodyPaint)
        y += 22f

        // Section: Subject Property
        canvas.drawText("2. SUBJECT PROPERTY", left, y, sectionPaint)
        y += 14f
        canvas.drawText("Address: ${property.address}, ${property.city}, ${property.state} ${property.zipCode}", left + 10f, y, boldBodyPaint)
        y += 14f
        canvas.drawText("Property Type: ${property.propertyType} | ${property.bedrooms} Beds, ${property.bathrooms} Baths | ${property.squareFeet} Sq Ft", left + 10f, y, bodyPaint)
        y += 14f
        canvas.drawText("Current List Price: $${String.format("%,.0f", property.price)}", left + 10f, y, bodyPaint)
        y += 22f

        // Section: Commercial Terms
        canvas.drawText("3. FINANCIAL & COMMERCIAL TERMS", left, y, sectionPaint)
        y += 14f
        canvas.drawText("Purchase Offer Price:", left + 10f, y, boldBodyPaint)
        canvas.drawText("$${String.format("%,.0f", offer.offerPrice)} USD", left + 180f, y, boldBodyPaint)
        y += 14f
        canvas.drawText("Earnest Money Deposit:", left + 10f, y, bodyPaint)
        canvas.drawText("$${String.format("%,.0f", offer.earnestMoney)} USD (to Title within 3 business days)", left + 180f, y, bodyPaint)
        y += 14f
        canvas.drawText("Inspection Due Diligence:", left + 10f, y, bodyPaint)
        canvas.drawText("${offer.inspectionPeriodDays} Days from full contract execution", left + 180f, y, bodyPaint)
        y += 14f
        canvas.drawText("Closing Timeline:", left + 10f, y, bodyPaint)
        canvas.drawText("${offer.closingPeriodDays} Days after expiration of inspection", left + 180f, y, bodyPaint)
        y += 22f

        // Section: Terms & Conditions
        canvas.drawText("4. SPECIAL CONDITIONS & CONTINGENCIES", left, y, sectionPaint)
        y += 14f
        val termsLines = (offer.terms + "\n" + offer.conditions).split("\n").filter { it.isNotBlank() }
        for (line in termsLines.take(6)) {
            val safeLine = if (line.length > 90) line.substring(0, 87) + "..." else line
            canvas.drawText("• $safeLine", left + 10f, y, bodyPaint)
            y += 13f
        }
        y += 10f

        // Section: Executive Letter Narrative
        canvas.drawText("5. PURCHASE LETTER OF INTENT STATEMENT", left, y, sectionPaint)
        y += 14f
        val letterLines = offer.generatedLetterContent.split("\n").filter { it.isNotBlank() }
        for (line in letterLines.take(5)) {
            val safeLine = if (line.length > 95) line.substring(0, 92) + "..." else line
            canvas.drawText(safeLine, left + 10f, y, bodyPaint)
            y += 12f
        }
        y += 25f

        // Signatures
        canvas.drawLine(left, y, right, y, linePaint)
        y += 25f
        canvas.drawText("BUYER SIGNATURE", left, y, sectionPaint)
        canvas.drawText("SELLER ACCEPTANCE", 320f, y, sectionPaint)
        y += 35f
        canvas.drawLine(left, y, left + 200f, y, linePaint)
        canvas.drawLine(320f, y, 520f, y, linePaint)
        y += 12f
        canvas.drawText("Authorized Signatory, Real Estate AI", left, y, bodyPaint)
        canvas.drawText("${offer.recipientName}, Seller / Rep", 320f, y, bodyPaint)

        document.finishPage(page)

        FileOutputStream(pdfFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        pdfFile
    }
}
