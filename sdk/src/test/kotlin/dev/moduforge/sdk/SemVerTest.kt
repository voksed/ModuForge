package dev.moduforge.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `parses core version, pre-release and build metadata`() {
        assertEquals(SemVer(1, 2, 3), SemVer.parseOrNull("1.2.3"))
        assertEquals(SemVer(1, 0, 0, "rc.1"), SemVer.parseOrNull("1.0.0-rc.1+build.5"))
    }

    @Test
    fun `rejects malformed versions`() {
        listOf("", "1", "1.2", "01.2.3", "1.2.3.4", "v1.2.3", "1.2.x", "1.2.99999999999").forEach {
            assertNull(it, SemVer.parseOrNull(it))
        }
    }

    @Test
    fun `orders by precedence`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-rc.2", "1.0.0-rc.10", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
            .map(SemVer::parse)
        assertEquals(ordered, ordered.shuffled().sorted())
    }

    @Test
    fun `range is a conjunction of comparators`() {
        val range = SemVerRange.parseOrNull(">=1.0.0 <2.0.0")!!
        assertTrue(SemVer(1, 0, 0) in range)
        assertTrue(SemVer(1, 9, 9) in range)
        assertFalse(SemVer(2, 0, 0) in range)
        assertFalse(SemVer(0, 9, 0) in range)
        assertFalse(SemVer(1, 0, 0, "rc.1") in range)
    }

    @Test
    fun `bare version in a range means exact match`() {
        val range = SemVerRange.parseOrNull("1.2.3")!!
        assertTrue(SemVer(1, 2, 3) in range)
        assertFalse(SemVer(1, 2, 4) in range)
    }

    @Test
    fun `rejects malformed ranges`() {
        listOf("", "   ", ">=", ">=1.0", "~1.0.0", ">=1.0.0 ||").forEach {
            assertNull(it, SemVerRange.parseOrNull(it))
        }
    }
}
