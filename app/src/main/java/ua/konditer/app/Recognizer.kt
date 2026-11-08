package ua.konditer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Перетворює фото на опис:
 *  - «відбиток» форми й текстури від нейромережі MobileNetV3 (через MediaPipe);
 *  - колірний опис (середній колір у Lab + гістограма відтінків), щоб відрізняти,
 *    наприклад, рожевий від світло-рожевого.
 */
class Recognizer(context: Context) : Closeable {

    private val embedder: ImageEmbedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setL2Normalize(true)
            .setQuantize(false)
            .setRunningMode(RunningMode.IMAGE)
            .build()
    )

    fun describe(bitmap: Bitmap): Shot {
        val result = embedder.embed(BitmapImageBuilder(bitmap).build())
        val emb = result.embeddingResult().embeddings()[0].floatEmbedding()
        return Shot(emb, colorDescriptor(bitmap))
    }

    override fun close() = embedder.close()

    companion object {
        const val MODEL = "mobilenet_v3_small.tflite"

        /** Вага форми й кольору в підсумковій оцінці. */
        private const val SHAPE_WEIGHT = 0.65f
        private const val COLOR_WEIGHT = 0.35f
        private const val HUE_BINS = 12

        // ---------- Порівняння ----------

        fun score(a: Shot, b: Shot): Float =
            SHAPE_WEIGHT * cosine(a.emb, b.emb) + COLOR_WEIGHT * colorSimilarity(a.color, b.color)

        private fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
            }
            return if (na == 0f || nb == 0f) 0f else (dot / sqrt(na * nb)).coerceIn(0f, 1f)
        }

        private fun colorSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.size < 3) return 0f
            // Різниця середніх кольорів (ΔE у просторі Lab)
            val dl = a[0] - b[0]
            val da = a[1] - b[1]
            val db = a[2] - b[2]
            val deltaE = sqrt(dl * dl + da * da + db * db)
            val meanSim = exp(-deltaE / 12f)
            // Перетин гістограм відтінків
            var inter = 0f
            for (i in 3 until a.size) inter += min(a[i], b[i])
            return 0.6f * meanSim + 0.4f * inter
        }

        // ---------- Колірний опис ----------

        /**
         * [L, a, b] середнього кольору виробу + 12 кошиків відтінку + частка безбарвного.
         * Світлий нейтральний фон (білий папір/тарілка) не враховується.
         */
        fun colorDescriptor(src: Bitmap): FloatArray {
            val small = Bitmap.createScaledBitmap(src, 64, 64, true)
            val px = IntArray(64 * 64)
            small.getPixels(px, 0, 64, 0, 0, 64, 64)

            fun build(skipBackground: Boolean): FloatArray? {
                var n = 0
                var sl = 0.0; var sa = 0.0; var sb = 0.0
                val hist = FloatArray(HUE_BINS + 1)
                val hsv = FloatArray(3)
                for (p in px) {
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    Color.RGBToHSV(r, g, b, hsv)
                    if (skipBackground && hsv[1] < 0.10f && hsv[2] > 0.78f) continue
                    val lab = rgbToLab(r, g, b)
                    sl += lab[0]; sa += lab[1]; sb += lab[2]
                    if (hsv[1] < 0.12f || hsv[2] < 0.15f) {
                        hist[HUE_BINS] += 1f
                    } else {
                        val bin = ((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                        hist[bin] += 1f
                    }
                    n++
                }
                if (n < 150) return null
                val out = FloatArray(3 + hist.size)
                out[0] = (sl / n).toFloat(); out[1] = (sa / n).toFloat(); out[2] = (sb / n).toFloat()
                for (i in hist.indices) out[3 + i] = hist[i] / n
                return out
            }
            // Якщо майже все — фон (наприклад, білий виріб на білому), рахуємо по всьому кадру
            return build(true) ?: build(false)!!
        }

        private fun rgbToLab(r: Int, g: Int, b: Int): FloatArray {
            fun lin(c: Int): Double {
                val v = c / 255.0
                return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            val rl = lin(r); val gl = lin(g); val bl = lin(b)
            val x = (rl * 0.4124 + gl * 0.3576 + bl * 0.1805) / 0.95047
            val y = (rl * 0.2126 + gl * 0.7152 + bl * 0.0722)
            val z = (rl * 0.0193 + gl * 0.1192 + bl * 0.9505) / 1.08883
            fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
            val fx = f(x); val fy = f(y); val fz = f(z)
            return floatArrayOf(
                (116 * fy - 16).toFloat(),
                (500 * (fx - fy)).toFloat(),
                (200 * (fy - fz)).toFloat()
            )
        }

        // ---------- Пошук у каталозі ----------

        class Match(val item: Item, val score: Float)

        /** Для кожного виробу береться найкращий збіг серед його еталонних знімків. */
        fun rank(query: Shot, items: List<Item>): List<Match> =
            items.filter { it.shots.isNotEmpty() }
                .map { item -> Match(item, item.shots.maxOf { score(query, it) }) }
                .sortedByDescending { it.score }
    }
}
