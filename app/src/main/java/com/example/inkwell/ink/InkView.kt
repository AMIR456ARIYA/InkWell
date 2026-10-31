package com.example.inkwell.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Low-memory ink canvas.
 *  - Strokes are stored as float arrays (vector), never as per-stroke bitmaps.
 *  - One screen-sized RGB_565 cache holds the rendered page (~8 MB at 2560x1600).
 *  - Only strokes intersecting the viewport are drawn when the cache is rebuilt.
 *  - During pan/zoom the cache bitmap is just transformed (no re-render).
 */
class InkView(ctx: Context) : View(ctx) {
    companion object {
        const val PAGE_W = 1240f
        const val PAGE_H = 1754f
        private const val MAX_UNDO = 100
        private const val BACKDROP = 0xFF9AA0A6.toInt()
        private const val MIN_SCALE = 0.4f
        private const val MAX_SCALE = 6f
    }

    private sealed class Op {
        class Add(val s: Stroke) : Op()
        class Remove(val items: List<Pair<Int, Stroke>>) : Op()
    }

    var strokes: MutableList<Stroke> = mutableListOf()
        private set
    var template = 0 // 0 blank, 1 lined, 2 grid
        set(v) { field = v; cacheDirty = true; invalidate() }
    var tool = Tool.PEN
    var color = Color.BLACK
    var penWidth = 3.5f
    var fingerDraws = false
    var onChanged: (() -> Unit)? = null

    // view transform: screen = scale * page + (tx, ty)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cScale = 1f
    private var cTx = 0f
    private var cTy = 0f
    private var cacheDirty = true
    private var gesturing = false

    private val undoStack = ArrayDeque<Op>()
    private val redoStack = ArrayDeque<Op>()

    // live stroke buffer
    private var buf = FloatArray(3 * 512)
    private var bufN = 0
    private var liveTool = Tool.PEN
    private var liveColor = Color.BLACK
    private var liveWidth = 3.5f
    private val pendingErase = ArrayList<Pair<Int, Stroke>>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val gridPaint = Paint().apply { color = 0xFFD0D7E2.toInt(); strokeWidth = 1.2f }

