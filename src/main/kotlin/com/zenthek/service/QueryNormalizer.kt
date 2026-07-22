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
 * Tokenization splits on any non-letter/non-digit, then folds accents
 * ("żurek" ≈ "zurek", "käsespätzle" ≈ "kasespatzle"). Accent folding and simple
 * plural stemming ("bananas" ≈ "banana") apply only in the heuristic match —
 * never in normalization itself, so the catalog key stays stable.
 */
object QueryNormalizer {

    private val whitespace = Regex("\\s+")
    private val nonAlphaNum = Regex("[^\\p{L}\\p{N}]+")
    private val combiningMarks = Regex("\\p{Mn}+")

    fun normalize(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        return nfkc.lowercase().trim().replace(whitespace, " ")
    }

    /**
     * Strip BCP 47 Unicode (`-u-…`), transform (`-t-…`), and private-use (`-x-…`)
     * extensions from a locale tag, preserving the `language[-script][-region]`
     * prefix. Used as the catalog cache key so `en-DE` and `en-DE-u-mu-celsius`
     * collapse to the same bucket — without this, every OS locale variant
     * (Unicode calendar/numbering/measurement extensions) creates a fresh
     * canonical and future searches miss the map.
     */
    fun canonicalLocale(locale: String): String {
        val trimmed = locale.trim()
        if (trimmed.isEmpty()) return trimmed
        var cut = trimmed.length
        for (marker in LOCALE_EXTENSION_MARKERS) {
            val idx = trimmed.indexOf(marker, ignoreCase = true)
            if (idx in 0 until cut) cut = idx
        }
        return trimmed.substring(0, cut)
    }

    fun tokenize(text: String): List<String> {
        return foldDiacritics(text.lowercase())
            .split(nonAlphaNum)
            .filter { it.isNotBlank() }
    }

    /**
     * Fold accents so a query typed without them still matches the accented name:
     * "zurek" ≈ "żurek", "kasespatzle" ≈ "käsespätzle", "golabki" ≈ "gołąbki".
     * Nobody reaches for ż or ä on an English keyboard, and without this the
     * display-name compatibility guard silently drops those rows on every lookup.
     *
     * NFD decomposition + combining-mark removal covers most accented letters, but
     * several have no canonical decomposition and need an explicit mapping —
     * Polish ł, German ß, Nordic ø/æ, French œ, Turkish ı, Croatian đ.
     *
     * Matching only. [normalize] deliberately does NOT fold: its output is the
     * catalog key, and folding there would orphan every row already stored.
     */
    private fun foldDiacritics(lowercased: String): String =
        Normalizer.normalize(lowercased, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .replace('ł', 'l')
            .replace('ø', 'o')
            .replace('đ', 'd')
            .replace('ı', 'i')
            .replace("ß", "ss")
            .replace("æ", "ae")
            .replace("œ", "oe")

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

    /**
     * Stricter cousin of [containsAsWholeTokens]: candidate tokens must match the
     * query tokens 1-to-1 in count. Prevents promoting OFF products like
     * "Eper-kiwi jogobella" (3 tokens) to GENERIC for query "kiwi" (1 token)
     * just because "kiwi" appears somewhere in the name.
     */
    fun exactTokenMatch(candidateName: String, normalizedQuery: String): Boolean {
        val candidateTokens = tokenize(candidateName)
        val queryTokens = tokenize(normalizedQuery)
        if (queryTokens.isEmpty()) return false
        if (candidateTokens.size != queryTokens.size) return false
        for (i in queryTokens.indices) {
            if (!tokenMatchesWithPluralTolerance(candidateTokens[i], queryTokens[i])) return false
        }
        return true
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

    private val LOCALE_EXTENSION_MARKERS = listOf("-u-", "-t-", "-x-", "_u_", "_t_", "_x_")
}
