package com.example.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the *wiring* facts of the property-to-offer workflow.
 *
 * A JVM test can prove that two components agree when the test itself builds the object graph; it
 * cannot prove that the shipped composition root builds it that way. These guards close exactly that
 * hole - they read the real `RealEstateAiApp`, the Gradle files and the CI workflows - so a seam the
 * reliability audit depends on cannot be silently disconnected again.
 *
 * The last guard pins the *known gaps* documented in `docs/e2e-reliability-audit.md`. Those are
 * tripwires, not approvals: when a gap is closed the guard fails with an instruction to update the
 * coverage matrix, so a wiring change can never land without the cross-boundary test the audit asks
 * for.
 */
class WorkflowWiringGuardTest {

    private val repoRoot: File by lazy {
        listOf(File("."), File(".."), File("../.."))
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Cannot locate the repository root from ${File(".").absolutePath}")
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        return requireNotNull(file.takeIf { it.isFile }) { "expected $relativePath to exist at ${file.absolutePath}" }.readText()
    }

    private fun productionSources(): List<File> =
        File(repoRoot, "app/src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()

    @Test
    fun `the app wires the durable import queue into the canonical property store`() {
        val app = read("app/src/main/java/com/example/RealEstateAiApp.kt")
        val queueBlock = app
            .substringAfter("propertyImportQueue = PropertyUrlIntelligenceFactory.createQueue(")
            .substringBefore("\n        )")

        assertTrue(
            "The background import queue must persist what it imports. Without `onPropertyImported` a " +
                "resumed job is recorded as imported while no property row exists.",
            queueBlock.contains("onPropertyImported")
        )
        assertTrue(
            "The queue must store through the same bridge the UI import path uses, so both paths share " +
                "the normalization, dedup and provenance rules.",
            queueBlock.contains("propertyUrlImporter.toBundle") && queueBlock.contains("propertyRepository.insertBundle")
        )
    }

    @Test
    fun `every app test job in ci keeps gradle's exit status attached to its command`() {
        // `>` on its own line is a separate shell command that always succeeds: `status=$?` would
        // then report the redirect, not Gradle, and the job would be green whatever the tests do.
        listOf(
            ".github/workflows/ai-analyst-ci.yml",
            ".github/workflows/seller-outreach-ci.yml",
            ".github/workflows/e2e-reliability-ci.yml"
        ).forEach { workflow ->
            val text = read(workflow)
            val lines = text.lines()
            val detachedRedirects = lines.indices.filter { index ->
                val trimmed = lines[index].trim()
                trimmed.startsWith(">\"") &&
                    !lines.subList(0, index).lastOrNull { it.isNotBlank() }.orEmpty().trimEnd().endsWith("\\")
            }
            assertTrue(
                "$workflow detaches a gradle redirection from its command on line(s) " +
                    "${detachedRedirects.map { it + 1 }}; that discards Gradle's exit status.",
                detachedRedirects.isEmpty()
            )
            assertTrue("$workflow must fail the job when gradle fails", text.contains("exit \"\$status\""))
        }
    }

    @Test
    fun `unwired workflow seams stay declared in the audit document`() {
        val audit = read("docs/e2e-reliability-audit.md")
        val appBuild = read("app/build.gradle.kts")

        // GAP-1: the deterministic repair estimator is an island. `rehabCost` in the pipeline stays an
        // explicit 0 (see PropertyUnderwritingFactory), so no repair evidence reaches underwriting.
        assertFalse(
            "GAP-1 is documented as an unwired seam. If :repairestimator is a dependency of :app now, " +
                "update the coverage matrix in docs/e2e-reliability-audit.md and add the cross-boundary " +
                "tests its gap entry asks for.",
            appBuild.contains("project(\":repairestimator\")")
        )
        listOf("GAP-1", "GAP-2", "GAP-3", "GAP-4", "GAP-5").forEach { id ->
            assertTrue("the audit document must keep an entry for $id", audit.contains(id))
        }

        // GAP-3: nothing in production persists an analyst analysis into `property_ai_analysis`.
        val analystWriters = productionSources().filter {
            it.readText().contains("RealEstateAnalystPersistence.toPersistenceValues")
        }
        assertTrue(
            "GAP-3 claims that no production code persists an analyst analysis. If a writer was added, " +
                "update the coverage matrix and replace this tripwire with the end-to-end test it asks for.",
            analystWriters.isEmpty()
        )

        // GAP-4: the canonical deal-score engine has no production caller.
        val scoringCallers = productionSources()
            .filterNot { it.path.contains("${File.separator}scoring${File.separator}") }
            .filter { it.readText().contains("DealAnalysisOrchestrator.score(") }
        assertTrue(
            "GAP-4 claims that deterministic deal scoring is not part of the shipped pipeline. If it is " +
                "wired now, update the coverage matrix and add the persisted-scoring end-to-end test.",
            scoringCallers.isEmpty()
        )
    }
}
