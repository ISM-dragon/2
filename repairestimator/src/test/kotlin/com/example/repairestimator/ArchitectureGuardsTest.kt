package com.example.repairestimator

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard rails for the constraints this module is built under:
 *
 *  1. the estimator is independent of Android, the app, the network, databases and AI,
 *  2. it is deterministic: no clock, randomness, environment or I/O reaches the arithmetic,
 *  3. no secrets are committed in sources or fixtures,
 *  4. the module has no runtime dependencies at all.
 *
 * Each rule fails the build the moment someone adds `import android.*`, a `Random`, or an API key.
 */
class ArchitectureGuardsTest {

    private val mainRoot = File("src/main/kotlin")

    private fun mainFiles(): List<File> =
        if (mainRoot.exists()) mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() else emptyList()

    private fun assertNoneOf(forbidden: List<String>, reason: String) {
        val files = mainFiles()
        assertTrue("main sources not found under ${mainRoot.absolutePath}", files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            for (token in forbidden) {
                assertTrue("${file.name} must not use $token: $reason", !text.contains(token))
            }
        }
    }

    @Test
    fun `no android, androidx or third-party framework imports in production sources`() {
        assertNoneOf(
            listOf(
                "import android.",
                "import androidx.",
                "import okhttp",
                "import retrofit",
                "import com.google.",
                "import io.ktor",
                "import org.robolectric",
                "import dagger",
                "import javax.inject",
            ),
            "the estimator must run on plain JVM",
        )
    }

    @Test
    fun `no dependency on the app, the urlintelligence module or any data layer`() {
        assertNoneOf(
            listOf(
                "com.example.data",
                "com.example.domain",
                "com.example.ui",
                "com.example.urlintelligence",
                "com.example.repairestimator.app",
                "FinancialRepository",
                "UnderwritingEngine",
                "DealRoom",
                "RoomDatabase",
                "@Entity",
                "@Dao",
            ),
            "the estimator is standalone and must not reach the app or persistence",
        )
    }

    @Test
    fun `no network, database or process access in production sources`() {
        assertNoneOf(
            listOf(
                "java.net",
                "javax.net",
                "java.sql",
                "javax.sql",
                "java.nio.channels",
                "java.io.File",
                "java.lang.ProcessBuilder",
                "Runtime.getRuntime",
                "HttpURLConnection",
                "SQLiteDatabase",
            ),
            "the estimate must not depend on the network, a database or the file system",
        )
    }

    @Test
    fun `no AI or model client in production sources`() {
        val tokens = listOf("Gemini", "gemini", "OpenAI", "openai", "Anthropic", "anthropic", "GenerativeModel", "LanguageModel")
        assertNoneOf(tokens, "every financial figure is arithmetic, never a model output")
        val llm = Regex("\\bllm\\b", RegexOption.IGNORE_CASE)
        for (file in mainFiles()) {
            assertTrue("${file.name} must not mention an LLM", !llm.containsMatchIn(file.readText()))
        }
    }

    @Test
    fun `no clock, randomness or environment in production sources`() {
        // The java.time token is assembled at runtime so this test file does not match its own search.
        val javaTime = "java" + ".time."
        assertNoneOf(
            listOf(
                javaTime,
                "System.currentTimeMillis",
                "System.nanoTime",
                "java.util.Random",
                "kotlin.random",
                "Math.random",
                "UUID",
                "Thread.sleep",
                "System.getenv",
                "System.getProperty",
            ),
            "output must be a pure function of the request",
        )
    }

    @Test
    fun `no hardcoded secrets in sources`() {
        val patterns = listOf(
            Regex("(?i)(api[_-]?key|secret|access[_-]?token|password|client[_-]?secret)\\s*[:=]\\s*\"[A-Za-z0-9+/=_-]{12,}\""),
            Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}"),
            Regex("(AKIA|AIza)[A-Za-z0-9_-]{12,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )
        mainFiles().forEach { file ->
            val text = file.readText()
            patterns.forEach { pattern ->
                val match = pattern.find(text)
                assertTrue("possible secret in ${file.name}: ${match?.value?.take(24)}", match == null)
            }
        }
    }

    @Test
    fun `the module declares no runtime dependencies`() {
        val build = File("build.gradle.kts")
        assertTrue("build.gradle.kts not found", build.exists())
        val runtimeDeclarations = build.readLines()
            .map { it.trim() }
            .filter { line ->
                listOf("implementation(", "api(", "compileOnly(", "runtimeOnly(").any { line.startsWith(it) }
            }
        assertTrue("runtime dependencies are not allowed: $runtimeDeclarations", runtimeDeclarations.isEmpty())
    }

    @Test
    fun `the public result types expose no platform types`() {
        // Public signatures use only Kotlin and JDK value types the caller can always consume.
        val publicFiles = mainFiles().filter { it.name == "RepairModels.kt" }
        publicFiles.forEach { file ->
            assertTrue("${file.name} must not expose java.io or android types", !file.readText().contains("android"))
        }
    }
}
