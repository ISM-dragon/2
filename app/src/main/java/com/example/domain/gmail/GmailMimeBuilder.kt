package com.example.domain.gmail

import android.content.Context
import android.util.Base64
import android.util.Patterns
import com.example.data.local.entity.GmailConfigurationEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Builds an RFC 5322 message for Gmail's `raw` field. */
internal object GmailMimeBuilder {
    const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
    private const val MIME_LINE_LENGTH = 76

    fun build(
        config: GmailConfigurationEntity,
        recipientEmail: String,
        recipientName: String,
        subject: String,
        htmlBody: String,
        pdfBytes: ByteArray,
        idempotencyKey: String,
        nowMillis: Long = System.currentTimeMillis()
    ): ByteArray {
        require(isSafeEmail(recipientEmail)) { "Invalid recipient email." }
        require(subject.isNotBlank() && !containsHeaderControls(subject)) { "Invalid email subject." }
        require(recipientName.isBlank() || !containsHeaderControls(recipientName)) { "Invalid recipient name." }
        require(config.senderName.isBlank() || !containsHeaderControls(config.senderName)) { "Invalid sender name." }
        require(config.accountEmail.isBlank() || isSafeEmail(config.accountEmail.trim())) { "Invalid sender email." }
        require(pdfBytes.isNotEmpty()) { "PDF attachment is empty." }
        require(pdfBytes.size.toLong() <= MAX_ATTACHMENT_BYTES) { "PDF attachment is too large." }
        require(idempotencyKey.isNotBlank()) { "Email idempotency key is required." }

        val messageIdHash = sha256Hex(idempotencyKey)
        val boundary = "reai_mixed_${messageIdHash.take(32)}"
        val fullHtmlBody = buildString {
            append(htmlBody)
            if (config.signature.isNotBlank()) {
                append("<br><br>--<br>")
                append(escapeHtml(config.signature).replace("\r\n", "\n").replace("\n", "<br>"))
            }
        }

        val output = ByteArrayOutputStream()
        fun header(value: String) {
            output.write(value.toByteArray(StandardCharsets.US_ASCII))
        }

        val senderEmail = config.accountEmail.trim()
        if (senderEmail.isNotBlank()) {
            header("From: ${formatMailbox(config.senderName, senderEmail)}\r\n")
        }
        header("To: ${formatMailbox(recipientName, recipientEmail.trim())}\r\n")
        header("Subject: ${encodeHeader(subject.trim())}\r\n")
        header("Date: ${formatDate(nowMillis)}\r\n")
        header("Message-ID: <offer-$messageIdHash@reai.invalid>\r\n")
        // Gmail does not promise server-side idempotency. This stable key is for correlation and
        // for our durable local send ledger; the local ledger is the actual duplicate-send guard.
        header("X-Offer-Idempotency-Key: $messageIdHash\r\n")
        header("MIME-Version: 1.0\r\n")
        header("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")

        header("--$boundary\r\n")
        header("Content-Type: text/html; charset=UTF-8\r\n")
        header("Content-Transfer-Encoding: base64\r\n\r\n")
        writeWrappedBase64(output, fullHtmlBody.toByteArray(StandardCharsets.UTF_8))
        header("\r\n--$boundary\r\n")
        header("Content-Type: application/pdf; name=\"offer.pdf\"\r\n")
        header("Content-Disposition: attachment; filename=\"offer.pdf\"\r\n")
        header("Content-Transfer-Encoding: base64\r\n\r\n")
        writeWrappedBase64(output, pdfBytes)
        header("\r\n--$boundary--\r\n")
        return output.toByteArray()
    }

    fun isSafeEmail(email: String): Boolean =
        email.length <= 254 &&
            email.isNotBlank() &&
            !containsHeaderControls(email) &&
            Patterns.EMAIL_ADDRESS.matcher(email).matches()

    /** Only attach a real PDF generated under the app's private offers directory. */
    fun isApprovedOfferPdf(context: Context, file: File): Boolean {
        return try {
            val offersDirectory = File(context.filesDir, "offers").canonicalFile
            val canonicalFile = file.canonicalFile
            val offersPrefix = offersDirectory.path.trimEnd(File.separatorChar) + File.separator
            if (
                !canonicalFile.path.startsWith(offersPrefix) ||
                !canonicalFile.isFile ||
                !canonicalFile.canRead() ||
                canonicalFile.length() < 5L
            ) {
                false
            } else {
                FileInputStream(canonicalFile).use { input ->
                    val signature = ByteArray(5)
                    var offset = 0
                    while (offset < signature.size) {
                        val count = input.read(signature, offset, signature.size - offset)
                        if (count <= 0) return@use false
                        offset += count
                    }
                    String(signature, StandardCharsets.US_ASCII) == "%PDF-"
                }
            }
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun containsHeaderControls(value: String): Boolean = value.any {
        it == '\r' || it == '\n' || Character.isISOControl(it)
    }

    fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when (ch) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> ch.toString()
                }
            )
        }
    }

    private fun formatMailbox(name: String, email: String): String {
        val safeEmail = email.trim()
        require(isSafeEmail(safeEmail)) { "Invalid email header." }
        return if (name.isBlank()) safeEmail else "${encodeHeader(name.trim())}\r\n <$safeEmail>"
    }

    /** Encoded words keep Unicode out of raw headers and prevent display-name/header injection. */
    private fun encodeHeader(value: String): String {
        require(!containsHeaderControls(value)) { "Header contains a control character." }
        val chunks = splitUtf8(value, maxBytes = 36)
        return chunks.joinToString("\r\n ") { bytes ->
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "=?UTF-8?B?$encoded?="
        }
    }

    private fun splitUtf8(value: String, maxBytes: Int): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val bytes = String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8)
            if (current.size() > 0 && current.size() + bytes.size > maxBytes) {
                result += current.toByteArray()
                current = ByteArrayOutputStream()
            }
            current.write(bytes)
            index += Character.charCount(codePoint)
        }
        if (current.size() > 0) result += current.toByteArray()
        return result.ifEmpty { listOf(ByteArray(0)) }
    }

    private fun writeWrappedBase64(output: ByteArrayOutputStream, bytes: ByteArray) {
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        var start = 0
        while (start < encoded.length) {
            val end = (start + MIME_LINE_LENGTH).coerceAtMost(encoded.length)
            output.write(encoded.substring(start, end).toByteArray(StandardCharsets.US_ASCII))
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            start = end
        }
    }

    private fun formatDate(timeMillis: Long): String = SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timeMillis))

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
