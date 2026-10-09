package com.example.domain.intelligence.scrape

/**
 * Bounded regex iteration.
 *
 * Portal pages are multi-megabyte and a `findAll` over a whole document is an easy way to hang the
 * calling coroutine; every scan in this package goes through this so there is a hard cap.
 */
internal fun Regex.findIterated(input: String, limit: Int = 400): Sequence<MatchResult> = sequence {
    var count = 0
    var index = 0
    while (count < limit) {
        val match = find(input, index) ?: break
        yield(match)
        index = match.range.last + 1
        count++
    }
}
