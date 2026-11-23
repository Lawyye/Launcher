package com.lawyye.wired

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Static HUD: crosshair marks and small square dots scattered on a grid. */
class HudFrameView(ctx: Context) : View(ctx) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    var color: Int = Palette.TEXT

    init {
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = ctx.dp(1.2f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val l = paddingLeft + 14f * d
        val t = paddingTop + 14f * d
        val r = width - paddingRight - 14f * d
        val b = height - paddingBottom - 14f * d
        if (r <= l || b <= t) return
        stroke.color = Palette.withAlpha(color, 150)
        fill.color = Palette.withAlpha(color, 120)
        val s = 6f * d
        val q = 1.6f * d

        fun plus(x: Float, y: Float) {
            canvas.drawLine(x - s, y, x + s, y, stroke)
            canvas.drawLine(x, y - s, x, y + s, stroke)
        }

        fun dot(x: Float, y: Float) {
            canvas.drawRect(x - q, y - q, x + q, y + q, fill)
        }

        val cx = (l + r) / 2f
        val w = r - l
        val h = b - t

        plus(l, t + 12f * d)
        plus(cx, t + 12f * d)
        plus(r, t + 22f * d)
        dot(r, t + 4f * d)

        val row1 = t + h * 0.43f
        dot(l + w * 0.03f, row1)
        plus(l + w * 0.24f, row1)
        dot(l + w * 0.29f, row1)
        plus(l + w * 0.70f, row1)
        dot(l + w * 0.84f, row1)
        dot(l + w * 0.90f, row1)

        val row2 = t + h * 0.62f
        dot(l + w * 0.29f, row2)
        dot(l + w * 0.70f, row2)

        plus(cx, b - 6f * d)
        dot(l + w * 0.02f, b - 2f * d)
        dot(l + w * 0.06f, b - 2f * d)
        plus(r - w * 0.05f, b - 2f * d)
        dot(r, b - 2f * d)
        dot(r, b - 14f * d)
    }
}
