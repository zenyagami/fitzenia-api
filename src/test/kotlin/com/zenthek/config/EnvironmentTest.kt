package com.zenthek.config

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentTest {

    @Test
    fun `off mirror batch size defaults to 100`() {
        assertEquals(100, parseOffMirrorBatchSize(null))
        assertEquals(100, parseOffMirrorBatchSize(""))
        assertEquals(100, parseOffMirrorBatchSize("not-a-number"))
    }

    @Test
    fun `off mirror batch size accepts valid values`() {
        assertEquals(1, parseOffMirrorBatchSize("1"))
        assertEquals(250, parseOffMirrorBatchSize("250"))
        assertEquals(500, parseOffMirrorBatchSize("500"))
    }

    @Test
    fun `off mirror batch size is clamped to supported range`() {
        assertEquals(1, parseOffMirrorBatchSize("0"))
        assertEquals(1, parseOffMirrorBatchSize("-50"))
        assertEquals(500, parseOffMirrorBatchSize("501"))
        assertEquals(500, parseOffMirrorBatchSize("999"))
    }
}
