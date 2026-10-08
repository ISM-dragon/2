package com.example

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard rails for the deterministic Deal Scoring contract:
 *
 *  1. the authoritative engine (`domain/scoring`) and the orchestration contract
 *     (`domain/intelligence/scoring`) stay pure Kotlin: no Android, no network, no data layer,
 *     no AI dependencies;
 *  2. the deal-scoring path has NO dependency on the legacy qualification scoring
 *     (`domain/qualification`) -- qualification remains a separate gate;
 *  3. the deal-score model projection (`DealScoreBreakdown`) stays equally isolated.
 *
 * Source scanning keeps these constraints honest the moment an import is added.
 */
class DealScoringArchitectureGuardTest {

    private val forbiddenInProductionScoring = listOf(
        "com.example.domain.qualification", // obsolete qualification scoring
        "import com.example.domain.ai", // AI/Gemini manager
        "Gemini",
        "import android.",
        "import androidx.",
        "okhttp",
        "retrofit",
        "com.example.data." // Room entities / repositories / DAOs
    )

    private fun appSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("../app/src/main/java"),
            File("app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate the app source root from ${File(".").absolutePath}")
    }

    private fun scoringSources(root: File): List<File> {
        val dirs = listOf(
            File(root, "com/example/domain/scoring"),
            File(root, "com/example/domain/intelligence/scoring")
        )
        val modelProjection = File(root, "com/example/domain/intelligence/model/AiAnalystResult.kt")
        val out = mutableListOf<File>()
        dirs.filter { it.isDirectory }.forEach { dir ->
            dir.walkTopDown().forEach { file -> if (file.isFile) out.add(file) }
        }
        if (modelProjection.isFile) out.add(modelProjection)
        return out
    }

    @Test
    fun scoringContractSourcesExist() {
        val root = appSourceRoot()
        val sources = scoringSources(root)
        assertNotNull("app source root resolved: $root", root)
        assertTrue(
            "Expected scoring contract sources under $root, found ${sources.size}",
            sources.isNotEmpty()
        )
        assertTrue(
            "The authoritative standalone engine must be present",
            sources.any { it.name == "DealScoringEngine.kt" && it.path.contains("domain/scoring") }
        )
        assertTrue(
            "The orchestration contract must be present",
            sources.any { it.name == "DealAnalysisOrchestrator.kt" }
        )
    }

    @Test
    fun scoringPackagesHaveNoForbiddenDependencies() {
        val root = appSourceRoot()
        val sources = scoringSources(root)
        assertTrue("No scoring sources found under $root", sources.isNotEmpty())

        sources.forEach { file ->
            val text = file.readText()
            forbiddenInProductionScoring.forEach { token ->
                assertTrue(
                    "${file.path} must not reference '$token' (pure deterministic contract)",
                    !text.contains(token)
                )
            }
        }
    }

    @Test
    fun facadeDelegatesInsteadOfReimplementingScoreArithmetic() {
        // The legacy facade may only call the contract; it must not build DealInputs itself.
        val root = appSourceRoot()
        val facade = File(root, "com/example/domain/intelligence/scoring/DealScoringEngine.kt")
        assertTrue("Facade source must exist: $facade", facade.isFile)
        val text = facade.readText()
        assertTrue(
            "The legacy facade must delegate to DealAnalysisOrchestrator",
            text.contains("DealAnalysisOrchestrator.toBreakdown") &&
                text.contains("DealAnalysisOrchestrator.score")
        )
        assertTrue(
            "The legacy facade must not build scoring inputs itself",
            !text.contains("DealInput(")
        )
    }
}
