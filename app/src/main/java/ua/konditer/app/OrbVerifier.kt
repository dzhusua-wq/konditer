package ua.konditer.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Друга перевірка для плоских виробів (топери, печиво з малюнком).
 *
 * Нейромережа бачить «загальний вигляд», тож дві картинки, що відрізняються лише написом
 * чи дрібною деталлю, для неї майже однакові. Тут шукаються сотні дрібних характерних точок
 * (кути літер, контури, візерунки) і перевіряється, чи вони збігаються з еталоном
 * з урахуванням перспективи (RANSAC-гомографія). Кількість точок, що збіглись геометрично
 * («inliers»), — дуже надійна ознака того самого малюнка.
 *
 * Використовувати з одного потоку.
 */
class OrbVerifier {

    private class Feat(val pts: Array<Point>, val desc: Mat)

    private val orb: ORB = ORB.create(700)
    private val matcher: DescriptorMatcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)

    /** Кеш ознак еталонів (id → ознаки або null, якщо точок замало). */
    private val cache = object : LinkedHashMap<String, Feat?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Feat?>?): Boolean {
            val drop = size > 400
            if (drop) eldest?.value?.desc?.release()
            return drop
        }
    }

    private fun features(bmp: Bitmap): Feat? {
        val rgba = Mat()
        val gray = Mat()
        val kp = MatOfKeyPoint()
        val desc = Mat()
        try {
            val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            orb.detectAndCompute(gray, Mat(), kp, desc)
            if (desc.rows() < 15) { desc.release(); return null }
            return Feat(kp.toArray().map { it.pt }.toTypedArray(), desc)
        } finally {
            rgba.release(); gray.release(); kp.release()
        }
    }

    private fun sampleFeat(id: String): Feat? {
        if (cache.containsKey(id)) return cache[id]
        val f = Catalog.hiFile(id).takeIf { it.exists() } ?: Catalog.imageFile(id)
        val feat = BitmapFactory.decodeFile(f.path)?.let { features(it) }
        cache[id] = feat
        return feat
    }

    /** Кількість точок, що збіглися з урахуванням геометрії. */
    private fun inliers(q: Feat, t: Feat): Int {
        val knn = ArrayList<MatOfDMatch>()
        matcher.knnMatch(q.desc, t.desc, knn, 2)
        val src = ArrayList<Point>()
        val dst = ArrayList<Point>()
        for (m in knn) {
            val a = m.toArray()
            if (a.size == 2 && a[0].distance < 0.75f * a[1].distance) {
                src.add(q.pts[a[0].queryIdx]); dst.add(t.pts[a[0].trainIdx])
            }
            m.release()
        }
        if (src.size < 8) return 0
        val s = MatOfPoint2f(*src.toTypedArray())
        val d = MatOfPoint2f(*dst.toTypedArray())
        val mask = Mat()
        val h = Calib3d.findHomography(s, d, Calib3d.RANSAC, 5.0, mask)
        val n = if (h.empty()) 0 else Core.countNonZero(mask)
        s.release(); d.release(); mask.release(); h.release()
        return n
    }

    /**
     * Уточнює рішення нейромережі, якщо серед кандидатів є плоскі вироби.
     * [qHi] — детальний кроп рамки (до 512 px).
     */
    fun refine(d: MatchIndex.Decision, ranked: List<MatchIndex.Ranked>, qHi: Bitmap): MatchIndex.Decision {
        if (ranked.isEmpty() || d.state == MatchIndex.State.EMPTY) return d
        val topScore = ranked[0].score
        val cands = ranked.take(4).filter { it.item.kind == Kind.FLAT && it.score >= topScore - 0.10f }.take(3)
        if (cands.isEmpty()) return d
        // Впевнений збіг з плоским виробом без схожих конкурентів — перевірка не потрібна
        if (d.state == MatchIndex.State.MATCH && d.confidence >= 0.85f && cands.size == 1) return d

        val q = features(qHi) ?: return d
        try {
            val inl = cands.map { c ->
                val ss = c.item.samples
                val step = max(1, ss.size / 6)
                val best = ss.filterIndexed { i, _ -> i % step == 0 }.take(6)
                    .mapNotNull { sampleFeat(it.id) }
                    .maxOfOrNull { inliers(q, it) } ?: 0
                c to best
            }.sortedByDescending { it.second }

            val (bestC, bestN) = inl[0]
            val second = inl.getOrNull(1)?.second ?: 0
            val note = "ORB: " + inl.joinToString { "${it.first.item.name.take(12)}=${it.second}" }

            if (bestN >= 15 && bestN >= second * 1.6f) {
                val conf = max(d.confidence, min(1f, 0.55f + bestN / 120f))
                val alts = ranked.filter { it.item.id != bestC.item.id }.take(3)
                return MatchIndex.Decision(MatchIndex.State.MATCH, bestC, conf, alts, d.debug + "\n" + note + " ✓")
            }
            // Нейромережа вибрала плоский виріб, але деталі явно належать іншому — не ризикуємо
            val top = d.best
            if (d.state == MatchIndex.State.MATCH && top != null && top.item.kind == Kind.FLAT) {
                val own = inl.find { it.first.item.id == top.item.id }?.second ?: 0
                if (bestN >= 12 && own < bestN / 2 && bestC.item.id != top.item.id) {
                    return MatchIndex.Decision(MatchIndex.State.UNKNOWN, top, d.confidence * 0.5f, d.alternatives, d.debug + "\n" + note + " ✗")
                }
            }
            return MatchIndex.Decision(d.state, d.best, d.confidence, d.alternatives, d.debug + "\n" + note)
        } finally {
            q.desc.release()
        }
    }

    fun clear() {
        cache.values.forEach { it?.desc?.release() }
        cache.clear()
    }
}
