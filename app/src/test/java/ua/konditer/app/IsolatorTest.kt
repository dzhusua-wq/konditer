package ua.konditer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class IsolatorTest {

    private val rnd = Random(7)
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun jitter(v: Int, amp: Int) = (v + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255)

    /** Світлий дерев'яний стіл з легким шумом + прямокутний «виріб». */
    private fun scene(w: Int, h: Int, obj: IntArray?, objColor: Triple<Int, Int, Int>): IntArray =
        IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val inside = obj != null && x >= obj[0] && x < obj[2] && y >= obj[1] && y < obj[3]
            if (inside) argb(jitter(objColor.first, 6), jitter(objColor.second, 6), jitter(objColor.third, 6))
            else argb(jitter(205, 8), jitter(170, 8), jitter(120, 8))
        }

    @Test
    fun findsCandyOnTable() {
        val box = intArrayOf(40, 50, 90, 80)
        val found = Isolator.findBox(scene(128, 128, box, Triple(80, 200, 190)), 128, 128)
        assertNotNull(found)
        for (k in 0 until 4) assertTrue("сторона $k: ${found!![k]} vs ${box[k]}", abs(found[k] - box[k]) <= 3)
    }

    @Test
    fun emptyTableGivesNull() {
        assertNull(Isolator.findBox(scene(128, 128, null, Triple(0, 0, 0)), 128, 128))
    }

    @Test
    fun clutteredBackgroundGivesNull() {
        val px = IntArray(128 * 128) { argb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        assertNull(Isolator.findBox(px, 128, 128))
    }

    @Test
    fun tinySpeckIgnored() {
        val box = intArrayOf(62, 62, 66, 66)
        assertNull(Isolator.findBox(scene(128, 128, box, Triple(200, 40, 60)), 128, 128))
    }

    @Test
    fun squareStaysInsideImage() {
        val sq = Isolator.squareAround(intArrayOf(0, 0, 100, 20), 128, 128)
        assertEquals(0, sq[0])
        assertTrue(sq[0] + sq[2] <= 128 && sq[1] + sq[2] <= 128)
    }
}
