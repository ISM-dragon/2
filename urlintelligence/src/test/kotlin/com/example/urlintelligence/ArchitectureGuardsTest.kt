package com.example.urlintelligence

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard rails for the constraints this module was created under:
 *
 *  1. no Android or platform dependencies may leak into the URL-intelligence layer,
 *  2. no secrets, keys or credentials may be committed in source or fixtures,
 *  3. the layer stays dependency-light (no scraping/HTML frameworks).
 *
 * These tests are cheap and they fail the build the moment someone adds an
 * `import android.*` or pastes an API key into an adapter.
 */
class ArchitectureGuardsTest {

    private val roots = listOf(
        File("src/main/kotlin"),
        File("src/test/kotlin"),
        File("src/test/resources")
    )

    private fun files(): List<File> {
        val out = mutableListOf<File>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().forEach { file ->
                if (file.isFile) out.add(file)
            }
        }
        return out
    }

    @Test
    fun `no android or platform imports in production sources`() {
        val forbidden = listOf(
            "import android.",
            "import androidx.",
            "import okhttp",
            "import retrofit",
            "import org.robolectric"
        )
        files()
            .filter { it.path.contains("src/main") }
            .forEach { file ->
                val text = file.readText()
                forbidden.forEach { import ->
                    assertTrue("${file.path} must not depend on $import", !text.contains(import))
                }
            }
    }

    @Test
    fun `no java time usage so the module runs on older android runtimes`() {
        files()
            .filter { it.path.endsWith(".kt") }
            .forEach { file ->
                val text = file.readText()
                assertTrue(
                    "${file.path} must not use java.time (requires API 26 / desugaring)",
                    !text.contains("java.time.")
                )
            }
    }

    @Test
    fun `no hardcoded secrets in sources or fixtures`() {
        val patterns = listOf(
            Regex("(?i)(api[_-]?key|secret|access[_-]?token|refresh[_-]?token|password|passwd|client[_-]?secret)\\s*[:=]\\s*\"[A-Za-z0-9+/=_-]{12,}\""),
            Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}"),
            Regex("(?i)(AKIA|AIza)[A-Za-z0-9]{12,}")
        )
        files()
            .filter { it.extension in setOf("kt", "html", "json") }
            .forEach { file ->
                val text = file.readText()
                patterns.forEach { pattern ->
                    val match = pattern.find(text)
                    assertTrue(
                        "possible secret in ${file.path}: ${match?.value?.take(24)}",
                        match == null
                    )
                }
            }
    }

    @Test
    fun `adapters never attach credential headers`() {
        val forbiddenHeaders = com.example.urlintelligence.adapter.SourceFetchRequest.FORBIDDEN_HEADERS
        assertTrue(forbiddenHeaders.contains("authorization"))
        assertTrue(forbiddenHeaders.contains("cookie"))
        assertTrue(forbiddenHeaders.contains("x-api-key"))
        assertTrue(forbiddenHeaders.contains("x-goog-api-key"))
        assertTrue(forbiddenHeaders.contains("x-access-token"))
    }

    @Test
    fun `source descriptors declare their opt in requirements`() {
        com.example.urlintelligence.source.KnownSources.all().forEach { descriptor ->
            if (descriptor.id != com.example.urlintelligence.source.SourceDescriptor.GENERIC_SOURCE_ID) {
                assertTrue(
                    "${descriptor.id} must declare whether it requires an opt-in",
                    descriptor.requiresOptIn || descriptor.tier ==
                        com.example.urlintelligence.source.SourceTier.OFFICIAL_API
                )
            }
        }
    }
}
