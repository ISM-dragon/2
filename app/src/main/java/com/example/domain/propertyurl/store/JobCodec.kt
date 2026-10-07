package com.example.domain.propertyurl.store

import com.example.domain.propertyurl.job.JobStateTransition
import com.example.domain.propertyurl.job.PropertyImportJob
import com.example.domain.propertyurl.job.PropertyImportJobState
import com.example.domain.propertyurl.json.JsonParseResult
import com.example.domain.propertyurl.json.JsonParser
import com.example.domain.propertyurl.json.JsonValue
import com.example.domain.propertyurl.json.JsonWriter
import com.example.domain.propertyurl.model.CanonicalProperty
import com.example.domain.propertyurl.model.CompletenessReport
import com.example.domain.propertyurl.model.FieldValue
import com.example.domain.propertyurl.model.ImportWarning
import com.example.domain.propertyurl.model.PropertyField
import com.example.domain.propertyurl.model.Provenance
import com.example.domain.propertyurl.model.ProvenanceMethod
import com.example.domain.propertyurl.model.SourceFailure
import com.example.domain.propertyurl.model.SourceFailureKind
import com.example.domain.propertyurl.model.SourcedField
import com.example.domain.propertyurl.util.Redaction
import java.util.Locale

/**
 * JSON codec for import jobs.
 *
 * Persisted jobs are what make idempotency survive process death, so the codec is:
 *  - **versioned** (`schemaVersion`), and
 *  - **defensive**: unknown enum values, missing members or malformed numbers degrade to "field
 *    absent" instead of failing the whole restore.
 *
 * Raw pasted input, failure text and URLs are redacted again at this persistence boundary so a
 * future caller cannot accidentally serialize credentials, sensitive query values, or raw errors.
 */
object JobCodec {

    const val SCHEMA_VERSION = 1

    fun encode(job: PropertyImportJob): String = JsonWriter.write(encodeValue(job))

    fun encodeValue(job: PropertyImportJob): JsonValue.Obj = JsonWriter.obj(
        "schemaVersion" to JsonValue.Num(SCHEMA_VERSION.toDouble(), SCHEMA_VERSION.toString()),
        "jobId" to JsonValue.Str(job.jobId),
        "rawInput" to JsonValue.Str(Redaction.url(job.rawInput)?.take(2048).orEmpty()),
        "normalizedUrl" to JsonWriter.str(job.normalizedUrl?.let(Redaction::url)),
        "sourceId" to JsonWriter.str(job.sourceId),
        "adapterId" to JsonWriter.str(job.adapterId),
        "externalListingId" to JsonWriter.str(job.externalListingId),
        "idempotencyKey" to JsonValue.Str(job.idempotencyKey),
        "state" to JsonValue.Str(job.state.name),
        "attempts" to JsonWriter.int(job.attempts),
        "maxAttempts" to JsonWriter.int(job.maxAttempts),
        "createdAt" to JsonWriter.long(job.createdAtEpochMillis),
        "updatedAt" to JsonWriter.long(job.updatedAtEpochMillis),
        "nextAttemptAt" to JsonWriter.long(job.nextAttemptAtEpochMillis),
        "requestId" to JsonValue.Str(job.requestId),
        "usedParsers" to JsonWriter.strArr(job.usedParsers),
        "lastFailure" to job.lastFailure?.let { encodeFailure(it) },
        "failures" to JsonValue.Arr(job.failures.map { encodeFailure(it) }),
        "warnings" to JsonValue.Arr(job.warnings.map { encodeWarning(it) }),
        "canonicalProperty" to job.canonicalProperty?.let { encodeProperty(it) },
        "duplicateOfJobId" to JsonWriter.str(job.duplicateOfJobId),
        "transitions" to JsonValue.Arr(
            job.transitions.map { transition ->
                JsonWriter.obj(
                    "from" to JsonValue.Str(transition.from.name),
                    "to" to JsonValue.Str(transition.to.name),
                    "at" to JsonWriter.long(transition.atEpochMillis),
                    "reasonCode" to JsonValue.Str(transition.reasonCode),
                    "detail" to JsonWriter.str(transition.detail)
                )
            }
        )
    )

    fun decode(json: String): PropertyImportJob? = when (val parsed = JsonParser().parse(json)) {
        is JsonParseResult.Success -> decodeValue(parsed.value)
        is JsonParseResult.Failure -> null
    }