    private var lastX = 0f
    private var lastY = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            tx = d.focusX - (d.focusX - tx) * ns / scale
            ty = d.focusY - (d.focusY - ty) * ns / scale
            scale = ns
            clampPan()
            invalidate()
            return true
        }
    })

    // ---------- public API ----------

    fun setPage(list: MutableList<Stroke>, tpl: Int) {
        strokes = list
        undoStack.clear(); redoStack.clear()
        template = tpl
        fitToWidth()
        cacheDirty = true
        invalidate()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.remove(op.s)
            is Op.Remove -> for ((i, s) in op.items.asReversed()) strokes.add(i.coerceAtMost(strokes.size), s)
        }
        redoStack.addLast(op)
        changed()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        when (op) {
            is Op.Add -> strokes.add(op.s)
            is Op.Remove -> for ((_, s) in op.items) strokes.remove(s)
        }
        undoStack.addLast(op)
        changed()
    }

    fun fitToWidth() {
        if (width == 0) return
        scale = ((width - 32f) / PAGE_W).coerceIn(MIN_SCALE, MAX_SCALE)
        tx = (width - PAGE_W * scale) / 2f
        ty = 16f
        clampPan()
    }

    // ---------- layout / cache ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cache?.recycle()
        if (w > 0 && h > 0) {
            cache = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            cacheCanvas = Canvas(cache!!)
        }
        if (oldw == 0) fitToWidth() else clampPan()
        cacheDirty = true
    }

    private fun clampPan() {
        val pw = PAGE_W * scale
        val ph = PAGE_H * scale
        tx = if (pw <= width) (width - pw) / 2f else tx.coerceIn(width - pw - 24f, 24f)
        ty = if (ph <= height) (height - ph) / 2f else ty.coerceIn(height - ph - 24f, 24f)
    }

    private fun renderCache() {
        val c = cacheCanvas ?: return
        c.drawColor(BACKDROP)
        c.save()
        c.translate(tx, ty)
        c.scale(scale, scale)
        c.clipRect(0f, 0f, PAGE_W, PAGE_H)
        c.drawColor(Color.WHITE)
        drawTemplate(c)
        val vl = -tx / scale; val vt = -ty / scale
        val vr = (width - tx) / scale; val vb = (height - ty) / scale
        for (s in strokes) {
            val b = s.bounds
            if (b.right < vl || b.left > vr || b.bottom < vt || b.top > vb) continue
            drawStroke(c, s)
        }
        c.restore()
        cScale = scale; cTx = tx; cTy = ty
        cacheDirty = false
    }

    private fun drawTemplate(c: Canvas) {
        if (template == 0) return
        var y = 120f
        while (y < PAGE_H) { c.drawLine(0f, y, PAGE_W, y, gridPaint); y += 42f }
        if (template == 2) {
            var x = 42f
            while (x < PAGE_W) { c.drawLine(x, 0f, x, PAGE_H, gridPaint); x += 42f }
        }
    }

    override fun onDraw(c: Canvas) {
        val bmp = cache ?: return
        if (cacheDirty && !gesturing) renderCache()
        c.drawColor(BACKDROP)
        val k = scale / cScale
        c.save()
        c.translate(tx - k * cTx, ty - k * cTy)
        c.scale(k, k)
        c.drawBitmap(bmp, 0f, 0f, null)
        c.restore()
        if (bufN > 0 && liveTool != Tool.ERASER) {
            c.save()
            c.translate(tx, ty)
            c.scale(scale, scale)
            drawStroke(c, Stroke(liveTool, liveColor, liveWidth, buf, bufN))
            c.restore()
        }
    }

    // ---------- stroke rendering ----------

    private fun drawStroke(c: Canvas, s: Stroke) {
        val p = s.pts
        val n = s.count
        if (n == 0) return
        val hl = s.tool == Tool.HIGHLIGHTER
        paint.color = if (hl) (s.color and 0x00FFFFFF) or 0x66000000 else s.color
        paint.strokeCap = if (hl) Paint.Cap.SQUARE else Paint.Cap.ROUND
        if (n == 1) {
            paint.style = Paint.Style.FILL
            c.drawCircle(p[0], p[1], s.width * 0.5f, paint)
            return
        }
        paint.style = Paint.Style.STROKE
        if (hl) {
            path.reset()
            path.moveTo(p[0], p[1])
            for (i in 1 until n) path.lineTo(p[i * 3], p[i * 3 + 1])
            paint.strokeWidth = s.width
            c.drawPath(path, paint)
            return
        }
        var mx = p[0]; var my = p[1]
        for (i in 1 until n) {
            val x = p[i * 3]; val y = p[i * 3 + 1]
            val ex = if (i == n - 1) x else (x + p[(i + 1) * 3]) / 2f
            val ey = if (i == n - 1) y else (y + p[(i + 1) * 3 + 1]) / 2f
            path.reset()
            path.moveTo(mx, my)
            path.quadTo(x, y, ex, ey)
            paint.strokeWidth = s.width * (0.35f + 0.65f * p[i * 3 + 2])
            c.drawPath(path, paint)
            mx = ex; my = ey
        }
    }

    // ---------- input ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tt = e.getToolType(0)
        val penLike = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER ||
            tt == MotionEvent.TOOL_TYPE_MOUSE || (fingerDraws && tt == MotionEvent.TOOL_TYPE_FINGER)
        return if (penLike && !gesturing) handlePen(e, tt) else handleGesture(e)
    }

    private fun pageX(sx: Float) = (sx - tx) / scale
    private fun pageY(sy: Float) = (sy - ty) / scale

    private fun addPoint(x: Float, y: Float, pressure: Float) {
        if (bufN * 3 + 3 > buf.size) buf = buf.copyOf(buf.size * 2)
        val i = bufN * 3
        buf[i] = x; buf[i + 1] = y
        buf[i + 2] = if (pressure <= 0f) 0.5f else pressure.coerceIn(0.1f, 1f)
        bufN++
    }

    private fun handlePen(e: MotionEvent, tt: Int): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                liveTool = if (tt == MotionEvent.TOOL_TYPE_ERASER) Tool.ERASER else tool
                liveColor = color
                liveWidth = if (liveTool == Tool.HIGHLIGHTER) penWidth * 5f else penWidth
                bufN = 0
                pendingErase.clear()
                if (liveTool == Tool.ERASER) erase(pageX(e.x), pageY(e.y))
                else addPoint(pageX(e.x), pageY(e.y), e.pressure)
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    val x = pageX(e.getHistoricalX(h)); val y = pageY(e.getHistoricalY(h))
                    if (liveTool == Tool.ERASER) erase(x, y) else addPoint(x, y, e.getHistoricalPressure(h))
                }
                val x = pageX(e.x); val y = pageY(e.y)
                if (liveTool == Tool.ERASER) erase(x, y) else addPoint(x, y, e.pressure)
            }
            MotionEvent.ACTION_UP -> {
                if (liveTool == Tool.ERASER) {
                    if (pendingErase.isNotEmpty()) pushOp(Op.Remove(ArrayList(pendingErase)))
                    pendingErase.clear()
                } else if (bufN > 0) {
                    val s = Stroke(liveTool, liveColor, liveWidth, buf.copyOf(bufN * 3), bufN)
                    strokes.add(s)
                    pushOp(Op.Add(s))
                }
                bufN = 0
                changed()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { bufN = 0; pendingErase.clear(); cacheDirty = true }
        }
        invalidate()
        return true
    }

    private fun erase(px: Float, py: Float) {
        val r = 14f
        var i = strokes.size - 1
        while (i >= 0) {
            val s = strokes[i]
            val b = s.bounds
            if (px >= b.left - r && px <= b.right + r && py >= b.top - r && py <= b.bottom + r &&
                s.hit(px, py, r + s.width / 2f)
            ) {
                pendingErase.add(i to s)
                strokes.removeAt(i)
                cacheDirty = true
            }
            i--
        }
    }

    private fun pushOp(op: Op) {
        undoStack.addLast(op)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun changed() {
        cacheDirty = true
        invalidate()
        onChanged?.invoke()
    }

    private fun setLast(e: MotionEvent, skip: Int) {
        var cx = 0f; var cy = 0f; var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            cx += e.getX(i); cy += e.getY(i); n++
        }
        if (n > 0) { lastX = cx / n; lastY = cy / n }
    }

    private fun handleGesture(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { gesturing = true; setLast(e, -1) }
            MotionEvent.ACTION_POINTER_DOWN -> setLast(e, -1)
            MotionEvent.ACTION_POINTER_UP -> setLast(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                val ox = lastX; val oy = lastY
                setLast(e, -1)
                tx += lastX - ox; ty += lastY - oy
                clampPan()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gesturing = false
                cacheDirty = true
                invalidate()
            }
        }
        return true
    }
}
