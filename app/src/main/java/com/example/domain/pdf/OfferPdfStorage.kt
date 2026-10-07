package com.example.domain.pdf

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Restricts offer-document creation and use to the app-private offers directory. */
internal object OfferPdfStorage {
    fun createPdfFile(context: Context, offerId: String): File {
        val offersDirectory = File(context.filesDir, "offers")
        if (!offersDirectory.exists() && !offersDirectory.mkdirs()) {
            throw IOException("Unable to create offer document directory")
        }
        if (!offersDirectory.isDirectory) {
            throw IOException("Offer document path is not a directory")
        }

        // Never use imported/user-controlled IDs as path segments. The name remains stable for updates.
        val safeId = MessageDigest.getInstance("SHA-256")
            .digest(offerId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        val canonicalFilesDirectory = context.filesDir.canonicalFile
        val canonicalDirectory = offersDirectory.canonicalFile
        if (canonicalDirectory != File(canonicalFilesDirectory, "offers")) {
            throw IOException("Offer document directory resolves outside its approved path")
        }
        return File(canonicalDirectory, "Offer_$safeId.pdf")
    }

    fun resolveExistingPdf(context: Context, path: String?): File? {
        if (path.isNullOrBlank()) return null
        return try {
            val filesDirectory = context.filesDir.canonicalFile
            val offersDirectory = File(context.filesDir, "offers").canonicalFile
            if (offersDirectory != File(filesDirectory, "offers")) return null

            val candidate = File(path).canonicalFile
            candidate.takeIf {
                it.isFile &&
                    it.extension.equals("pdf", ignoreCase = true) &&
                    it.parentFile == offersDirectory
            }
        } catch (_: Exception) {
            null
        }
    }
}
