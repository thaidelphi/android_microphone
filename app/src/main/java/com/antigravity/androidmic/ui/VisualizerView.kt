package com.antigravity.androidmic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentRms: Float = 0f
    private var peakLevel: Float = 0f
    private var peakHold: Float = 0f
    private var currentDb: Float = -80f

    // Smoothed values for animation
    private var displayRms: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#121A28")
        style = Paint.Style.FILL
    }

    private val segmentBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B2638")
        style = Paint.Style.FILL
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    private val dbValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 34f
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val barRect = RectF()
    private val cornerRadius = 12f
    private val totalSegments = 28

    fun updateAudioLevel(peak: Float, rms: Float, db: Float) {
        this.currentRms = rms.coerceIn(0f, 1f)
        this.peakLevel = peak.coerceIn(0f, 1f)
        this.currentDb = db

        if (this.peakLevel > this.peakHold) {
            this.peakHold = this.peakLevel
        }

        postInvalidateOnAnimation()
    }

    fun reset() {
        currentRms = 0f
        peakLevel = 0f
        peakHold = 0f
        currentDb = -80f
        displayRms = 0f
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        // Smooth decay logic
        displayRms += (currentRms - displayRms) * 0.35f
        if (displayRms < 0.002f) displayRms = 0f

        peakHold -= 0.008f
        if (peakHold < displayRms) peakHold = displayRms
        if (peakHold < 0f) peakHold = 0f

        // Draw card background
        barRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, bgPaint)

        // Draw segmented LED VU Meter
        val meterTop = 60f
        val meterBottom = h - 50f
        val meterLeft = 30f
        val meterRight = w - 30f
        val meterWidth = meterRight - meterLeft
        val gap = 6f
        val segmentWidth = (meterWidth - (totalSegments - 1) * gap) / totalSegments

        val activeCount = (displayRms * totalSegments).toInt().coerceIn(0, totalSegments)
        val peakIndex = (peakHold * (totalSegments - 1)).toInt().coerceIn(0, totalSegments - 1)

        for (i in 0 until totalSegments) {
            val left = meterLeft + i * (segmentWidth + gap)
            val right = left + segmentWidth
            val rect = RectF(left, meterTop, right, meterBottom)

            // Background of segment
            canvas.drawRoundRect(rect, 4f, 4f, segmentBgPaint)

            if (i < activeCount) {
                // Determine color based on index
                val ratio = i.toFloat() / totalSegments
                val color = when {
                    ratio < 0.65f -> Color.parseColor("#10B981") // Green
                    ratio < 0.85f -> Color.parseColor("#F59E0B") // Amber
                    else -> Color.parseColor("#EF4444")          // Red
                }
                activePaint.color = color
                canvas.drawRoundRect(rect, 4f, 4f, activePaint)
            }
        }

        // Draw peak hold cursor
        if (peakHold > 0.02f) {
            val peakLeft = meterLeft + peakIndex * (segmentWidth + gap)
            val peakRight = peakLeft + segmentWidth
            val peakRect = RectF(peakLeft, meterTop - 2f, peakRight, meterBottom + 2f)
            peakPaint.color = Color.parseColor("#00E5FF")
            canvas.drawRoundRect(peakRect, 4f, 4f, peakPaint)
        }

        // Draw DB value top right
        val dbText = if (currentDb <= -79f) "SILENT" else String.format("%.1f dB", currentDb)
        canvas.drawText(dbText, w - 35f, 42f, dbValuePaint)

        // Draw Label top left
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("AUDIO VU LEVEL", 35f, 42f, textPaint)

        // Draw dB scale markers bottom
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("-60dB", meterLeft, h - 18f, textPaint)
        canvas.drawText("-30dB", meterLeft + meterWidth * 0.33f, h - 18f, textPaint)
        canvas.drawText("-12dB", meterLeft + meterWidth * 0.66f, h - 18f, textPaint)
        canvas.drawText("0dB", meterRight, h - 18f, textPaint)

        // Continue animation if decaying
        if (displayRms > 0.001f || peakHold > 0.001f) {
            postInvalidateOnAnimation()
        }
    }
}
