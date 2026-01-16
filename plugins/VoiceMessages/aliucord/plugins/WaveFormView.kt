package com.aliucord.plugins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import android.view.View
import java.nio.charset.StandardCharsets
import kotlin.math.min

class WaveFormView(context: Context) : View(context) {

    val waves: MutableList<Int> = mutableListOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setWaveform(encoded: String) {
        waves.clear()

        val decoded = Base64.decode(encoded, Base64.DEFAULT)
        val text = String(decoded, StandardCharsets.UTF_8)

        text.split(",").forEach {
            waves.add(it.toInt())
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (waves.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / waves.size

        paint.strokeWidth = barWidth * 0.6f

        waves.forEachIndexed { index, value ->
            val barHeight = min(height, value.toFloat())
            val x = index * barWidth
            canvas.drawLine(
                x,
                height / 2 - barHeight / 2,
                x,
                height / 2 + barHeight / 2,
                paint
            )
        }
    }
}
