package ua.konditer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizerTest {

    @Test
    fun locksAfterEnoughVotes() {
        val s = Stabilizer()
        assertFalse(s.push("a", 0.9f))
        assertFalse(s.push("a", 0.9f))
        assertFalse(s.push("a", 0.9f))
        assertTrue(s.push("a", 0.9f))   // 4-й голос — фіксація
        assertEquals("a", s.lockedId)
        assertFalse(s.push("a", 0.9f))  // повторно не сигналить
    }

    @Test
    fun singleNoisyFrameDoesNotBreakLock() {
        val s = Stabilizer()
        repeat(5) { s.push("a", 0.8f) }
        s.push("b", 0.6f)
        s.push(null, 0f)
        assertEquals("a", s.lockedId)
    }

    @Test
    fun releasesWhenItemDisappears() {
        val s = Stabilizer()
        repeat(6) { s.push("a", 0.8f) }
        repeat(5) { s.push(null, 0f) }
        assertNull(s.lockedId)
        assertEquals(5, s.unknownStreak)
    }

    @Test
    fun switchesToNewItem() {
        val s = Stabilizer()
        repeat(6) { s.push("a", 0.8f) }
        var switched = false
        repeat(6) { if (s.push("b", 0.8f)) switched = true }
        assertTrue(switched)
        assertEquals("b", s.lockedId)
    }
}
