package com.lawyye.wired

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** Vertical letter index on the right edge of the app list. Drag along it to jump. */
@SuppressLint("ViewConstructor")
class AlphabetBar(ctx: Context, private val onPick: (String) -> Unit) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var letters: List<String> = emptyList()
    private var active = -1

    var accent: Int = Palette.TEXT

    init {
        paint.typeface = Palette.mono
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = ctx.dp(10f)
    }

    fun setLetters(l: List<String>) {
        letters = l
        visibility = if (l.size > 1) VISIBLE else INVISIBLE
        invalidate()
    }

    private fun step(): Float = (height - paddingTop - paddingBottom).toFloat() / letters.size.coerceAtLeast(1)

    override fun onDraw(canvas: Canvas) {
        if (letters.isEmpty()) return
        val st = step()
        paint.textSize = minOf(context.dp(10f), st * 0.85f)
        val off = (paint.descent() + paint.ascent()) / 2f
        for (i in letters.indices) {
            paint.color = if (i == active) accent else Palette.DIM
            val y = paddingTop + st * i + st / 2f - off
            canvas.drawText(letters[i], width / 2f, y, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = ((event.y - paddingTop) / step()).toInt().coerceIn(0, letters.size - 1)
                if (i != active) {
                    active = i
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPick(letters[i])
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
            }
        }
        return true
    }
}
