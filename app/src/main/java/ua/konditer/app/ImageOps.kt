package ua.konditer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** Робота з картинками: кропи, якість кадру, колір, виділення виробу. */
object ImageOps {
    /** Кроп для нейромережі й мініатюр. */
    const val CROP = 256
    /** Детальний кроп для перевірки дрібних деталей (ORB). */
    const val HI = 512
    private const val HUE_BINS = 12

    fun scaleTo(src: Bitmap, side: Int): Bitmap =
        if (src.width == side && src.height == side) src else Bitmap.createScaledBitmap(src, side, side, true)

    fun rotate(src: Bitmap, deg: Float): Bitmap =
        if (deg == 0f) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg) }, true)

    /**
     * Обрізає квадратний кроп щільно навколо виробу (див. [Isolator]).
     * Якщо виріб не знайдено надійно — повертає кроп без змін.
     */
    fun isolate(src: Bitmap): Bitmap {
        val n = 128
        val small = Bitmap.createScaledBitmap(src, n, n, true)
        val px = IntArray(n * n)
        small.getPixels(px, 0, n, 0, 0, n, n)
        val box = Isolator.findBox(px, n, n) ?: return src
        val sq = Isolator.squareAround(box, n, n)
        val k = src.width.toFloat() / n
        val left = (sq[0] * k).toInt()
        val top = (sq[1] * k).toInt()
        val side = (sq[2] * k).toInt().coerceAtMost(minOf(src.width - left, src.height - top))
        if (side < 16) return src
        return scaleTo(Bitmap.createBitmap(src, left, top, side, side), src.width)
    }

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
        val lab = FloatArray(3)

        fun build(skipBackground: Boolean): FloatArray? {
            var cnt = 0
            var sl = 0.0; var sa = 0.0; var sb = 0.0
            val hist = FloatArray(HUE_BINS + 1)
            val hsv = FloatArray(3)
            for (p in px) {
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                Color.RGBToHSV(r, g, b, hsv)
                if (skipBackground && hsv[1] < 0.10f && hsv[2] > 0.78f) continue
                ColorMath.rgbToLab(r, g, b, lab)
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

    /** Кладе картинку на білий квадрат size×size з полями [margin] (частка сторони). */
    fun onWhiteSquare(src: Bitmap, margin: Float, size: Int = CROP): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val inner = size * (1f - 2 * margin)
        val k = inner / maxOf(src.width, src.height)
        val w = src.width * k
        val h = src.height * k
        c.drawBitmap(src, null, RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
