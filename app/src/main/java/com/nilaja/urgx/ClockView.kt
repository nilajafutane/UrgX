package com.nilaja.urgx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class ClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paintFace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F0F0F0")
        style = Paint.Style.FILL
    }
    private val paintRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#E0E0E0")
        style       = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val paintHour = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#E91E63")
        strokeWidth = 10f
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }
    private val paintMin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#E91E63")
        strokeWidth = 7f
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }
    private val paintSec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#E91E63")
        strokeWidth = 2.5f
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }
    private val paintCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E91E63")
        style = Paint.Style.FILL
    }
    private val paintTickHour = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#CCCCCC")
        strokeWidth = 3f
        strokeCap   = Paint.Cap.ROUND
        style       = Paint.Style.STROKE
    }
    private val paintTickMin = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color       = Color.parseColor("#E8E8E8")
        strokeWidth = 1.5f
        style       = Paint.Style.STROKE
    }

    private val handler  = Handler(Looper.getMainLooper())
    private val ticker   = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 1000) }
    }

    override fun onAttachedToWindow()  { super.onAttachedToWindow();  handler.post(ticker) }
    override fun onDetachedFromWindow(){ super.onDetachedFromWindow(); handler.removeCallbacks(ticker) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx     = width  / 2f
        val cy     = height / 2f
        val radius = minOf(cx, cy) * 0.88f

        // White clock face
        canvas.drawCircle(cx, cy, radius, paintFace)
        canvas.drawCircle(cx, cy, radius, paintRim)

        // Tick marks
        for (i in 0..59) {
            val angle      = Math.toRadians((i * 6 - 90).toDouble())
            val isHour     = i % 5 == 0
            val outer      = radius * 0.93f
            val inner      = if (isHour) radius * 0.80f else radius * 0.88f
            val paint      = if (isHour) paintTickHour else paintTickMin
            canvas.drawLine(
                cx + (inner * cos(angle)).toFloat(),
                cy + (inner * sin(angle)).toFloat(),
                cx + (outer * cos(angle)).toFloat(),
                cy + (outer * sin(angle)).toFloat(),
                paint
            )
        }

        // Time
        val cal  = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR)
        val min  = cal.get(java.util.Calendar.MINUTE)
        val sec  = cal.get(java.util.Calendar.SECOND)

        // Hour hand
        val hourAngle = Math.toRadians((hour * 30 + min * 0.5) - 90)
        canvas.drawLine(
            cx - (radius * 0.12f * cos(hourAngle)).toFloat(),
            cy - (radius * 0.12f * sin(hourAngle)).toFloat(),
            cx + (radius * 0.50f * cos(hourAngle)).toFloat(),
            cy + (radius * 0.50f * sin(hourAngle)).toFloat(),
            paintHour
        )

        // Minute hand
        val minAngle = Math.toRadians(min * 6.0 - 90)
        canvas.drawLine(
            cx - (radius * 0.12f * cos(minAngle)).toFloat(),
            cy - (radius * 0.12f * sin(minAngle)).toFloat(),
            cx + (radius * 0.70f * cos(minAngle)).toFloat(),
            cy + (radius * 0.70f * sin(minAngle)).toFloat(),
            paintMin
        )

        // Second hand
        val secAngle = Math.toRadians(sec * 6.0 - 90)
        canvas.drawLine(
            cx - (radius * 0.18f * cos(secAngle)).toFloat(),
            cy - (radius * 0.18f * sin(secAngle)).toFloat(),
            cx + (radius * 0.78f * cos(secAngle)).toFloat(),
            cy + (radius * 0.78f * sin(secAngle)).toFloat(),
            paintSec
        )

        // Center dot
        canvas.drawCircle(cx, cy, 8f, paintCenter)
    }
}