    fun decodeValue(value: JsonValue): PropertyImportJob? {
        val obj = value as? JsonValue.Obj ?: return null
        val jobId = obj.string("jobId") ?: return null
        val idempotencyKey = obj.string("idempotencyKey") ?: return null

        return PropertyImportJob(
            jobId = jobId,
            rawInput = obj.string("rawInput").orEmpty(),
            normalizedUrl = obj.string("normalizedUrl"),
            sourceId = obj.string("sourceId"),
            adapterId = obj.string("adapterId"),
            externalListingId = obj.string("externalListingId"),
            idempotencyKey = idempotencyKey,
            state = enumValueOrDefault(obj.string("state"), PropertyImportJobState.RECEIVED),
            attempts = obj.int("attempts") ?: 0,
            maxAttempts = obj.int("maxAttempts") ?: 3,
            createdAtEpochMillis = obj.long("createdAt") ?: 0L,
            updatedAtEpochMillis = obj.long("updatedAt") ?: 0L,
            nextAttemptAtEpochMillis = obj.long("nextAttemptAt"),
            lastFailure = obj.obj("lastFailure")?.let { decodeFailure(it) },
            failures = obj.array("failures").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeFailure) },
            warnings = obj.array("warnings").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeWarning) },
            canonicalProperty = obj.obj("canonicalProperty")?.let { decodeProperty(it) },
            duplicateOfJobId = obj.string("duplicateOfJobId"),
            usedParsers = obj.array("usedParsers").mapNotNull { it.asString() },
            requestId = obj.string("requestId").orEmpty(),
            transitions = obj.array("transitions").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeTransition) }
        )
    }

    // --- Nested models ----------------------------------------------------------------------

    private fun encodeFailure(failure: SourceFailure): JsonValue.Obj = JsonWriter.obj(
        "kind" to JsonValue.Str(failure.kind.name),
        "message" to JsonValue.Str(Redaction.message(failure.message, maxLength = 400)),
        "httpStatus" to JsonWriter.int(failure.httpStatus),
        "retryAfterSeconds" to JsonWriter.long(failure.retryAfterSeconds),
        "sourceId" to JsonWriter.str(failure.sourceId),
        "adapterId" to JsonWriter.str(failure.adapterId),
        "url" to JsonWriter.str(failure.url?.let(Redaction::url)),
        "causeType" to JsonWriter.str(failure.causeType),
        "occurredAt" to JsonWriter.long(failure.occurredAtEpochMillis),
        "diagnostics" to JsonValue.Obj(
            failure.diagnostics.mapValues { (_, v) -> JsonValue.Str(Redaction.message(v, maxLength = 200)) as JsonValue }
        )
    )

    private fun decodeFailure(obj: JsonValue.Obj): SourceFailure = SourceFailure(
        kind = enumValueOrDefault(obj.string("kind"), SourceFailureKind.UNKNOWN),
        message = obj.string("message").orEmpty(),
        httpStatus = obj.int("httpStatus"),
        retryAfterSeconds = obj.long("retryAfterSeconds"),
        sourceId = obj.string("sourceId"),
        adapterId = obj.string("adapterId"),
        url = obj.string("url"),
        causeType = obj.string("causeType"),
        diagnostics = obj.obj("diagnostics")?.asObject()?.mapValues { (_, v) -> v.asString().orEmpty() } ?: emptyMap(),
        occurredAtEpochMillis = obj.long("occurredAt") ?: 0L
    )

    private fun encodeWarning(warning: ImportWarning): JsonValue.Obj = JsonWriter.obj(
        "code" to JsonValue.Str(warning.code.name),
        "message" to JsonValue.Str(Redaction.message(warning.message, maxLength = 300)),
        "field" to JsonWriter.str(warning.field?.name),
        "sourceId" to JsonWriter.str(warning.sourceId),
        "detail" to JsonWriter.str(warning.detail?.let { Redaction.message(it, maxLength = 300) })
    )

    private fun decodeWarning(obj: JsonValue.Obj): ImportWarning = ImportWarning(
        code = enumValueOrDefault(obj.string("code"), ImportWarning.WarningCode.PARTIAL_PARSE),
        message = obj.string("message").orEmpty(),
        field = obj.string("field")?.let { name ->
            PropertyField.entries.firstOrNull { it.name == name }
        },
        sourceId = obj.string("sourceId"),
        detail = obj.string("detail")
    )

    private fun encodeTransition(transition: JobStateTransition): JsonValue.Obj = JsonWriter.obj(
        "from" to JsonValue.Str(transition.from.name),
        "to" to JsonValue.Str(transition.to.name),
        "at" to JsonWriter.long(transition.atEpochMillis),
        "reasonCode" to JsonValue.Str(transition.reasonCode),
        "detail" to JsonWriter.str(transition.detail)
    )

    private fun decodeTransition(obj: JsonValue.Obj): JobStateTransition? {
        val from = enumValueOrNull<PropertyImportJobState>(obj.string("from")) ?: return null
        val to = enumValueOrNull<PropertyImportJobState>(obj.string("to")) ?: return null
        return JobStateTransition(
            from = from,
            to = to,
            atEpochMillis = obj.long("at") ?: 0L,
            reasonCode = obj.string("reasonCode").orEmpty(),
            detail = obj.string("detail")
        )
    }

    private fun encodeProperty(property: CanonicalProperty): JsonValue.Obj = JsonWriter.obj(
        "canonicalId" to JsonValue.Str(property.canonicalId),
        "primarySourceId" to JsonValue.Str(property.primarySourceId),
        "sourceListingId" to JsonWriter.str(property.sourceListingId),
        "sourceUrl" to JsonValue.Str(Redaction.url(property.sourceUrl).orEmpty()),
        "builtAt" to JsonWriter.long(property.builtAtEpochMillis),
        "fields" to JsonValue.Obj(
            property.fields.entries.associate { (field, sourced) ->
                field.name to (
                    JsonWriter.obj(
                        "field" to JsonValue.Str(field.name),
                        "value" to encodeFieldValue(sourced.value),
                        "provenance" to encodeProvenance(sourced.provenance)
                    ) as JsonValue
                    )
            }
        ),
        "warnings" to JsonValue.Arr(property.warnings.map { encodeWarning(it) }),
        "completeness" to JsonWriter.obj(
            "present" to JsonWriter.strArr(property.completeness.present.map { it.name }.sorted()),
            "missing" to JsonWriter.strArr(property.completeness.missing.map { it.name }.sorted()),
            "missingCritical" to JsonWriter.strArr(property.completeness.missingCritical.map { it.name }.sorted()),
            "missingRecommended" to JsonWriter.strArr(property.completeness.missingRecommended.map { it.name }.sorted()),
            "score" to JsonWriter.num(property.completeness.score)
        )
    )

    private fun decodeProperty(obj: JsonValue.Obj): CanonicalProperty? {
        val canonicalId = obj.string("canonicalId") ?: return null
        val sourceUrl = obj.string("sourceUrl") ?: return null

        val fields = LinkedHashMap<PropertyField, SourcedField>()
        obj.obj("fields")?.asObject()?.forEach { (_, raw) ->
            val entry = raw as? JsonValue.Obj ?: return@forEach
            val fieldName = entry.string("field") ?: return@forEach
            val field = PropertyField.entries.firstOrNull { it.name == fieldName } ?: return@forEach
            val value = entry.obj("value")?.let { decodeFieldValue(it) } ?: return@forEach
            val provenance = entry.obj("provenance")?.let { decodeProvenance(it) } ?: return@forEach
            fields[field] = SourcedField(value, provenance)
        }

        val completenessObj = obj.obj("completeness")
        val completeness = CompletenessReport(
            present = completenessObj?.array("present")?.mapNotNull { name ->
                PropertyField.entries.firstOrNull { it.name == name.asString() }
            }?.toSet() ?: fields.keys.toSet(),
            missing = completenessObj?.array("missing")?.mapNotNull { name ->
                PropertyField.entries.firstOrNull { it.name == name.asString() }
            }?.toSet() ?: CompletenessReport.of(fields).missing,
            missingCritical = completenessObj?.array("missingCritical")?.mapNotNull { name ->
                PropertyField.entries.firstOrNull { it.name == name.asString() }
            }?.toSet() ?: CompletenessReport.of(fields).missingCritical,
            missingRecommended = completenessObj?.array("missingRecommended")?.mapNotNull { name ->
                PropertyField.entries.firstOrNull { it.name == name.asString() }
            }?.toSet() ?: emptySet(),
            score = completenessObj?.double("score") ?: CompletenessReport.of(fields).score
        )

        return CanonicalProperty(
            canonicalId = canonicalId,
            primarySourceId = obj.string("primarySourceId").orEmpty(),
            sourceListingId = obj.string("sourceListingId"),
            sourceUrl = sourceUrl,
            fields = fields,
            completeness = completeness,
            warnings = obj.array("warnings").mapNotNull { (it as? JsonValue.Obj)?.let(::decodeWarning) },
            builtAtEpochMillis = obj.long("builtAt") ?: 0L
        )
    }

    private fun encodeFieldValue(value: FieldValue): JsonValue = when (value) {
        is FieldValue.Text -> JsonWriter.obj("t" to JsonValue.Str("text"), "v" to JsonValue.Str(value.value))
        is FieldValue.Decimal -> JsonWriter.obj("t" to JsonValue.Str("num"), "v" to JsonWriter.num(value.value))
        is FieldValue.WholeNumber -> JsonWriter.obj("t" to JsonValue.Str("int"), "v" to JsonWriter.int(value.value))
        is FieldValue.Flag -> JsonWriter.obj("t" to JsonValue.Str("bool"), "v" to JsonWriter.bool(value.value))
        is FieldValue.TextList -> JsonWriter.obj("t" to JsonValue.Str("list"), "v" to JsonWriter.strArr(value.value))
        is FieldValue.Timestamp -> JsonWriter.obj("t" to JsonValue.Str("ts"), "v" to JsonWriter.long(value.epochMillis))
        is FieldValue.GeoPoint -> JsonWriter.obj(
            "t" to JsonValue.Str("geo"),
            "lat" to JsonWriter.num(value.latitude),
            "lon" to JsonWriter.num(value.longitude)
        )
    }

    private fun decodeFieldValue(obj: JsonValue.Obj): FieldValue? = when (obj.string("t")) {
        "text" -> obj.string("v")?.let { FieldValue.Text(it) }
        "num" -> obj.double("v")?.let { FieldValue.Decimal(it) }
        "int" -> obj.int("v")?.let { FieldValue.WholeNumber(it) }
        "bool" -> obj.entries["v"]?.asBool()?.let { FieldValue.Flag(it) }
        "list" -> obj.array("v").mapNotNull { it.asString() }.let { FieldValue.TextList(it) }
        "ts" -> obj.long("v")?.let { FieldValue.Timestamp(it) }
        "geo" -> {
            val lat = obj.double("lat")
            val lon = obj.double("lon")
            if (lat != null && lon != null) FieldValue.GeoPoint(lat, lon) else null
        }
        else -> null
    }

    private fun encodeProvenance(provenance: Provenance): JsonValue.Obj = JsonWriter.obj(
        "sourceId" to JsonValue.Str(provenance.sourceId),
        "method" to JsonValue.Str(provenance.method.name),
        "confidence" to JsonWriter.num(provenance.confidence),
        "extractedAt" to JsonWriter.long(provenance.extractedAtEpochMillis),
        "adapterId" to JsonWriter.str(provenance.adapterId),
        "adapterVersion" to JsonValue.Str(provenance.adapterVersion),
        "sourceUrl" to JsonWriter.str(provenance.sourceUrl?.let(Redaction::url)),
        "rawPath" to JsonWriter.str(provenance.rawPath),
        "note" to JsonWriter.str(provenance.note)
    )

    private fun decodeProvenance(obj: JsonValue.Obj): Provenance = Provenance(
        sourceId = obj.string("sourceId").orEmpty(),
        method = enumValueOrDefault(obj.string("method"), ProvenanceMethod.DERIVED),
        confidence = (obj.double("confidence") ?: 0.5).coerceIn(0.0, 1.0),
        extractedAtEpochMillis = obj.long("extractedAt") ?: 0L,
        adapterId = obj.string("adapterId"),
        adapterVersion = obj.string("adapterVersion") ?: Provenance.UNKNOWN_VERSION,
        sourceUrl = obj.string("sourceUrl"),
        rawPath = obj.string("rawPath"),
        note = obj.string("note")
    )

    // --- Small helpers ----------------------------------------------------------------------

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String?): T? {
        if (name.isNullOrBlank()) return null
        val upper = name.uppercase(Locale.US)
        return enumValues<T>().firstOrNull { it.name == upper || it.name.equals(name, ignoreCase = true) }
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T {
        val parsed: T? = enumValueOrNull<T>(name)
        return parsed ?: default
    }

    private fun JsonValue.Obj.string(key: String): String? = entries[key]?.asString()?.takeIf { it.isNotEmpty() }

    private fun JsonValue.Obj.int(key: String): Int? = entries[key]?.asInt()

    private fun JsonValue.Obj.long(key: String): Long? = entries[key]?.asNumber()?.toLong()

    private fun JsonValue.Obj.double(key: String): Double? = entries[key]?.asNumber()

    private fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = entries[key] as? JsonValue.Obj

    private fun JsonValue.Obj.array(key: String): List<JsonValue> = entries[key]?.asArray() ?: emptyList()
}
