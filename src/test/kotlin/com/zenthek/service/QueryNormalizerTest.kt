package com.zenthek.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryNormalizerTest {

    @Test
    fun `normalize lowercases and collapses whitespace`() {
        assertEquals("flat white", QueryNormalizer.normalize("  Flat   White "))
        assertEquals("cheesecake", QueryNormalizer.normalize("Cheesecake"))
        assertEquals("cheesecake", QueryNormalizer.normalize("\tcheesecake\n"))
    }

    @Test
    fun `normalize applies NFKC for compatibility chars`() {
        // Full-width "A" (U+FF21) → ASCII "a" after NFKC + lowercase
        assertEquals("abc", QueryNormalizer.normalize("\uFF21\uFF22\uFF23"))
    }

    @Test
    fun `normalize on blank returns empty string`() {
        assertEquals("", QueryNormalizer.normalize("   "))
        assertEquals("", QueryNormalizer.normalize(""))
    }

    @Test
    fun `tokenize splits on non-alphanumeric`() {
        assertEquals(listOf("bananas", "raw"), QueryNormalizer.tokenize("Bananas, raw"))
        assertEquals(listOf("cheesecake", "factory"), QueryNormalizer.tokenize("Cheesecake  Factory!!!"))
    }

    @Test
    fun `containsAsWholeTokens matches exact single token`() {
        assertTrue(QueryNormalizer.containsAsWholeTokens("Sandwich, cold cut sub", "sandwich"))
    }

    @Test
    fun `containsAsWholeTokens matches with simple plural tolerance`() {
        // The canonical case: query "banana" matches "Bananas, raw"
        assertTrue(QueryNormalizer.containsAsWholeTokens("Bananas, raw", "banana"))
        assertTrue(QueryNormalizer.containsAsWholeTokens("Tomatoes, red", "tomato"))
    }

    @Test
    fun `containsAsWholeTokens matches multi-word query as consecutive sequence`() {
        assertTrue(QueryNormalizer.containsAsWholeTokens("Flat white coffee", "flat white"))
        assertTrue(QueryNormalizer.containsAsWholeTokens("Iced flat white, small", "flat white"))
    }

    @Test
    fun `containsAsWholeTokens rejects partial substring inside a longer word`() {
        // "anana" should NOT match "banana" via the plural-tolerance path because
        // the candidate token "banana" does not start with "anana".
        assertFalse(QueryNormalizer.containsAsWholeTokens("Banana bread", "anana"))
    }

    @Test
    fun `containsAsWholeTokens returns false on empty query`() {
        assertFalse(QueryNormalizer.containsAsWholeTokens("anything here", ""))
    }

    @Test
    fun `containsAsWholeTokens rejects when plural delta exceeds 2 chars`() {
        // "cheesecake" → "cheesecakery" would be a 2-char delta; acceptable.
        // But 3+ chars should not match.
        assertFalse(QueryNormalizer.containsAsWholeTokens("Cheesecakelover special", "cheesecake"))
    }
}
