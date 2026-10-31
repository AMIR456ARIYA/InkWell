package com.example.inkwell.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.inkwell.data.AppDb
import com.example.inkwell.data.AppScope
import com.example.inkwell.data.DbDispatcher
import com.example.inkwell.data.Page
import com.example.inkwell.ink.InkView
import com.example.inkwell.ink.StrokeCodec
import com.example.inkwell.ink.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorActivity : ComponentActivity() {
    private lateinit var ink: InkView
    private lateinit var pageLabel: TextView
    private val dao by lazy { AppDb.get(this).dao() }
    private var pageIds = listOf<Long>()
    private var index = 0
    private var notebookId = 0L
    private var dirty = false
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { flushSave() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notebookId = intent.getLongExtra("nb", 0)

        ink = InkView(this)
        ink.onChanged = {
            dirty = true
            handler.removeCallbacks(saveRunnable)
            handler.postDelayed(saveRunnable, 1500)
        }
        pageLabel = TextView(this).apply {
            setPadding(24, 0, 24, 0)
            gravity = Gravity.CENTER_VERTICAL
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        fun btn(label: String, textColor: Int = Color.BLACK, action: () -> Unit) {
            bar.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setTextColor(textColor)
                setOnClickListener { action() }
            })
        }
        btn("Pen") { ink.tool = Tool.PEN }
        btn("Highlighter") { ink.tool = Tool.HIGHLIGHTER }
        btn("Eraser") { ink.tool = Tool.ERASER }
        btn("●", Color.BLACK) { ink.color = Color.BLACK }
        btn("●", 0xFF1A73E8.toInt()) { ink.color = 0xFF1A73E8.toInt() }
        btn("●", 0xFFD93025.toInt()) { ink.color = 0xFFD93025.toInt() }
        btn("●", 0xFF188038.toInt()) { ink.color = 0xFF188038.toInt() }
        btn("●", 0xFFFBBC04.toInt()) { ink.color = 0xFFFBBC04.toInt() }
        btn("Thin") { ink.penWidth = 2f }
        btn("Medium") { ink.penWidth = 3.5f }
        btn("Thick") { ink.penWidth = 6f }
        btn("Undo") { ink.undo() }
        btn("Redo") { ink.redo() }
        btn("Paper") { cycleTemplate() }
        btn("Finger: off") { /* label updated below */ }
        (bar.getChildAt(bar.childCount - 1) as Button).setOnClickListener { v ->
            ink.fingerDraws = !ink.fingerDraws
            (v as Button).text = if (ink.fingerDraws) "Finger: on" else "Finger: off"
        }
        btn("◀") { goTo(index - 1) }
        bar.addView(pageLabel)
        btn("▶") { goTo(index + 1) }
        btn("+ Page") { addPage() }

        val scroller = HorizontalScrollView(this).apply { addView(bar) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroller)
        root.addView(ink, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            pageIds = withContext(DbDispatcher) { dao.pageIds(notebookId) }
            if (pageIds.isNotEmpty()) loadPage(0)
        }
    }

    private fun loadPage(i: Int) {
        lifecycleScope.launch {
            val p = withContext(DbDispatcher) { dao.page(pageIds[i]) } ?: return@launch
            val strokes = withContext(Dispatchers.Default) { StrokeCodec.decode(p.strokes) }
            index = i
            ink.setPage(strokes, p.template)
            dirty = false
            pageLabel.text = "${i + 1} / ${pageIds.size}"
        }
    }

    private fun goTo(i: Int) {
        if (i !in pageIds.indices || i == index) return
        flushSave()
        loadPage(i)
    }

    private fun addPage() {
        flushSave()
        lifecycleScope.launch {
            val id = withContext(DbDispatcher) {
                dao.insertPage(
                    Page(notebookId = notebookId, position = pageIds.size, template = ink.template, strokes = null)
                )
            }
            pageIds = pageIds + id
            loadPage(pageIds.size - 1)
        }
    }

    private fun cycleTemplate() {
        if (pageIds.isEmpty()) return
        val t = (ink.template + 1) % 3
        ink.template = t
        val id = pageIds[index]
        AppScope.launch(DbDispatcher) { dao.saveTemplate(id, t) }
    }

    /** Encode on the UI thread (fast, compact), write on the single DB thread. */
    private fun flushSave() {
        handler.removeCallbacks(saveRunnable)
        if (!dirty || pageIds.isEmpty()) return
        dirty = false
        val id = pageIds[index]
        val data = StrokeCodec.encode(ink.strokes)
        AppScope.launch(DbDispatcher) { dao.saveStrokes(id, data) }
    }

    override fun onPause() {
        flushSave()
        super.onPause()
    }
}
