package ua.konditer.app

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Незмінний «зліпок» каталогу для пошуку. Будується щоразу, коли каталог змінюється,
 * тому потік розпізнавання ніколи не бачить напівзмінених даних.
 *
 * Оцінка схожості абсолютна (0…1) мало що говорить сама по собі: вона залежить від моделі,
 * світла, фону. Тому впевненість рахується відносно:
 *  1) наскільки збіг близький до того, як еталони цього виробу схожі між собою (самокалібрування);
 *  2) наскільки найкращий виріб випереджає другий за схожістю виріб і порожній фон.
 */
class MatchIndex private constructor(
    val items: List<Item>,
    private val itemOf: IntArray,          // для кожного вектора — індекс виробу
    private val sampleOf: Array<String>,   // для кожного вектора — id еталона
    private val embs: Array<FloatArray>,
    private val colors: Array<FloatArray>,
    private val bg: List<Shot>,
    private val intra: FloatArray,         // самокалібрування для кожного виробу (NaN — невідомо)
    private val defaultIntra: Float
) {
    val isEmpty: Boolean get() = embs.isEmpty()
    val hasBackground: Boolean get() = bg.isNotEmpty()
    val vectorCount: Int get() = embs.size

    class Ranked(val item: Item, val score: Float)

    class Result(
        val ranked: List<Ranked>,
        val bgScore: Float?
    )

    enum class State { NO_CATALOG, EMPTY, UNKNOWN, MATCH }

    class Decision(
        val state: State,
        val best: Ranked?,
        val confidence: Float,
        val alternatives: List<Ranked>,
        val debug: String
    ) {
        val itemId: String? get() = if (state == State.MATCH) best?.item?.id else null
    }

    // ---------------- Пошук ----------------

    /** Найкраща оцінка для кожного виробу (за всіма його еталонами), за спаданням. */
    fun query(q: Shot, excludeSampleId: String? = null): Result {
        val best = FloatArray(items.size) { -1f }
        for (k in embs.indices) {
            if (excludeSampleId != null && sampleOf[k] == excludeSampleId) continue
            val s = score(q.emb, q.color, embs[k], colors[k])
            val i = itemOf[k]
            if (s > best[i]) best[i] = s
        }
        val ranked = items.indices
            .filter { best[it] >= 0f }
            .map { Ranked(items[it], best[it]) }
            .sortedByDescending { it.score }
        val bgScore = if (bg.isEmpty()) null else bg.maxOf { score(q.emb, q.color, it.emb, it.color) }
        return Result(ranked, bgScore)
    }

    fun decide(r: Result, sens: Sensitivity): Decision {
        if (r.ranked.isEmpty()) return Decision(State.NO_CATALOG, null, 0f, emptyList(), "каталог порожній")
        val top = r.ranked[0]
        val s1 = top.score
        val s2 = r.ranked.getOrNull(1)?.score
        val bgS = r.bgScore
        val alts = r.ranked.drop(1).take(3)

        if (bgS != null && bgS >= s1) {
            return Decision(State.EMPTY, top, 0f, alts, dbg(s1, s2, bgS, Float.NaN, 0f, 0f, 0f))
        }

        val other = max(s2 ?: -1f, bgS ?: -1f)
        val margin = if (other >= 0f) s1 - other else Float.NaN
        val marginConf = if (margin.isNaN()) 0.5f else clamp01(margin / MARGIN_FULL)

        val ii = items.indexOf(top.item)
        val cal = intra.getOrNull(ii)?.takeIf { !it.isNaN() } ?: defaultIntra
        val relConf = if (cal.isNaN() || cal <= 0f) 0.5f
        else clamp01((s1 / cal - sens.relLow) / (1f - sens.relLow))

        val conf = 0.4f * relConf + 0.6f * marginConf
        val ok = conf >= sens.needConf && (margin.isNaN() || margin >= MIN_MARGIN)
        return Decision(
            if (ok) State.MATCH else State.UNKNOWN,
            top, conf, alts,
            dbg(s1, s2, bgS, cal, relConf, marginConf, conf)
        )
    }

    // ---------------- Перевірки якості каталогу ----------------

    /**
     * З якими виробами плутається [itemId]: для кожного його еталона дивимось,
     * чи інший виріб схожий не менше, ніж найближчий «свій» еталон.
     */
    fun conflictsFor(itemId: String): List<Item> {
        val ii = items.indexOfFirst { it.id == itemId }
        if (ii < 0) return emptyList()
        val own = items[ii].samples.filter { it.shots.isNotEmpty() }
        if (own.size < 2) return emptyList()
        val counts = HashMap<Int, Int>()
        for (s in own) {
            val q = s.shots[0]
            var ownBest = -1f
            val otherBest = HashMap<Int, Float>()
            for (k in embs.indices) {
                if (sampleOf[k] == s.id) continue
                val sc = score(q.emb, q.color, embs[k], colors[k])
                val i = itemOf[k]
                if (i == ii) ownBest = max(ownBest, sc)
                else if (sc > (otherBest[i] ?: -1f)) otherBest[i] = sc
            }
            for ((i, sc) in otherBest) if (sc >= ownBest - 0.01f) counts[i] = (counts[i] ?: 0) + 1
        }
        val need = max(1, (own.size * 0.3f).toInt())
        return counts.filter { it.value >= need }.keys.map { items[it] }
    }

    class SelfTest(val checked: Int, val correct: Int, val confusions: List<Triple<Item, Item, Int>>)

    /** «Іспит» каталогу: кожен еталон по черзі ховаємо й перевіряємо, чи впізнається виріб без нього. */
    fun selfTest(perItem: Int = 3, progress: (Int, Int) -> Unit = { _, _ -> }): SelfTest {
        val probes = ArrayList<Pair<Item, Sample>>()
        for (it in items) {
            val ss = it.samples.filter { s -> s.shots.isNotEmpty() }
            if (ss.size < 2) continue
            val step = max(1, ss.size / perItem)
            ss.filterIndexed { idx, _ -> idx % step == 0 }.take(perItem).forEach { s -> probes.add(it to s) }
        }
        var correct = 0
        val conf = HashMap<Pair<Item, Item>, Int>()
        probes.forEachIndexed { n, (item, s) ->
            val r = query(s.shots[0], excludeSampleId = s.id)
            val top = r.ranked.firstOrNull()?.item
            if (top == item) correct++ else if (top != null) {
                conf[item to top] = (conf[item to top] ?: 0) + 1
            }
            progress(n + 1, probes.size)
        }
        return SelfTest(
            probes.size, correct,
            conf.entries.sortedByDescending { it.value }.map { Triple(it.key.first, it.key.second, it.value) }
        )
    }

    companion object {
        const val SHAPE_WEIGHT = 0.7f
        const val COLOR_WEIGHT = 0.3f
        /** Відрив від конкурента, за якого впевненість за відривом = 100%. */
        const val MARGIN_FULL = 0.08f
        const val MIN_MARGIN = 0.015f

        val NONE = build(emptyList(), emptyList())

        fun build(items: List<Item>, background: List<Sample>): MatchIndex {
            val itemOf = ArrayList<Int>()
            val sampleOf = ArrayList<String>()
            val embs = ArrayList<FloatArray>()
            val colors = ArrayList<FloatArray>()
            val usable = items.filter { it.samples.any { s -> s.shots.isNotEmpty() } }
            usable.forEachIndexed { i, item ->
                for (s in item.samples) for (sh in s.shots) {
                    itemOf.add(i); sampleOf.add(s.id); embs.add(sh.emb); colors.add(sh.color)
                }
            }
            val intra = FloatArray(usable.size) { i -> intraFor(usable[i]) }
            val known = intra.filter { !it.isNaN() }.sorted()
            val def = if (known.isEmpty()) Float.NaN else known[known.size / 2]
            val bg = background.flatMap { it.shots }
            return MatchIndex(
                usable, itemOf.toIntArray(), sampleOf.toTypedArray(),
                embs.toTypedArray(), colors.toTypedArray(), bg, intra, def
            )
        }

        /** Медіана схожості кожного еталона з найближчим іншим еталоном того самого виробу. */
        private fun intraFor(item: Item): Float {
            val ss = item.samples.filter { it.shots.isNotEmpty() }
            if (ss.size < 2) return Float.NaN
            val vals = ss.map { a ->
                val q = a.shots[0]
                ss.filter { it !== a }.maxOf { b -> b.shots.maxOf { score(q, it) } }
            }.sorted()
            return vals[vals.size / 2]
        }

        fun score(a: Shot, b: Shot): Float = score(a.emb, a.color, b.emb, b.color)

        fun score(ae: FloatArray, ac: FloatArray, be: FloatArray, bc: FloatArray): Float =
            SHAPE_WEIGHT * cosine(ae, be) + COLOR_WEIGHT * colorSimilarity(ac, bc)

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                val x = a[i]; val y = b[i]
                dot += x * y; na += x * x; nb += y * y
            }
            return if (na == 0f || nb == 0f) 0f else clamp01(dot / sqrt(na * nb))
        }

        /** Колір: різниця середніх кольорів у Lab (ΔE) + перетин гістограм відтінків. */
        fun colorSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.size < 3) return 0f
            val dl = a[0] - b[0]
            val da = a[1] - b[1]
            val db = a[2] - b[2]
            val deltaE = sqrt(dl * dl + da * da + db * db)
            val meanSim = exp(-deltaE / 15f)
            var inter = 0f
            for (i in 3 until a.size) inter += min(a[i], b[i])
            return 0.6f * meanSim + 0.4f * inter
        }

        fun clamp01(v: Float) = max(0f, min(1f, v))

        private fun f2(v: Float?) = if (v == null || v.isNaN()) "—" else "%.3f".format(v)

        private fun dbg(s1: Float, s2: Float?, bg: Float?, cal: Float, rel: Float, mar: Float, conf: Float) =
            "s1=${f2(s1)} s2=${f2(s2)} фон=${f2(bg)} калібр=${f2(cal)}\n" +
                "відн=${f2(rel)} відрив=${f2(mar)} впевн=${f2(conf)}"
    }
}
