package ua.konditer.app

import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Перетворення кольорів. Без Android-залежностей. */
object ColorMath {
    /** sRGB (0…255) → CIE Lab (L 0…100). */
    fun rgbToLab(r: Int, g: Int, b: Int, out: FloatArray = FloatArray(3)): FloatArray {
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
        out[0] = (116 * fy - 16).toFloat()
        out[1] = (500 * (fx - fy)).toFloat()
        out[2] = (200 * (fy - fz)).toFloat()
        return out
    }
}

/**
 * Знаходить виріб у рамці, відокремлюючи його від фону.
 *
 * Ідея: краї рамки — це фон (стіл, підкладка). Пікселі, що помітно відрізняються від кольору країв,
 * належать виробу. Беремо зв'язну область біля центру й повертаємо її межі.
 * Якщо фон строкатий або виріб не вдалося надійно знайти — повертаємо null,
 * і тоді використовується вся рамка (як раніше).
 *
 * Працює з масивом пікселів ARGB, без Android-залежностей (перевіряється тестами).
 */
object Isolator {

    /** Межі виробу [left, top, right, bottom) у пікселях, або null. */
    fun findBox(px: IntArray, w: Int, h: Int): IntArray? {
        val n = w * h
        if (n == 0 || px.size < n) return null
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n)
        val tmp = FloatArray(3)
        for (i in 0 until n) {
            val p = px[i]
            ColorMath.rgbToLab((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF, tmp)
            L[i] = tmp[0]; A[i] = tmp[1]; B[i] = tmp[2]
        }

        // 1. Колір фону — медіана смуги вздовж країв
        val bw = max(2, w / 32)
        val border = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < bw || y < bw || x >= w - bw || y >= h - bw) border.add(y * w + x)
        }
        fun median(arr: FloatArray): Float {
            val v = border.map { arr[it] }.sorted()
            return v[v.size / 2]
        }
        val bl = median(L); val ba = median(A); val bb = median(B)
        fun dist(i: Int): Float {
            val dl = L[i] - bl; val da = A[i] - ba; val db = B[i] - bb
            // Яскравість важить менше: так тіні менше сприймаються як виріб
            return sqrt(0.35f * dl * dl + da * da + db * db)
        }

        // 2. Поріг залежить від того, наскільки однорідний фон
        val bd = border.map { dist(it) }.sorted()
        val p90 = bd[(bd.size * 0.9f).toInt().coerceAtMost(bd.size - 1)]
        if (p90 > 28f) return null // строкатий фон — надійно відокремити не вийде
        val thr = max(14f, p90 * 1.5f)

        // 3. Маска + згладжування (прибирає поодинокі пікселі й заповнює дрібні дірки)
        val raw = BooleanArray(n) { dist(it) > thr }
        val mask = BooleanArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && raw[yy * w + xx]) c++
            }
            mask[y * w + x] = c >= 5
        }

        // 4. Зв'язні області
        val label = IntArray(n) { -1 }
        class Comp(var area: Int = 0, var l: Int = Int.MAX_VALUE, var t: Int = Int.MAX_VALUE, var r: Int = -1, var b: Int = -1)
        val comps = ArrayList<Comp>()
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (!mask[start] || label[start] >= 0) continue
            val c = Comp()
            val id = comps.size
            comps.add(c)
            var sp = 0
            stack[sp++] = start
            label[start] = id
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                c.area++
                c.l = min(c.l, x); c.r = max(c.r, x + 1); c.t = min(c.t, y); c.b = max(c.b, y + 1)
                if (x > 0 && mask[i - 1] && label[i - 1] < 0) { label[i - 1] = id; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && label[i + 1] < 0) { label[i + 1] = id; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && label[i - w] < 0) { label[i - w] = id; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && label[i + w] < 0) { label[i + w] = id; stack[sp++] = i + w }
            }
        }

        // 5. Головна область — найбільша з тих, що заходять у центральну половину рамки
        val cl = w / 4; val cr = w * 3 / 4; val ct = h / 4; val cb = h * 3 / 4
        fun central(c: Comp) = c.r > cl && c.l < cr && c.b > ct && c.t < cb
        val main = comps.filter { central(it) }.maxByOrNull { it.area } ?: return null
        val frac = main.area.toFloat() / n
        if (frac < 0.015f || frac > 0.85f) return null

        // Частини того самого виробу (бантик з прозорими місцями, квітка з листочками)
        var l = main.l; var t = main.t; var r = main.r; var b = main.b
        for (c in comps) {
            if (c === main || c.area < main.area * 0.08f || !central(c)) continue
            l = min(l, c.l); t = min(t, c.t); r = max(r, c.r); b = max(b, c.b)
        }
        // Виріб займає майже всю рамку — обрізати нічого
        if (r - l >= w * 0.95f && b - t >= h * 0.95f) return null
        return intArrayOf(l, t, r, b)
    }

    /**
     * Квадратна область навколо [box] з полями [pad] (частка сторони), у межах w×h.
     * Повертає [left, top, side].
     */
    fun squareAround(box: IntArray, w: Int, h: Int, pad: Float = 0.12f): IntArray {
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        var side = (max(bw, bh) * (1f + 2 * pad)).toInt()
        side = side.coerceIn(8, min(w, h))
        val cx = (box[0] + box[2]) / 2
        val cy = (box[1] + box[3]) / 2
        val left = (cx - side / 2).coerceIn(0, w - side)
        val top = (cy - side / 2).coerceIn(0, h - side)
        return intArrayOf(left, top, side)
    }
}
