package com.example.domain.crm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the two promises this branch makes about *shape* rather than behaviour:
 *
 *  1. the CRM domain is plain Kotlin — no Android, no Room, no app data/UI layer — so wiring it up
 *     later is a mapping exercise, not a rewrite;
 *  2. the pipeline statuses and the ladder are exactly the ones the business asked for, so a
 *     well-meaning refactor cannot silently drop a stage.
 *
 * The source scan is deliberately text-based: a compile-time check cannot see an import that *would*
 * be added later, and this test is meant to fail the moment one appears.
 */
class LeadCrmIsolationTest {

    private val forbiddenImports = listOf(
        "android.",             // no framework dependency
        "androidx.",            // no Room/Compose/Lifecycle dependency
        "kotlinx.coroutines",   // the domain is suspend-free; the service owns the coroutine boundary
        "com.example.data.",    // no reach into the Room data layer
        "com.example.ui.",      // no reach into the UI layer
        "java.time."            // Android API 24 has no java.time, and this module has no desugaring
    )

    /**
     * Locates `.../domain/crm` main sources from wherever the test happens to run: Gradle unit tests
     * run with the module directory as their working directory, while a plain JVM run may start at the
     * repository root, in a sibling checkout, or deeper.
     */
    private fun crmSourceDir(): File {
        val suffix = "src/main/java/com/example/domain/crm"
        val anchors = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .take(4)
            .toList()
        fun probe(dir: File): File? = listOf(File(dir, "app/$suffix"), File(dir, suffix)).firstOrNull { it.isDirectory }
        anchors.forEach { anchor -> probe(anchor)?.let { return it } }
        // One and two levels below each anchor (a workspace holding the checkout under a subdirectory).
        anchors.forEach { anchor ->
            anchor.listFiles { file -> file.isDirectory }?.forEach { child ->
                probe(child)?.let { return it }
                child.listFiles { file -> file.isDirectory }?.forEach { grandchild ->
                    probe(grandchild)?.let { return it }
                }
            }
        }
        error("could not locate the CRM sources ($suffix) from ${System.getProperty("user.dir")}")
    }

    private fun mainSources(): List<File> =
        crmSourceDir().listFiles { file -> file.isFile && file.name.endsWith(".kt") }?.toList().orEmpty()

    @Test
    fun `the CRM domain is plain Kotlin with no framework imports`() {
        val sources = mainSources()
        assertTrue("the CRM sources must be found for this guard to mean anything", sources.size >= 20)

        val offenders = sources.mapNotNull { file ->
            val imports = file.readLines()
                .filter { it.trimStart().startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
            val bad = imports.filter { imported -> forbiddenImports.any { imported.startsWith(it) } }
            if (bad.isEmpty()) null else "${file.name} imports $bad"
        }
        assertTrue("the CRM domain must stay framework-free, but: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the CRM sources carry no Room annotations or database wiring`() {
        val forbidden = listOf("@Entity", "@Dao", "@Database", "AppDatabase", "RoomDatabase")
        val offenders = mainSources().mapNotNull { file ->
            val text = file.readText()
            val hits = forbidden.filter { text.contains(it) }
            if (hits.isEmpty()) null else "${file.name} mentions $hits"
        }
        assertTrue("this branch must not register or depend on Room entities: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the pipeline statuses are exactly the business ladder`() {
        assertEquals(
            listOf(
                LeadPipelineStatus.NEW,
                LeadPipelineStatus.CONTACTED,
                LeadPipelineStatus.RESPONDED,
                LeadPipelineStatus.NEGOTIATING,
                LeadPipelineStatus.OFFER_SENT,
                LeadPipelineStatus.UNDER_CONTRACT,
                LeadPipelineStatus.DUE_DILIGENCE,
                LeadPipelineStatus.CLOSED,
                LeadPipelineStatus.LOST
            ),
            LeadPipelineStatus.entries.toList()
        )
        assertEquals(LeadPipelineStatus.PIPELINE_ORDER.sorted(), LeadPipelineStatus.entries.sorted())
        assertEquals(
            LeadPipelineStatus.OPEN_STATUSES + LeadPipelineStatus.TERMINAL_STATUSES,
            LeadPipelineStatus.entries.toSet()
        )
        assertTrue(LeadPipelineStatus.LOST != LeadPipelineStatus.CLOSED)
    }

    @Test
    fun `the qualification ladder and the policies are versioned`() {
        assertEquals(
            listOf(
                LeadQualificationState.NOT_ASSESSED,
                LeadQualificationState.DISQUALIFIED,
                LeadQualificationState.UNQUALIFIED,
                LeadQualificationState.NURTURE,
                LeadQualificationState.WARM,
                LeadQualificationState.QUALIFIED
            ),
            LeadQualificationState.entries.toList()
        )
        assertEquals("wholesale-lead-qualification-v1", LeadQualificationPolicy.DEFAULT.version)
        assertEquals("wholesale-lead-transition-v1", LeadTransitionPolicy.DEFAULT.version)
        assertEquals("wholesale-lead-priority-v1", LeadPriorityPolicy.DEFAULT.version)
        assertEquals("motivation-v1", MotivationScoringPolicy.DEFAULT.version)
        assertEquals("condition-v1", ConditionClassificationPolicy.DEFAULT.version)
        // The scored weights must add up to 100, or the thresholds mean nothing.
        assertEquals(100, LeadQualificationPolicy.DEFAULT.weights.values.sum())
        assertEquals(100, LeadPriorityPolicy.DEFAULT.weights.values.sum())
        assertFalse(LeadQualificationPolicy.DEFAULT.weights.values.any { it <= 0 })
    }

    @Test
    fun `the CRM test package is self-contained`() {
        // The fixtures are shared by every CRM suite: a change that breaks them must be visible here
        // rather than as a mysterious failure in an unrelated class.
        val fixture = CrmFixtures.crmLead(status = LeadPipelineStatus.NEW)
        assertEquals(CrmFixtures.LEAD_ID, fixture.id)
        assertEquals(CrmFixtures.SELLER_ID, fixture.primarySeller!!.id)
        assertEquals(CrmFixtures.PROPERTY_ID, fixture.subjectPropertyLink!!.propertyId)
        assertTrue(CrmFixtures.T2 > CrmFixtures.T1)
    }
}
