package com.iptv.player4k.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.ContextCompat
import com.iptv.player4k.R
import kotlin.math.abs
import kotlin.math.min

/** Artes geradas para itens sem capa/logo: cor única por nome + iniciais ou título. */
object CoverArt {
    private val palette = intArrayOf(
        0xFF7C6CFF.toInt(), 0xFF22D3EE.toInt(), 0xFFFF5C8A.toInt(), 0xFFFFB020.toInt(),
        0xFF4ADE80.toInt(), 0xFFF97316.toInt(), 0xFF60A5FA.toInt(), 0xFFE879F9.toInt(),
        0xFF2DD4BF.toInt(), 0xFFFB7185.toInt(),
    )

    fun colorFor(name: String): Int = palette[abs(name.trim().lowercase().hashCode()) % palette.size]

    fun initials(name: String): String {
        val words = name.replace(Regex("""[\[(].*?[\])]"""), " ")
            .split(Regex("""[\s|:._\-]+""")).filter { w -> w.any { it.isLetterOrDigit() } }
            .filterNot { it.uppercase() in setOf("HD", "FHD", "UHD", "SD", "4K", "BR", "TV") }
        return when {
            words.isEmpty() -> name.take(2).uppercase()
            words.size == 1 -> words[0].take(3).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    fun channel(name: String): Drawable = LogoDrawable(initials(name), colorFor(name))

    fun cover(ctx: Context, title: String, series: Boolean): Drawable =
        CoverDrawable(ctx, title, colorFor(title), series)

    private fun darker(c: Int, f: Float): Int {
        val r = ((c shr 16 and 0xFF) * f).toInt()
        val g = ((c shr 8 and 0xFF) * f).toInt()
        val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private class LogoDrawable(private val text: String, private val color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            rect.set(b)
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, color, darker(color, 0.45f), Shader.TileMode.CLAMP)
            val r = min(rect.width(), rect.height()) * 0.18f
            canvas.drawRoundRect(rect, r, r, paint)
            textPaint.textSize = min(rect.height() * 0.46f, rect.width() / (text.length * 0.62f + 0.4f))
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, rect.centerX(), y, textPaint)
        }

        override fun getIntrinsicWidth() = 160
        override fun getIntrinsicHeight() = 110
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; textPaint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private class CoverDrawable(ctx: Context, private val title: String, private val color: Int, series: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD
        }
        private val icon = ContextCompat.getDrawable(ctx, if (series) R.drawable.ic_series else R.drawable.ic_movie)?.mutate()
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            rect.set(b)
            paint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, darker(color, 0.85f), darker(color, 0.2f), Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
            // ícone grande e translúcido
            icon?.let {
                val s = (rect.width() * 0.42f).toInt()
                val l = (rect.centerX() - s / 2).toInt()
                val t = (rect.top + rect.height() * 0.16f).toInt()
                it.setBounds(l, t, l + s, t + s)
                it.setTint(0x55FFFFFF)
                it.draw(canvas)
            }
            // título
            val w = (rect.width() * 0.86f).toInt().coerceAtLeast(1)
            textPaint.textSize = rect.width() * 0.115f
            val layout = StaticLayout(title, textPaint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
            val lines = min(layout.lineCount, 4)
            val h = layout.getLineBottom(lines - 1)
            canvas.save()
            canvas.translate(rect.left + (rect.width() - w) / 2, rect.top + rect.height() * 0.62f - h / 3f)
            canvas.clipRect(0, 0, w, h)
            layout.draw(canvas)
            canvas.restore()
        }

        override fun getIntrinsicWidth() = 300
        override fun getIntrinsicHeight() = 450
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; textPaint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }
}

/** Selo de classificação indicativa (L, 10, 12, 14, 16, 18) com as cores oficiais. */
object AgeBadge {
    fun bind(tv: android.widget.TextView, age: Int?) {
        if (age == null) {
            tv.visibility = android.view.View.GONE
            return
        }
        val d = tv.resources.displayMetrics.density
        tv.visibility = android.view.View.VISIBLE
        tv.text = com.iptv.player4k.security.AgeRating.label(age)
        tv.setTextColor(if (age in 11..12) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        tv.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(com.iptv.player4k.security.AgeRating.color(age))
            cornerRadius = 4 * d
            if (age >= 18) setStroke((1 * d).toInt(), 0xFFFFFFFF.toInt())
        }
    }
}
