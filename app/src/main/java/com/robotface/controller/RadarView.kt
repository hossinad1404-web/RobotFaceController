package com.robotface.controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple radar-style visualization. Draws a semicircle, the last known
 * sweep angle/distance as a line, and a faint trail of recent readings.
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Reading(val angle: Int, val distanceCm: Int)

    private val history = ArrayDeque<Reading>()
    private val maxHistory = 80
    private val maxRangeCm = 100 // anything farther is drawn at the edge

    private val arcPaint = Paint().apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }
    private val sweepPaint = Paint().apply {
        color = Color.parseColor("#00C2A8")
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val dotPaintFar = Paint().apply {
        color = Color.parseColor("#00C2A8")
        isAntiAlias = true
    }
    private val dotPaintNear = Paint().apply {
        color = Color.parseColor("#E53935")
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }

    fun pushReading(angle: Int, distanceCm: Int) {
        history.addLast(Reading(angle, distanceCm))
        while (history.size > maxHistory) history.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h - 20f
        val radius = (h - 40f).coerceAtMost(w / 2f - 10f)

        // background arcs
        for (frac in listOf(0.33f, 0.66f, 1.0f)) {
            canvas.drawArc(
                cx - radius * frac, cy - radius * frac,
                cx + radius * frac, cy + radius * frac,
                180f, 180f, false, arcPaint
            )
        }

        // baseline
        canvas.drawLine(cx - radius, cy, cx + radius, cy, arcPaint)

        // history dots
        history.forEachIndexed { index, r ->
            val rad = Math.toRadians((180.0 - r.angle))
            val distFrac = (r.distanceCm.coerceIn(0, maxRangeCm)).toFloat() / maxRangeCm
            val dist = if (r.distanceCm <= 0) radius else radius * distFrac
            val px = cx + (dist * cos(rad)).toFloat()
            val py = cy - (dist * sin(rad)).toFloat()
            val paint = if (r.distanceCm in 1..25) dotPaintNear else dotPaintFar
            val alpha = (255 * (index + 1) / history.size.coerceAtLeast(1))
            paint.alpha = alpha.coerceIn(40, 255)
            canvas.drawCircle(px, py, 4f, paint)
        }

        // current sweep line (last reading)
        history.lastOrNull()?.let { last ->
            val rad = Math.toRadians((180.0 - last.angle))
            val ex = cx + (radius * cos(rad)).toFloat()
            val ey = cy - (radius * sin(rad)).toFloat()
            canvas.drawLine(cx, cy, ex, ey, sweepPaint)

            val label = "زاویه: ${last.angle}°   فاصله: ${if (last.distanceCm > 0) last.distanceCm.toString() + " cm" else "---"}"
            canvas.drawText(label, 10f, 30f, textPaint)
        }
    }
}
