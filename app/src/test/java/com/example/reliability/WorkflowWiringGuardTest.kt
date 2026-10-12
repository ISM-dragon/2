package com.example.reliability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Source-level and shell-contract guards for the *wiring* facts of the property-to-offer workflow.
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

    private data class ShellResult(val exitCode: Int, val output: String)

    /** Extract shell blocks from this repository's simple GitHub Actions `run:` entries. */
    private fun workflowRunScripts(workflow: String): List<String> {
        val lines = workflow.lines()
        val scripts = mutableListOf<String>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("run:")) {
                index++
                continue
            }

            val runValue = trimmed.removePrefix("run:").trim()
            val runIndent = line.length - trimmed.length
            if (runValue in setOf("|", "|-", "|+", ">", ">-", ">+")) {
                val scriptLines = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val candidate = lines[index]
                    val candidateIndent = candidate.takeWhile { it == ' ' || it == '\t' }.length
                    if (candidate.isNotBlank() && candidateIndent <= runIndent) break
                    scriptLines += candidate
                    index++
                }
                scripts += scriptLines.joinToString("\n")
            } else {
                scripts += runValue
                index++
            }
        }
        return scripts
    }

    private fun isDirectGradleInvocation(line: String): Boolean =
        Regex("""^\s*gradle(?:\s|${'$'})""").containsMatchIn(line)

    private fun isBlankOrComment(line: String): Boolean =
        line.isBlank() || line.trimStart().startsWith("#")

    private fun assertWorkflowUsesGradleGate(workflowPath: String, expectedGateCalls: Int) {
        val workflow = read(workflowPath)
        val scripts = workflowRunScripts(workflow)
        val gateInvocation = Regex("""(?m)^\s*bash\s+\.github/scripts/run-gradle-gate\.sh(?:\s|${'$'})""")
        val gateCalls = scripts.sumOf { script -> gateInvocation.findAll(script).count() }
        assertEquals("$workflowPath must route every Gradle invocation through the shared gate helper", expectedGateCalls, gateCalls)
        scripts.filter { gateInvocation.containsMatchIn(it) }.forEach { script ->
            val lines = script.lines()
            val start = lines.indexOfFirst { gateInvocation.containsMatchIn(it) }
            var end = start
            while (end < lines.lastIndex && lines[end].trimEnd().endsWith("\\")) end++
            val command = lines.subList(start, end + 1).joinToString(" ")
            assertFalse("$workflowPath must not pipe, redirect, or chain a helper's status away", command.any { it in "|;&<>" })
            assertTrue("$workflowPath must leave the helper as the final shell command", lines.drop(end + 1).all(::isBlankOrComment))
            assertTrue("$workflowPath must not conditionally mask the helper result", lines.take(start).all(::isBlankOrComment))
        }
        assertTrue(
            "$workflowPath must not bypass the helper with a raw Gradle command",
            scripts.none { script -> script.lines().any(::isDirectGradleInvocation) }
        )
        assertFalse(
            "$workflowPath must not ignore a required gate with continue-on-error",
            Regex("""(?m)^\s*continue-on-error:\s*true\s*${'$'}""").containsMatchIn(workflow)
        )
    }

    private fun assertInlineGradleStepsPropagateFailure(workflowPath: String) {
        val workflow = read(workflowPath)
        assertFalse(
            "$workflowPath must not ignore a required gate with continue-on-error",
            Regex("""(?m)^\s*continue-on-error:\s*true\s*${'$'}""").containsMatchIn(workflow)
        )
        val scripts = workflowRunScripts(workflow)
        val gradleScripts = scripts.filter { script ->
            script.lines().any(::isDirectGradleInvocation)
        }
        assertTrue("$workflowPath should have inline Gradle steps to verify", gradleScripts.isNotEmpty())

        val statusCapture = Regex("""^\s*status\s*=\s*\$\?\s*${'$'}""")
        val statusExit = Regex("""^\s*exit\s+["']?\${'$'}status["']?\s*${'$'}""")
        gradleScripts.forEachIndexed { stepIndex, script ->
            val lines = script.lines()
            val starts = lines.indices.filter { isDirectGradleInvocation(lines[it]) }
            assertEquals("Each inline Gradle step should have one invocation", 1, starts.size)
            val start = starts.single()

            assertTrue(
                "Inline Gradle step $stepIndex in $workflowPath must disable errexit before capturing a failure",
                lines.take(start).any { it.trim() == "set +e" }
            )

            var end = start
            while (end < lines.lastIndex && lines[end].trimEnd().endsWith("\\")) end++
            val commandLines = lines.subList(start, end + 1)
            assertTrue(
                "Inline Gradle invocation in $workflowPath must capture its own status, not a later command",
                commandLines.none { it.contains('|') }
            )

            val nextStatement = (end + 1 until lines.size).firstOrNull { lineIndex ->
                lines[lineIndex].isNotBlank() && !lines[lineIndex].trimStart().startsWith("#")
            }
            assertTrue("Inline Gradle invocation in $workflowPath must be followed by a status capture", nextStatement != null)
            val statusIndex = requireNotNull(nextStatement)
            assertTrue(
                "Inline Gradle invocation in $workflowPath must capture Gradle's status immediately after the command",
                statusCapture.matches(lines[statusIndex].trim())
            )
            assertTrue(
                "Inline Gradle invocation in $workflowPath must exit with its captured status",
                (statusIndex + 1 until lines.size).any { lineIndex ->
                    statusExit.matches(lines[lineIndex].trim())
                }
            )
        }
    }

    private fun runGateAsCiStep(
        helper: File,
        fakeGradleBin: File,
        runnerTemp: File,
        stepSummary: File,
        gradleExitCode: Int
    ): ShellResult {
        val processBuilder = ProcessBuilder(
            "bash", "-e", "-o", "pipefail", "-c",
            """bash "${'$'}1" gradle-gate-regression :app:testDebugUnitTest""",
            "ci-step",
            helper.absolutePath
        )
        processBuilder.directory(repoRoot)
        processBuilder.redirectErrorStream(true)
        processBuilder.environment().apply {
            this["PATH"] = fakeGradleBin.absolutePath + File.pathSeparator + get("PATH").orEmpty()
            this["RUNNER_TEMP"] = runnerTemp.absolutePath
            this["GITHUB_STEP_SUMMARY"] = stepSummary.absolutePath
            this["FAKE_GRADLE_EXIT"] = gradleExitCode.toString()
        }

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ShellResult(process.waitFor(), output)
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
    fun `CI workflows use a tested Gradle gate or preserve inline command status`() {
        val analystWorkflowPath = ".github/workflows/ai-analyst-ci.yml"
        val analystWorkflow = read(analystWorkflowPath)
        assertWorkflowUsesGradleGate(analystWorkflowPath, expectedGateCalls = 3)
        assertWorkflowUsesGradleGate(".github/workflows/seller-outreach-ci.yml", expectedGateCalls = 1)
        assertInlineGradleStepsPropagateFailure(".github/workflows/e2e-reliability-ci.yml")

        assertTrue(analystWorkflow.contains("name: AI Analyst unit tests"))
        assertTrue(analystWorkflow.contains("name: Full app build and module test gate"))
        val targetedJob = analystWorkflow.substringAfter("  ai-analyst-tests:").substringBefore("\n  full-app-gate:")
        assertTrue(targetedJob.contains("Run targeted AI Analyst test suite"))
        val targetedStep = targetedJob.substringAfter("- name: Run targeted AI Analyst test suite")
            .substringBefore("\n      - name:")
        assertFalse("The targeted test step must remain a required step", Regex("(?m)^\\s*if:").containsMatchIn(targetedStep))

        val fullGate = analystWorkflow.substringAfter("  full-app-gate:")
        assertTrue(fullGate.contains("Run full-app module unit tests (app, urlintelligence, repairestimator)"))
        val fullTestStep = fullGate.substringAfter("- name: Run full-app module unit tests (app, urlintelligence, repairestimator)")
            .substringBefore("\n      - name:")
        assertFalse("The full-app test step must remain a required step", Regex("(?m)^\\s*if:").containsMatchIn(fullTestStep))
        assertTrue(fullGate.contains("--continue"))
        listOf(
            ":app:testDebugUnitTest",
            ":urlintelligence:test",
            ":repairestimator:test",
            ":app:assembleDebug"
        ).forEach { task ->
            assertTrue("The full-app CI gate must expose $task", fullGate.contains(task))
        }
        assertTrue(analystWorkflow.contains("java-version: \"21\""))
        assertTrue(analystWorkflow.contains("gradle-version: \"9.3.1\""))
        assertTrue(analystWorkflow.contains("android-actions/setup-android@v4"))
    }

    @Test
    fun `Gradle gate preserves success and failure status through the CI shell`() {
        val sandbox = Files.createTempDirectory("gradle-gate-regression").toFile()
        try {
            val fakeGradleBin = File(sandbox, "fake-bin").apply { mkdirs() }
            val runnerTemp = File(sandbox, "runner-temp").apply { mkdirs() }
            val stepSummary = File(sandbox, "step-summary.md")
            val fakeGradle = File(fakeGradleBin, "gradle")
            fakeGradle.writeText(
                """#!/usr/bin/env bash
                    |echo "Simulated Gradle invocation: ${'$'}*"
                    |if [ "${'$'}FAKE_GRADLE_EXIT" -eq 0 ]; then
                    |  echo "BUILD SUCCESSFUL (simulated)"
                    |else
                    |  echo "FAILURE: simulated Gradle failure"
                    |fi
                    |exit "${'$'}FAKE_GRADLE_EXIT"
                """.trimMargin()
            )
            assertTrue("The simulated Gradle executable must be runnable", fakeGradle.setExecutable(true))

            val helper = File(repoRoot, ".github/scripts/run-gradle-gate.sh").canonicalFile
            val passing = runGateAsCiStep(helper, fakeGradleBin, runnerTemp, stepSummary, gradleExitCode = 0)
            assertEquals("A successful Gradle invocation must succeed in the calling CI shell", 0, passing.exitCode)
            assertTrue("A successful gate must report success", passing.output.contains("Gradle gate succeeded."))
            assertTrue(
                "tee must retain the passing Gradle output in the gate log",
                File(runnerTemp, "gradle-gate-regression.log").readText().contains("BUILD SUCCESSFUL (simulated)")
            )

            val failing = runGateAsCiStep(helper, fakeGradleBin, runnerTemp, stepSummary, gradleExitCode = 23)
            assertEquals("The CI step must preserve Gradle's non-zero exit status", 23, failing.exitCode)
            assertTrue("The gate must report the failing Gradle status", failing.output.contains("Gradle exit status: 23"))
            assertTrue("The simulated Gradle failure must remain visible", failing.output.contains("simulated Gradle failure"))
        } finally {
            sandbox.deleteRecursively()
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
