package com.zenthek.service

import java.text.Normalizer

/**
 * Normalizes user search queries to a stable catalog key and supports the
 * orchestrator's single-generic-match heuristic. Pure functions, no I/O.
 *
 * Normalization rules:
 *  - Unicode NFKC (folds compatibility characters, e.g. full-width → half-width).
 *  - Lowercase (Locale-insensitive — enough for catalog keying; LLM handles locale naming).
 *  - Trim, collapse runs of whitespace to single space.
 *
 * Tokenization splits on any non-letter/non-digit. Simple plural stemming
 * ("bananas" ≈ "banana") is applied only in the heuristic match — not in
 * normalization itself (so the catalog key stays stable).
 */
object QueryNormalizer {

    private val whitespace = Regex("\\s+")
    private val nonAlphaNum = Regex("[^\\p{L}\\p{N}]+")

    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return nfkc.lowercase().trim().replace(whitespace, " ")
    }

    fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(nonAlphaNum)
            .filter { it.isNotBlank() }
    }

    /**
     * True if `candidateName`'s tokens contain `normalizedQuery`'s tokens as a
     * consecutive sub-sequence, with tolerance for simple plurals on each token.
     *
     * Examples (true):
     *   "Bananas, raw" contains "banana"
     *   "Chicken salad sandwich" contains "sandwich"
     *   "Flat white coffee" contains "flat white"
     * Examples (false):
     *   "Banana bread" does NOT contain "banana" (orchestrator requires the rest to also
     *    match the category; but this function alone returns true — the single-candidate
     *    constraint is enforced upstream by counting matches across all hits).
     */
    fun containsAsWholeTokens(candidateName: String, normalizedQuery: String): Boolean {
        val candidateTokens = tokenize(candidateName)
        val queryTokens = tokenize(normalizedQuery)
        if (queryTokens.isEmpty() || candidateTokens.size < queryTokens.size) return false

        val lastStart = candidateTokens.size - queryTokens.size
        for (start in 0..lastStart) {
            var match = true
            for (i in queryTokens.indices) {
                if (!tokenMatchesWithPluralTolerance(candidateTokens[start + i], queryTokens[i])) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun tokenMatchesWithPluralTolerance(candidate: String, query: String): Boolean {
        if (candidate == query) return true
        // Simple English plural stem: "bananas" ≈ "banana", "cheesecakes" ≈ "cheesecake",
        // "berries" ≈ "berry" handled loosely. Bounded suffix delta keeps this conservative.
        if (candidate.length > query.length &&
            candidate.length - query.length <= 2 &&
            candidate.startsWith(query)
        ) return true
        return false
    }
}
