package ua.konditer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Затемнення навколо квадратної рамки + підказки над і під нею. */
class FrameOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Сторона рамки як частка ширини екрана. Така сама частка вирізається з кадру. */
        const val FRACTION = 0.72f
        /** Зсув центру рамки вгору (частка висоти), щоб картка внизу її не закривала. */
        const val SHIFT_UP = 0.08f
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val dp = resources.displayMetrics.density

    private val dim = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp * 3
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = sp(15f); textAlign = Paint.Align.CENTER
        setShadowLayer(dp * 3, 0f, 0f, Color.BLACK)
    }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 210, 90); textSize = sp(14f); textAlign = Paint.Align.CENTER
        isFakeBoldText = true; setShadowLayer(dp * 3, 0f, 0f, Color.BLACK)
    }
    private val rect = RectF()
    private val path = Path()

    var hint: String = "Розмістіть виріб у рамці"
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Попередження під рамкою (розмито, темно…). */
    var warning: String = ""
        set(value) { if (field != value) { field = value; invalidate() } }

    var accent: Int = Color.WHITE
        set(value) { if (field != value) { field = value; border.color = value; invalidate() } }

    /** Центр рамки у координатах цього view (для вирізання кадру). */
    fun frameCenterY(): Float = height / 2f - height * SHIFT_UP

    override fun onDraw(canvas: Canvas) {
        val side = width * FRACTION
        val cy = frameCenterY()
        val l = (width - side) / 2f
        val t = cy - side / 2f
        rect.set(l, t, l + side, t + side)
        val r = dp * 18

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        canvas.drawRoundRect(rect, r, r, border)
        canvas.drawText(hint, width / 2f, t - text.textSize * 0.9f, text)
        if (warning.isNotEmpty()) canvas.drawText(warning, width / 2f, rect.bottom + warn.textSize * 1.6f, warn)
    }
}
