package com.example.domain.propertyurl.store

import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * File backed [PropertyImportJobStore]: one JSON document per job, written atomically
 * (`*.tmp` + rename) into `<directory>/jobs/`.
 *
 * Why files instead of Room in this module?
 *  - the layer must stay independent from the Android data stack (it is unit testable on a plain
 *    JVM and reusable server side),
 *  - job records are small, append-mostly documents; a directory of JSON files survives crashes
 *    without migrations and without touching the app's existing Room schema.
 *
 * A Room-backed implementation of the same port can be added later without changing a single call
 * site (see `docs/property-url-intelligence.md` → "Wiring into the Android app").
 */
class FilePropertyImportJobStore(
    directory: File,
    private val maxFiles: Int = 1000,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : PropertyImportJobStore {

    private val jobsDirectory = File(directory, "jobs")
    private val lock = Any()
    private val cache = LinkedHashMap<String, PropertyImportJob>()

    override suspend fun save(job: PropertyImportJob) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            ensureDirectory()
            writeAtomically(fileFor(job.jobId), JobCodec.encode(job))
            cache[job.jobId] = job
            trimIfNeeded()
        }
    }

    override suspend fun findById(jobId: String): PropertyImportJob? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cache[jobId]?.let { return@withContext it }
            val file = fileFor(jobId)
            if (!file.exists()) return@withContext null
            readJob(file)?.also { cache[jobId] = it }
        }
    }

    override suspend fun findLatestByKey(idempotencyKey: String): PropertyImportJob? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadAll().filter { it.idempotencyKey == idempotencyKey }.maxByOrNull { it.updatedAtEpochMillis }
        }
    }

    override suspend fun recent(limit: Int): List<PropertyImportJob> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadAll().sortedByDescending { it.updatedAtEpochMillis }.take(limit)
        }
    }

    override suspend fun deleteOlderThan(epochMillis: Long): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            var removed = 0
            loadAll().filter { it.isTerminal && it.updatedAtEpochMillis < epochMillis }.forEach { job ->
                if (fileFor(job.jobId).delete()) {
                    cache.remove(job.jobId)
                    removed++
                }
            }
            removed
        }
    }

    override suspend fun count(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) { loadAll().size }
    }

    // --- internals --------------------------------------------------------------------------

    /** Job ids are app generated (`job-<uuid>`), but never trust an id coming from storage. */
    private fun fileFor(jobId: String): File {
        val safe = jobId.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(80)
        return File(jobsDirectory, "$safe.json")
    }

    private fun ensureDirectory() {
        if (!jobsDirectory.exists() && !jobsDirectory.mkdirs()) {
            throw IOException("Cannot create job store directory ${jobsDirectory.absolutePath}")
        }
    }

    private fun writeAtomically(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Windows/AV fallback — still better than an in-place partial write.
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun readJob(file: File): PropertyImportJob? = try {
        JobCodec.decode(file.readText())
    } catch (e: IOException) {
        null
    }

    private fun loadAll(): List<PropertyImportJob> {
        ensureDirectory()
        val files = jobsDirectory.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        files.forEach { file ->
            val jobId = file.name.removeSuffix(".json")
            if (!cache.containsKey(jobId)) {
                readJob(file)?.let { cache[it.jobId] = it }
            }
        }
        // Drop cache entries whose file disappeared.
        val existingIds = files.map { it.name.removeSuffix(".json") }.toSet()
        cache.keys.filter { id -> existingIds.none { it == id.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(80) } }
            .forEach { cache.remove(it) }
        return cache.values.toList()
    }

    private fun trimIfNeeded() {
        val files = jobsDirectory.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxFiles) return
        val jobs = loadAll().filter { it.isTerminal }.sortedBy { it.updatedAtEpochMillis }
        jobs.take(files.size - maxFiles).forEach { job ->
            fileFor(job.jobId).delete()
            cache.remove(job.jobId)
        }
    }

    /** Removes everything (tests, "clear import history" action). */
    fun clearAll() = synchronized(lock) {
        jobsDirectory.listFiles()?.forEach { it.delete() }
        cache.clear()
    }

    fun lastWriteTimestamp(): Long = clock()
}
