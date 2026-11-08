package ua.konditer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import java.io.Closeable
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Нейромережа MobileNetV3-large (через MediaPipe): фото → вектор-«відбиток».
 * Плюс колірний опис і оцінка якості кадру (різкість, яскравість).
 * Не потокобезпечний: використовувати з одного потоку.
 */
class Embedder(context: Context) : Closeable {

    private val embedder: ImageEmbedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_FILE).build())
            .setL2Normalize(true)
            .setQuantize(false)
            .setRunningMode(RunningMode.IMAGE)
            .build()
    )

    fun describe(bmp: Bitmap): Shot = Shot(embed(bmp), colorDescriptor(bmp))

    /** Оригінал + повороти на 90/180/270°. Колір від повороту не змінюється. */
    fun describeAugmented(bmp: Bitmap): List<Shot> {
        val color = colorDescriptor(bmp)
        return listOf(0f, 90f, 180f, 270f).map { deg ->
            val img = if (deg == 0f) bmp else {
                val m = Matrix().apply { postRotate(deg) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            Shot(embed(img), color)
        }
    }

    private fun embed(bmp: Bitmap): FloatArray {
        val result = embedder.embed(BitmapImageBuilder(bmp).build())
        return result.embeddingResult().embeddings()[0].floatEmbedding()
    }

    override fun close() = embedder.close()

    companion object {
        const val MODEL_FILE = "mobilenet_v3_large.tflite"
        /** Змінюється разом з моделлю — тоді вектори перераховуються з фото. */
        const val MODEL_ID = "mpv3l-1"
        /** Розмір кропа, який зберігається і подається в мережу. */
        const val CROP = 256
        private const val HUE_BINS = 12

        /** Різкість: дисперсія лапласіана на сірому 96×96. Розмиті кадри дають малі значення. */
        fun sharpness(bmp: Bitmap): Float {
            val n = 96
            val s = Bitmap.createScaledBitmap(bmp, n, n, true)
            val px = IntArray(n * n)
            s.getPixels(px, 0, n, 0, 0, n, n)
            val g = FloatArray(n * n) {
                val p = px[it]
                0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
            }
            var sum = 0.0
            var sum2 = 0.0
            var cnt = 0
            for (y in 1 until n - 1) for (x in 1 until n - 1) {
                val i = y * n + x
                val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - n] - g[i + n]
                sum += l; sum2 += l * l; cnt++
            }
            val mean = sum / cnt
            return (sum2 / cnt - mean * mean).toFloat()
        }

        /** Середня яскравість 0…255. */
        fun brightness(bmp: Bitmap): Float {
            val n = 32
            val s = Bitmap.createScaledBitmap(bmp, n, n, true)
            val px = IntArray(n * n)
            s.getPixels(px, 0, n, 0, 0, n, n)
            var sum = 0.0
            for (p in px) sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
            return (sum / px.size).toFloat()
        }

        /**
         * [L, a, b] середнього кольору виробу + 12 кошиків відтінку + частка безбарвного.
         * Світлий нейтральний фон (білий аркуш, тарілка) не враховується.
         */
        fun colorDescriptor(src: Bitmap): FloatArray {
            val n = 64
            val small = Bitmap.createScaledBitmap(src, n, n, true)
            val px = IntArray(n * n)
            small.getPixels(px, 0, n, 0, 0, n, n)

            fun build(skipBackground: Boolean): FloatArray? {
                var cnt = 0
                var sl = 0.0; var sa = 0.0; var sb = 0.0
                val hist = FloatArray(HUE_BINS + 1)
                val hsv = FloatArray(3)
                for (p in px) {
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    Color.RGBToHSV(r, g, b, hsv)
                    if (skipBackground && hsv[1] < 0.10f && hsv[2] > 0.78f) continue
                    val lab = rgbToLab(r, g, b)
                    sl += lab[0]; sa += lab[1]; sb += lab[2]
                    if (hsv[1] < 0.12f || hsv[2] < 0.15f) hist[HUE_BINS] += 1f
                    else hist[((hsv[0] / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)] += 1f
                    cnt++
                }
                if (cnt < 150) return null
                val out = FloatArray(3 + hist.size)
                out[0] = (sl / cnt).toFloat(); out[1] = (sa / cnt).toFloat(); out[2] = (sb / cnt).toFloat()
                for (i in hist.indices) out[3 + i] = hist[i] / cnt
                return out
            }
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
            return floatArrayOf((116 * fy - 16).toFloat(), (500 * (fx - fy)).toFloat(), (200 * (fy - fz)).toFloat())
        }

        /** Кладе картинку на білий квадрат CROP×CROP з полями [margin] (частка сторони). */
        fun onWhiteSquare(src: Bitmap, margin: Float): Bitmap {
            val out = Bitmap.createBitmap(CROP, CROP, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(Color.WHITE)
            val inner = CROP * (1f - 2 * margin)
            val k = inner / maxOf(src.width, src.height)
            val w = src.width * k
            val h = src.height * k
            val dst = android.graphics.RectF((CROP - w) / 2f, (CROP - h) / 2f, (CROP + w) / 2f, (CROP + h) / 2f)
            c.drawBitmap(src, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
