package ua.konditer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Затемнення навколо квадратної рамки, в яку треба помістити виріб. */
class FrameOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Сторона рамки як частка ширини екрана. Така сама частка вирізається з кадру. */
        const val FRACTION = 0.75f
    }

    private val dim = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.scaledDensity * 15
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val path = Path()

    var hint: String = "Розмістіть виріб у рамці"
        set(value) { field = value; invalidate() }

    var accent: Int = Color.WHITE
        set(value) { field = value; border.color = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val side = width * FRACTION
        val l = (width - side) / 2f
        val t = (height - side) / 2f
        rect.set(l, t, l + side, t + side)
        val r = resources.displayMetrics.density * 18

        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.drawPath(path, dim)
        canvas.drawRoundRect(rect, r, r, border)
        canvas.drawText(hint, width / 2f, t - text.textSize, text)
    }
}
