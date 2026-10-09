package com.example.data.repository

/**
 * Retention policy for plaintext backup exports kept in app-private storage.
 *
 * Exports contain offers, email send history and property data, and nothing else removes them. The
 * newest [DEFAULT_KEEP] exports are retained and older ones are deleted when a new export is written.
 */
object BackupRetention {

    const val FILE_PREFIX: String = "real_estate_ai_backup_"
    const val FILE_SUFFIX: String = ".json"
    const val DEFAULT_KEEP: Int = 5

    /** Epoch millis encoded in a backup file name, or null when the name is not a backup export. */
    fun timestampOf(fileName: String): Long? {
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
        if (fileName.length <= FILE_PREFIX.length + FILE_SUFFIX.length) return null
        return fileName.substring(FILE_PREFIX.length, fileName.length - FILE_SUFFIX.length).toLongOrNull()
    }

    /** Names of backup exports that fall outside the newest [keep] entries. Other files are ignored. */
    fun namesToDelete(fileNames: List<String>, keep: Int = DEFAULT_KEEP): List<String> {
        require(keep >= 0) { "keep must not be negative" }
        return fileNames
            .mapNotNull { name -> timestampOf(name)?.let { name to it } }
            .sortedByDescending { (_, timestamp) -> timestamp }
            .drop(keep)
            .map { (name, _) -> name }
    }
}
