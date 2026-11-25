package com.iptv.player4k.ui.epg

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.iptv.player4k.R
import com.iptv.player4k.data.Channel
import com.iptv.player4k.epg.EpgData
import com.iptv.player4k.epg.Programme
import com.iptv.player4k.util.Fmt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Grade de programação: canais na vertical, tempo na horizontal.
 * Desenhada direto no Canvas (rápida com milhares de canais) e navegável por toque e D-pad.
 */
class EpgGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onFocus(channel: Channel, programme: Programme?)
        fun onActivate(channel: Channel, programme: Programme?)
    }

    var listener: Listener? = null

    private var channels: List<Channel> = emptyList()
    private var epg: EpgData? = null

    private val dp = resources.displayMetrics.density
    private val wide = resources.configuration.screenWidthDp >= 600
    private val rowH = (if (wide) 60 else 62) * dp
    private val headerH = 34 * dp
    private val chanW = (if (wide) 270 else 120) * dp
    private val pxPerMin = (if (wide) 5.5f else 4.2f) * dp
    private val gap = 2 * dp
    private val radius = 5 * dp

    private var origin = 0L
    private var endTime = 0L
    private var sx = 0f
    private var sy = 0f
    private var focusRow = 0
    private var focusTime = 0L
    private var initialized = false
    private var lastNotified: Pair<String, Long>? = null

    private fun c(id: Int) = ContextCompat.getColor(context, id)
    private val bg = c(R.color.bg)
    private val colSurface = c(R.color.surface)
    private val colFocusRow = c(R.color.surface_focus)
    private val colPrimary = c(R.color.primary)
    private val colLive = c(R.color.epg_live)
    private val colPast = c(R.color.epg_past)
    private val colFuture = c(R.color.epg_future)
    private val colRed = c(R.color.live_red)
    private val colNow = c(R.color.epg_now)
    private val colCell = c(R.color.epg_cell)
    private val colCellPast = c(R.color.epg_cell_past)
    private val colRowFocus = c(R.color.epg_row_focus)
    private val colCellFocus = c(R.color.epg_cell_focus)
    private val colTextDark = c(R.color.epg_text_dark)
    private val dateFmt = java.text.SimpleDateFormat("EEE, d 'de' MMM  HH:mm", java.util.Locale.getDefault())

    /** canal tocando na pré-visualização (marcado com ▶) */
    var playingUrl: String? = null
        set(v) { field = v; invalidate() }
    private val colText = c(R.color.text_primary)
    private val colText2 = c(R.color.text_secondary)
    private val colDivider = c(R.color.divider)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (if (wide) 16f else 13.5f) * dp }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * dp }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f * dp; typeface = Typeface.DEFAULT_BOLD }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (wide) 16f else 10.5f) * dp; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
    }
    private val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15 * dp; color = c(R.color.text_secondary); typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    private val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10 * dp; typeface = Typeface.DEFAULT_BOLD; color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    private val logos = LruCache<String, Bitmap>(160)
    private val arts = HashMap<String, android.graphics.drawable.Drawable>()
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()

    private val scroller = OverScroller(context)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollTo(sx + dx, sy + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(sx.toInt(), sy.toInt(), -vx.toInt(), -vy.toInt(), 0, maxX().toInt(), 0, maxY().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }
    })

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        resetTimeline()
    }

    // ---------------- dados ----------------

    fun setData(list: List<Channel>, data: EpgData?) {
        val keep = channels.getOrNull(focusRow)?.url
        channels = list
        epg = data
        focusRow = list.indexOfFirst { it.url == keep }.takeIf { it >= 0 } ?: 0
        if (keep != null && list.getOrNull(focusRow)?.url != keep) sy = 0f
        scrollTo(sx, sy)
        notifyFocus(force = true)
        invalidate()
    }

    private fun resetTimeline() {
        val now = System.currentTimeMillis()
        origin = floorHalfHour(now - 2 * HOUR)
        endTime = origin + 28 * HOUR
        focusTime = now
    }

    fun goToNow() {
        val now = System.currentTimeMillis()
        if (now > endTime - 4 * HOUR) resetTimeline()
        focusTime = now
        scrollTo(timeToX(now) - 20 * pxPerMin, sy)
        notifyFocus(force = true)
    }

    private fun floorHalfHour(t: Long) = t - (t % (30 * 60_000L))
    private fun timeToX(t: Long) = (t - origin) / 60_000f * pxPerMin
    private fun xToTime(x: Float) = origin + (x / pxPerMin * 60_000f).toLong()
    private fun gridW() = width - chanW
    private fun gridH() = height - headerH
    private fun maxX() = max(0f, timeToX(endTime) - gridW())
    private fun maxY() = max(0f, channels.size * rowH - gridH())

    private fun scrollTo(x: Float, y: Float) {
        sx = x.coerceIn(0f, maxX())
        sy = y.coerceIn(0f, maxY())
        invalidate()
    }

    private fun programmes(row: Int): List<Programme> =
        channels.getOrNull(row)?.let { epg?.programmes(it) } ?: emptyList()

    private fun focusedProgramme(): Programme? {
        val list = programmes(focusRow)
        return EpgData.at(list, focusTime)?.let { list[it] }
    }

    private fun notifyFocus(force: Boolean = false) {
        val ch = channels.getOrNull(focusRow) ?: return
        val p = focusedProgramme()
        val key = ch.url to (p?.start ?: -1L)
        if (!force && key == lastNotified) return
        lastNotified = key
        listener?.onFocus(ch, p)
    }

    // ---------------- ciclo de vida ----------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(ticker, 30_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0) {
            initialized = true
            goToNow()
        } else scrollTo(sx, sy)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.currX.toFloat().coerceIn(0f, maxX())
            sy = scroller.currY.toFloat().coerceIn(0f, maxY())
            postInvalidateOnAnimation()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    // ---------------- desenho ----------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val first = (sy / rowH).toInt().coerceAtLeast(0)
        val last = min(channels.lastIndex, ((sy + gridH()) / rowH).toInt())
        val tStart = xToTime(sx)
        val tEnd = xToTime(sx + gridW())
        val focused = focusedProgramme()

        canvas.save()
        canvas.clipRect(chanW, headerH, width.toFloat(), height.toFloat())
        for (r in first..last) {
            val top = headerH + r * rowH - sy
            val list = programmes(r)
            if (list.isEmpty()) {
                drawBlock(canvas, null, chanW + gap, width - gap, top, now, r == focusRow, r == focusRow, getEmptyLabel())
                continue
            }
            var i = EpgData.firstEndingAfter(list, tStart)
            while (i < list.size && list[i].start < tEnd) {
                val p = list[i]
                val left = chanW + timeToX(max(p.start, origin)) - sx + gap
                val right = chanW + timeToX(min(p.stop, endTime)) - sx - gap
                drawBlock(canvas, p, left, right, top, now, r == focusRow && p === focused, r == focusRow, p.title)
                i++
            }
        }
        // linha do "agora"
        val nowX = chanW + timeToX(now) - sx
        if (nowX >= chanW && nowX <= width) {
            fill.color = colNow
            canvas.drawRect(nowX - 1 * dp, headerH, nowX + 1 * dp, height.toFloat(), fill)
        }
        canvas.restore()

        drawHeader(canvas, tStart, tEnd, nowX)
        drawChannels(canvas, first, last)
    }

    private fun getEmptyLabel() = context.getString(R.string.epg_no_info)

    /** Célula de programa: focada = branca com texto escuro; linha focada = mais clara. */
    private fun drawBlock(
        canvas: Canvas, p: Programme?, left: Float, right: Float, top: Float,
        now: Long, isFocus: Boolean, rowFocused: Boolean, label: String,
    ) {
        if (right - left < 2 * dp) return
        val past = p != null && p.stop <= now
        rect.set(left, top + gap, right, top + rowH - gap)
        fill.color = when {
            isFocus -> colCellFocus
            rowFocused -> colRowFocus
            past || p == null -> colCellPast
            else -> colCell
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        // texto "grudado" na borda esquerda visível
        val textLeft = max(left, chanW) + 14 * dp
        val avail = right - textLeft - 8 * dp
        if (avail < 14 * dp) return
        titlePaint.color = when {
            isFocus -> colTextDark
            past && !rowFocused -> colText2
            else -> colText
        }
        val title = TextUtils.ellipsize(label, titlePaint, avail, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, textLeft, top + rowH / 2 + titlePaint.textSize * 0.36f, titlePaint)
    }

    private fun drawHeader(canvas: Canvas, tStart: Long, tEnd: Long, nowX: Float) {
        fill.color = bg
        canvas.drawRect(0f, 0f, width.toFloat(), headerH, fill)
        canvas.save()
        canvas.clipRect(chanW, 0f, width.toFloat(), headerH)
        var t = floorHalfHour(tStart)
        while (t <= tEnd) {
            val x = chanW + timeToX(t) - sx
            fill.color = colDivider
            canvas.drawRect(x, headerH - 10 * dp, x + 1 * dp, headerH, fill)
            headerPaint.color = colText2
            canvas.drawText(Fmt.time(t), x + 6 * dp, headerH - 12 * dp, headerPaint)
            t += 30 * 60_000L
        }
        if (nowX >= chanW && nowX <= width) {
            fill.color = colNow
            canvas.drawCircle(nowX, headerH - 4 * dp, 4 * dp, fill)
        }
        canvas.restore()
        // canto: "qua, 25 de nov  16:37"
        fill.color = bg
        canvas.drawRect(0f, 0f, chanW, headerH, fill)
        headerPaint.color = colNow
        val day = TextUtils.ellipsize(dateFmt.format(java.util.Date()), headerPaint, chanW - 16 * dp, TextUtils.TruncateAt.END)
        canvas.drawText(day, 0, day.length, 10 * dp, headerH - 12 * dp, headerPaint)
        fill.color = colNow
        canvas.drawRect(0f, headerH - 1 * dp, width.toFloat(), headerH, fill.apply { alpha = 90 })
        fill.alpha = 255
    }

    private fun drawChannels(canvas: Canvas, first: Int, last: Int) {
        canvas.save()
        canvas.clipRect(0f, headerH, chanW, height.toFloat())
        fill.color = bg
        canvas.drawRect(0f, headerH, chanW, height.toFloat(), fill)
        for (r in first..last) {
            val ch = channels[r]
            val top = headerH + r * rowH - sy
            val focusedRow = r == focusRow
            val playing = ch.url == playingUrl
            namePaint.color = if (focusedRow) colNow else colText
            numPaint.color = if (focusedRow) colNow else colText2
            if (wide) {
                val num = (r + 1).toString()
                canvas.drawText(num, 10 * dp, top + rowH / 2 + 5 * dp, numPaint)
                val lx = 52 * dp
                drawLogo(canvas, ch, lx, top + 9 * dp, 62 * dp, rowH - 18 * dp)
                val nx = lx + 74 * dp
                val maxW = chanW - nx - (if (playing) 28 else 8) * dp
                val name = TextUtils.ellipsize(ch.name, namePaint, maxW, TextUtils.TruncateAt.END)
                canvas.drawText(name, 0, name.length, nx, top + rowH / 2 + 6 * dp, namePaint)
                if (playing) drawPlayMark(canvas, chanW - 20 * dp, top + rowH / 2)
            } else {
                drawLogo(canvas, ch, 8 * dp, top + 6 * dp, chanW - 16 * dp, 30 * dp)
                val name = TextUtils.ellipsize(ch.name, namePaint, chanW - 12 * dp, TextUtils.TruncateAt.END)
                val w = namePaint.measureText(name, 0, name.length)
                canvas.drawText(name, 0, name.length, (chanW - w) / 2, top + rowH - 10 * dp, namePaint)
                if (playing) drawPlayMark(canvas, chanW - 10 * dp, top + 14 * dp)
            }
        }
        canvas.restore()
    }

    private val playPath = android.graphics.Path()

    /** ▶ ao lado do canal que está tocando na pré-visualização. */
    private fun drawPlayMark(canvas: Canvas, cx: Float, cy: Float) {
        val s = 7 * dp
        playPath.reset()
        playPath.moveTo(cx - s * 0.6f, cy - s)
        playPath.lineTo(cx + s, cy)
        playPath.lineTo(cx - s * 0.6f, cy + s)
        playPath.close()
        fill.color = colNow
        canvas.drawPath(playPath, fill)
    }

    private fun drawLogo(canvas: Canvas, ch: Channel, x: Float, y: Float, w: Float, h: Float) {
        val url = ch.logo ?: epg?.logo(ch)
        val bmp = logo(url)
        if (bmp == null) {
            // sem logo: arte com as iniciais do canal
            val aw = min(w, h * 1.5f)
            val art = arts.getOrPut(ch.name) { com.iptv.player4k.util.CoverArt.channel(ch.name) }
            art.setBounds((x + (w - aw) / 2).toInt(), y.toInt(), (x + (w + aw) / 2).toInt(), (y + h).toInt())
            art.draw(canvas)
            return
        }
        val scale = min(w / bmp.width, h / bmp.height)
        val bw = bmp.width * scale
        val bh = bmp.height * scale
        rect.set(x + (w - bw) / 2, y + (h - bh) / 2, x + (w + bw) / 2, y + (h + bh) / 2)
        canvas.drawBitmap(bmp, null, rect, fill.apply { color = 0xFFFFFFFF.toInt() })
    }

    private fun logo(url: String?): Bitmap? {
        if (url.isNullOrBlank() || url in failed) return null
        logos.get(url)?.let { return it }
        if (pending.add(url)) {
            val req = ImageRequest.Builder(context)
                .data(url)
                .size((96 * dp).toInt())
                .allowHardware(false)
                .target(
                    onSuccess = { d ->
                        pending.remove(url)
                        runCatching { logos.put(url, d.toBitmap()) }
                        invalidate()
                    },
                    onError = { pending.remove(url); failed += url },
                )
                .build()
            context.imageLoader.enqueue(req)
        }
        return null
    }

    // ---------------- interação ----------------

    private fun tap(x: Float, y: Float) {
        if (y < headerH || channels.isEmpty()) return
        val row = ((y - headerH + sy) / rowH).toInt()
        if (row !in channels.indices) return
        if (x < chanW) {
            focusRow = row
            focusTime = System.currentTimeMillis()
            invalidate()
            notifyFocus(force = true)
            listener?.onActivate(channels[row], focusedProgramme())
            return
        }
        val t = xToTime(x - chanW + sx)
        val list = programmes(row)
        val hit = EpgData.at(list, t)?.let { list[it] }
        val current = focusedProgramme()
        if (row == focusRow && (hit != null && hit === current)) {
            listener?.onActivate(channels[row], hit)
            return
        }
        focusRow = row
        focusTime = hit?.let { max(it.start, origin) + 1 } ?: t
        invalidate()
        notifyFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        val visStart = xToTime(sx)
        val visEnd = xToTime(sx + gridW())
        if (focusTime < visStart || focusTime > visEnd) focusTime = visStart + 60_000
        val list = programmes(focusRow)
        val idx = EpgData.at(list, focusTime)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                focusTime = if (idx != null && idx + 1 < list.size) list[idx + 1].start + 1
                else min(endTime - 1, focusTime + 30 * 60_000L)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val prev = if (idx != null) idx - 1 else EpgData.firstEndingAfter(list, focusTime) - 1
                if (prev >= 0 && list[prev].stop > origin) focusTime = max(list[prev].start, origin) + 1
                else if (focusTime - 30 * 60_000L > origin) focusTime -= 30 * 60_000L
                else return false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (focusRow < channels.lastIndex) focusRow++ else return false
            KeyEvent.KEYCODE_DPAD_UP -> if (focusRow > 0) focusRow-- else return false
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                focusRow = min(channels.lastIndex, focusRow + (gridH() / rowH).toInt())
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP ->
                focusRow = max(0, focusRow - (gridH() / rowH).toInt())
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                listener?.onActivate(channels[focusRow], focusedProgramme())
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        ensureVisible()
        notifyFocus()
        return true
    }

    private fun ensureVisible() {
        var ny = sy
        val top = focusRow * rowH
        if (top < ny) ny = top
        if (top + rowH > ny + gridH()) ny = top + rowH - gridH()

        var nx = sx
        val p = focusedProgramme()
        val s = timeToX(max(p?.start ?: focusTime, origin))
        val e = timeToX(min(p?.stop ?: (focusTime + 30 * 60_000L), endTime))
        val pad = 12 * dp
        if (s < nx) nx = s - pad
        else if (e > nx + gridW()) nx = if (e - s > gridW() - pad) s - pad else e - gridW() + pad
        if (abs(nx - sx) > 0.5f || abs(ny - sy) > 0.5f) scrollTo(nx, ny) else invalidate()
    }

    companion object {
        private const val HOUR = 60 * 60_000L
    }
}
