package ua.konditer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class MatchIndexTest {

    private val rnd = Random(42)
    private val dim = 64

    private fun norm(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    private fun randomVec() = norm(FloatArray(dim) { rnd.nextFloat() })

    private fun noisy(base: FloatArray, amount: Float) =
        norm(FloatArray(dim) { base[it] + (rnd.nextFloat() - 0.5f) * amount })

    private fun color(l: Float, a: Float, b: Float) = FloatArray(16).also {
        it[0] = l; it[1] = a; it[2] = b; it[3] = 1f
    }

    private fun item(name: String, base: FloatArray, col: FloatArray, samples: Int, noise: Float = 0.15f) =
        Item(name, name).apply {
            repeat(samples) { k ->
                this.samples.add(Sample("$name-$k", Sample.SRC_CAMERA, 0L, listOf(Shot(noisy(base, noise), col))))
            }
        }

    @Test
    fun emptyCatalog() {
        val idx = MatchIndex.build(emptyList(), emptyList())
        val d = idx.decide(idx.query(Shot(randomVec(), color(50f, 0f, 0f))), Sensitivity.NORMAL)
        assertEquals(MatchIndex.State.NO_CATALOG, d.state)
    }

    @Test
    fun recognizesCorrectItem() {
        val green = randomVec()
        val pink = randomVec()
        val items = listOf(
            item("green", green, color(60f, -40f, 10f), 8),
            item("pink", pink, color(70f, 35f, 0f), 8)
        )
        val idx = MatchIndex.build(items, emptyList())
        val d = idx.decide(idx.query(Shot(noisy(green, 0.15f), color(60f, -40f, 10f))), Sensitivity.NORMAL)
        assertEquals(MatchIndex.State.MATCH, d.state)
        assertEquals("green", d.itemId)
        assertTrue("впевненість ${d.confidence}", d.confidence >= 0.5f)
    }

    @Test
    fun colorSeparatesSameShape() {
        // Однакова форма, різний колір (рожева й світло-рожева квітка)
        val shape = randomVec()
        val items = listOf(
            item("pink", shape, color(65f, 40f, 5f), 6, noise = 0.05f),
            item("light", shape, color(85f, 15f, 2f), 6, noise = 0.05f)
        )
        val idx = MatchIndex.build(items, emptyList())
        val r = idx.query(Shot(noisy(shape, 0.05f), color(84f, 16f, 2f)))
        assertEquals("light", r.ranked[0].item.id)
    }

    @Test
    fun backgroundWinsWhenFrameIsEmpty() {
        val table = randomVec()
        val bg = (0 until 5).map { Sample("bg$it", Sample.SRC_CAMERA, 0L, listOf(Shot(noisy(table, 0.1f), color(55f, 10f, 30f)))) }
        val items = listOf(item("candy", randomVec(), color(60f, -40f, 10f), 6))
        val idx = MatchIndex.build(items, bg)
        val d = idx.decide(idx.query(Shot(noisy(table, 0.1f), color(55f, 10f, 30f))), Sensitivity.NORMAL)
        assertEquals(MatchIndex.State.EMPTY, d.state)
        assertNull(d.itemId)
    }

    @Test
    fun unknownObjectIsNotMatched() {
        val items = listOf(
            item("a", randomVec(), color(60f, -40f, 10f), 8, noise = 0.05f),
            item("b", randomVec(), color(70f, 35f, 0f), 8, noise = 0.05f)
        )
        val idx = MatchIndex.build(items, emptyList())
        val d = idx.decide(idx.query(Shot(randomVec(), color(30f, 0f, -30f))), Sensitivity.STRICT)
        assertEquals(MatchIndex.State.UNKNOWN, d.state)
    }

    @Test
    fun selfTestFindsNoConfusionForDistinctItems() {
        val items = (0 until 4).map { item("i$it", randomVec(), color(50f + it * 10, 0f, 0f), 5, noise = 0.1f) }
        val r = MatchIndex.build(items, emptyList()).selfTest()
        assertEquals(r.checked, r.correct)
    }

    @Test
    fun conflictsDetectedForTwins() {
        val shape = randomVec()
        val c = color(60f, 20f, 0f)
        val items = listOf(item("x", shape, c, 5, noise = 0.1f), item("y", shape, c, 5, noise = 0.1f))
        val conflicts = MatchIndex.build(items, emptyList()).conflictsFor("x")
        assertEquals(listOf("y"), conflicts.map { it.id })
    }
}
