package com.example.inkwell.ink

import android.graphics.RectF
import java.nio.ByteBuffer

enum class Tool { PEN, HIGHLIGHTER, ERASER }

/** Compact stroke: pts holds x, y, pressure triples in page units. */
class Stroke(
    val tool: Tool,
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    val count: Int = pts.size / 3
) {
    val bounds: RectF by lazy {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        RectF(minX - width, minY - width, maxX + width, maxY + width)
    }

    fun hit(x: Float, y: Float, r: Float): Boolean {
        val r2 = r * r
        for (i in 0 until count) {
            val bx = pts[i * 3]; val by = pts[i * 3 + 1]
            val dx = bx - x; val dy = by - y
            if (dx * dx + dy * dy <= r2) return true
            if (i > 0) {
                val ax = pts[(i - 1) * 3]; val ay = pts[(i - 1) * 3 + 1]
                val sx = bx - ax; val sy = by - ay
                val len2 = sx * sx + sy * sy
                if (len2 > 0f) {
                    val t = (((x - ax) * sx + (y - ay) * sy) / len2).coerceIn(0f, 1f)
                    val ex = ax + t * sx - x; val ey = ay + t * sy - y
                    if (ex * ex + ey * ey <= r2) return true
                }
            }
        }
        return false
    }
}

object StrokeCodec {
    fun encode(list: List<Stroke>): ByteArray {
        var size = 4
        for (s in list) size += 1 + 4 + 4 + 4 + s.count * 12
        val b = ByteBuffer.allocate(size)
        b.putInt(list.size)
        for (s in list) {
            b.put(s.tool.ordinal.toByte())
            b.putInt(s.color)
            b.putFloat(s.width)
            b.putInt(s.count * 3)
            for (i in 0 until s.count * 3) b.putFloat(s.pts[i])
        }
        return b.array()
    }

    fun decode(data: ByteArray?): MutableList<Stroke> {
        val out = ArrayList<Stroke>()
        if (data == null || data.size < 4) return out
        val b = ByteBuffer.wrap(data)
        val n = b.int
        repeat(n) {
            val tool = Tool.values()[b.get().toInt()]
            val color = b.int
            val width = b.float
            val len = b.int
            val pts = FloatArray(len) { b.float }
            out.add(Stroke(tool, color, width, pts))
        }
        return out
    }
}
