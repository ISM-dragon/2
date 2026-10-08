package com.example.urlintelligence

import com.example.urlintelligence.model.PropertyDraft
import com.example.urlintelligence.model.PropertyField
import com.example.urlintelligence.normalization.NormalizationOutcome
import com.example.urlintelligence.normalization.PropertyNormalizer
import com.example.urlintelligence.provenance.Confidence
import com.example.urlintelligence.provenance.ExtractionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Image URLs are extracted from documents this module does not control and are later fetched by the
 * host application's image loader. They must therefore be screened for the same destinations the
 * fetch boundary refuses, so a hostile listing page cannot walk the app onto a private host.
 */
class IngestionImageGuardTest {

    private val clock = TestClock(1_700_000_000_000L)
    private val normalizer = PropertyNormalizer(clock)

    private fun draft(images: List<String>, primary: String? = null): PropertyDraft =
        PropertyDraft("test", "https://example.test/p/1").apply {
            sourcePropertyId = "1"
            put(PropertyField.ADDRESS_LINE1, "4127 Oak Hollow Dr", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            put(PropertyField.CITY, "Austin", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            put(PropertyField.STATE, "TX", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            put(PropertyField.POSTAL_CODE, "78745", ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            if (images.isNotEmpty()) {
                put(PropertyField.IMAGE_URLS, images, ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            }
            if (primary != null) {
                put(PropertyField.PRIMARY_IMAGE_URL, primary, ExtractionMethod.STRUCTURED_DATA, Confidence.EXACT, "t")
            }
        }

    private fun normalizedImages(images: List<String>, primary: String? = null): List<String> {
        val outcome = normalizer.normalize(draft(images, primary))
        return (outcome as NormalizationOutcome.Success).property.imageUrls
    }

    @Test
    fun `private and local image hosts are dropped`() {
        val hostile = listOf(
            "http://127.0.0.1/probe.jpg",
            "http://localhost:8080/probe.jpg",
            "https://192.168.1.1/status.jpg",
            "https://10.10.0.5/internal.jpg",
            "http://169.254.169.254/latest/meta-data/iam.jpg",
            "https://[::1]/loopback.jpg",
            "https://router.internal/dashboard.jpg",
            "https://nas.home.arpa/photo.jpg",
            "https://metadata.google.internal/computeMetadata.jpg",
            "https://user:unit-test-secret@cdn.example.test/a.jpg",
            "https://cdn.example.test/../../etc/passwd.jpg",
            "file:///data/secret.jpg",
            "data:image/png;base64,QUJDREVGRw==",
            "not-a-url"
        )

        assertTrue(normalizedImages(hostile).isEmpty())
    }

    @Test
    fun `public image hosts are kept`() {
        val kept = normalizedImages(
            listOf(
                "https://photos.zillowstatic.com/fp/sample-front.jpg",
                "https://ssl.cdn-redfin.com/photo/1/sample-front.jpg",
                "https://cdn.example-portal.test/foundry-front.jpg"
            )
        )

        assertEquals(3, kept.size)
    }

    @Test
    fun `a hostile primary image cannot be promoted when every image is private`() {
        val outcome = normalizer.normalize(
            draft(
                images = listOf("https://192.168.1.1/status.jpg"),
                primary = "http://169.254.169.254/latest/meta-data/iam.jpg"
            )
        )
        val property = (outcome as NormalizationOutcome.Success).property

        assertTrue(property.imageUrls.isEmpty())
        assertNull(property.primaryImageUrl)
    }

    @Test
    fun `image url screening is exposed for reuse and stays strict`() {
        assertFalse(normalizer.isPublicImageUrl("https://192.168.0.1/a.jpg"))
        assertFalse(normalizer.isPublicImageUrl("https://localhost/a.jpg"))
        assertFalse(normalizer.isPublicImageUrl("https://gateway.internal/a.jpg"))
        assertFalse(normalizer.isPublicImageUrl("https://intranet/photo.jpg")) // host without a dot
        assertFalse(normalizer.isPublicImageUrl("//cdn.example.test/protocol-relative.jpg"))
        assertFalse(normalizer.isPublicImageUrl("ftp://cdn.example.test/a.jpg"))
        assertFalse(normalizer.isPublicImageUrl("https://cdn.example.test/${"a".repeat(2100)}.jpg"))
        assertTrue(normalizer.isPublicImageUrl("https://cdn.example-portal.test/foundry-front.jpg"))
    }
}
