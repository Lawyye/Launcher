package com.lawyye.wired

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.util.Locale
import kotlin.random.Random

/** Faint, slowly scrolling pseudo-code behind the home screen. */
class DataStreamView(ctx: Context) : View(ctx) {

    private class Line(val text: String, val indent: Int, val alpha: Int, val hot: Boolean)

    var color: Int = Palette.TEXT
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = ArrayList<Line>()
    private var offset = 0f
    private var running = false

    private val lineH: Float
        get() = paint.textSize * 1.6f

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            offset += context.dp(0.6f)
            while (offset >= lineH) {
                offset -= lineH
                if (lines.isNotEmpty()) lines.removeAt(0)
                lines.add(gen())
            }
            invalidate()
            postDelayed(this, 60L)
        }
    }

    init {
        paint.typeface = Palette.mono
        paint.textSize = ctx.dp(10f)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun resume() {
        if (running) return
        running = true
        removeCallbacks(frame)
        postDelayed(frame, 60L)
    }

    fun pause() {
        running = false
        removeCallbacks(frame)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val need = (h / lineH).toInt() + 3
        while (lines.size < need) lines.add(gen())
    }

    override fun onDraw(canvas: Canvas) {
        val x0 = context.dp(14f)
        val ch = paint.measureText("0")
        for (i in lines.indices) {
            val l = lines[i]
            val y = i * lineH - offset + paint.textSize
            paint.color = Palette.withAlpha(if (l.hot) color else Palette.TEXT, l.alpha)
            canvas.drawText(l.text, x0 + l.indent * ch * 2f, y, paint)
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    private fun hex(n: Int): String = String.format(Locale.US, "%02X", n)

    private fun gen(): Line {
        val r = Random
        val w = WORDS[r.nextInt(WORDS.size)]
        val text = when (r.nextInt(14)) {
            0 -> "if (layer.depth > ${r.nextInt(2, 13)}) navi.sync(\"$w\");"
            1 -> String.format(Locale.US, "0x%04X  ", r.nextInt(65536)) +
                (0 until 8).joinToString(" ") { hex(r.nextInt(256)) }
            2 -> "wired.connect(\"$w\", port=${r.nextInt(1024, 65535)});"
            3 -> "protocol_7::handshake(node_${r.nextInt(100, 999)}) -> OK"
            4 -> "for (i = 0; i < ${r.nextInt(8, 256)}; i++) psyche[i] ^= 0x${hex(r.nextInt(256))};"
            5 -> String.format(Locale.US, "// layer:%02d  %s", r.nextInt(1, 14), w)
            6 -> String.format(Locale.US, "memory.dump(0x%08X, %d);", r.nextInt(), r.nextInt(16, 4096))
            7 -> "user[${r.nextInt(0, 99)}].presence = ${if (r.nextBoolean()) "true" else "null"};"
            8 -> ".node-$w { opacity: 0.${r.nextInt(1, 9)}; flex-direction: column; }"
            9 -> "assert(reality != null); // $w"
            10 -> "kernel: signal ${r.nextInt(1, 32)} from $w"
            11 -> "ping ${r.nextInt(1, 255)}.${r.nextInt(255)}.${r.nextInt(255)}.${r.nextInt(255)} ... ${r.nextInt(3, 400)}ms"
            12 -> "> " + PHRASES[r.nextInt(PHRASES.size)]
            else -> "}"
        }
        val hot = r.nextInt(9) == 0
        return Line(text, r.nextInt(0, 4), if (hot) r.nextInt(70, 110) else r.nextInt(22, 48), hot)
    }

    companion object {
        private val WORDS = arrayOf(
            "navi", "wired", "psyche", "ghost", "echo", "signal", "void",
            "proxy", "daemon", "layer", "node", "mirror", "static", "dream",
        )
        private val PHRASES = arrayOf(
            "is anyone there?", "link established", "identity: unverified",
            "signal lost... retrying", "you are being observed", "memory is not reliable",
            "the network remembers", "who is typing?", "sync complete", "do not disconnect",
        )
    }
}
