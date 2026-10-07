package com.example.domain.propertyurl.source

import com.example.domain.propertyurl.url.PropertyUrl

/**
 * Maps a validated URL to a source definition.
 *
 * Evidence hierarchy (strongest first):
 *  1. exact registrable domain match
 *  2. sub-domain match (`www.`, regional or vanity sub-domains)
 *  3. alias host match (mirror domains, vanity short links)
 *  4. source path rule match (e.g. `/homedetails/<id>_zpid`)
 *  5. source query parameter (e.g. `?zpid=`)
 *  6. generic fallback definition for unknown hosts
 *
 * Detection never fetches anything and never throws; unknown hosts degrade to the generic source
 * with a low confidence so the pipeline can decide whether to continue.
 */
class SourceDetector(private val registry: SourceRegistry) {

    fun detect(url: PropertyUrl): SourceDetection {
        val host = url.host
        val path = url.path
        val query = url.query

        val scored = ArrayList<ScoredCandidate>()

        registry.candidatesForHost(host).forEach { definition ->
            val evidence = ArrayList<String>()
            var confidence = 0.0
            var signal = DetectionSignal.NONE

            val exactDomain = definition.domains.any { it.equals(host, ignoreCase = true) }
            if (exactDomain) {
                confidence = 0.98
                signal = DetectionSignal.EXACT_HOST
                evidence.add("host matches ${host}")
            } else {
                confidence = 0.94
                signal = DetectionSignal.SUBDOMAIN_HOST
                evidence.add("sub-domain of ${definition.domains.first()}")
            }

            val idMatch = definition.extractListingId(path, query)
            if (idMatch != null) {
                val (id, origin) = idMatch
                evidence.add("listing id '$id' from $origin")
                if (signal == DetectionSignal.SUBDOMAIN_HOST || signal == DetectionSignal.EXACT_HOST) {
                    confidence = (confidence + 0.01).coerceAtMost(0.99)
                } else {
                    signal = DetectionSignal.PATH_RULE
                    confidence = 0.9
                }
            }

            if (signal == DetectionSignal.NONE) {
                val ruleMatch = definition.pathRules.firstOrNull { it.matches(path) != null }
                if (ruleMatch != null) {
                    signal = DetectionSignal.PATH_RULE
                    confidence = ruleMatch.confidence
                    evidence.add("path rule '${ruleMatch.name}' matched")
                } else if (definition.queryIdNames.any { query.containsKey(it.lowercase()) }) {
                    signal = DetectionSignal.QUERY_PARAMETER
                    confidence = 0.7
                    evidence.add("query parameter identifies the source")
                }
            }

            scored.add(ScoredCandidate(definition, confidence, signal, evidence))
        }

        if (scored.isEmpty()) {
            registry.candidatesByAlias(host)
                .forEach { definition ->
                    scored.add(
                        ScoredCandidate(
                            definition = definition,
                            confidence = 0.75,
                            signal = DetectionSignal.ALIAS_MATCH,
                            evidence = listOf("host alias matches ${definition.displayName}")
                        )
                    )
                }
        }

        if (scored.isEmpty()) {
            val generic = registry.genericFallback()
                ?: return SourceDetection.none(listOf("host '$host' is not a known property source"))
            return SourceDetection(
                definition = generic,
                confidence = 0.3,
                signal = DetectionSignal.GENERIC_FALLBACK,
                evidence = listOf("host '$host' is not a known property source; generic parsing applies")
            )
        }

        val ordered = scored.sortedWith(
            compareByDescending<ScoredCandidate> { it.confidence }
                .thenByDescending { it.definition.trustRank }
                .thenBy { it.definition.sourceId }
        )
        val best = ordered.first()
        val alternatives = ordered.drop(1)
            .filter { it.definition.sourceId != best.definition.sourceId }
            .map { it.definition }

        return SourceDetection(
            definition = best.definition,
            confidence = best.confidence,
            signal = best.signal,
            evidence = best.evidence,
            alternatives = alternatives
        )
    }

    private data class ScoredCandidate(
        val definition: PropertySourceDefinition,
        val confidence: Double,
        val signal: DetectionSignal,
        val evidence: List<String>
    )
}
