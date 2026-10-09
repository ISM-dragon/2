package com.example.domain.propertyurl

import com.example.domain.propertyurl.normalize.ValueGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression: a restored backup is untrusted input. Its primary image URL is later fetched by the image
 * loader, so private, metadata and credentialed destinations must be rejected on restore as they are at
 * ingestion.
 */
class ImageUrlRestoreGuardTest {

    @Test
    fun `private and metadata image destinations are rejected on restore`() {
        val hostile = listOf(
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
            "https://192.168.1.1/admin.jpg",
            "https://10.0.0.5/photo.jpg",
            "https://127.0.0.1/photo.jpg",
            "https://localhost/photo.jpg",
            "https://router.internal/photo.jpg",
            "https://metadata.google.internal/computeMetadata/v1/photo.jpg",
            "https://user:fakePassword@cdn.example.test/photo.jpg",
            "file:///data/data/com.example/files/offers/x.pdf",
            "javascript:alert(1)",
            "//cdn.example.test/protocol-relative.jpg"
        )

        hostile.forEach { url -> assertNull("expected rejection of $url", ValueGuards.imageUrlOrNull(url)) }
    }

    @Test
    fun `missing and blank restored values yield no image`() {
        assertNull(ValueGuards.imageUrlOrNull(null))
        assertNull(ValueGuards.imageUrlOrNull(""))
        assertNull(ValueGuards.imageUrlOrNull("   "))
    }

    @Test
    fun `public image urls are kept and trimmed`() {
        assertEquals(
            "https://photos.zillowstatic.com/fp/sample-front.jpg",
            ValueGuards.imageUrlOrNull("  https://photos.zillowstatic.com/fp/sample-front.jpg  ")
        )
    }
}
