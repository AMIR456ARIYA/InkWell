package com.example.inkwell.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.inkwell.data.AppDb
import com.example.inkwell.data.DbDispatcher
import com.example.inkwell.data.Notebook
import com.example.inkwell.data.Page
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotebookListActivity : ComponentActivity() {
    private val dao by lazy { AppDb.get(this).dao() }
    private var items = listOf<Notebook>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val header = TextView(this).apply { text = "Inkwell"; textSize = 28f }
        val add = Button(this).apply {
            text = "New notebook"
            isAllCaps = false
            setOnClickListener { promptNew() }
        }
        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, EditorActivity::class.java).putExtra("nb", items[pos].id))
        }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(items[pos]); true }
        root.addView(header)
        root.addView(add)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            items = withContext(DbDispatcher) { dao.notebooks() }
            adapter.clear()
            adapter.addAll(items.map { it.title })
        }
    }

    private fun promptNew() {
        val et = EditText(this).apply { hint = "Title" }
        AlertDialog.Builder(this)
            .setTitle("New notebook")
            .setView(et)
            .setPositiveButton("Create") { _, _ -> create(et.text.toString().ifBlank { "Untitled" }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun create(title: String) {
        lifecycleScope.launch {
            withContext(DbDispatcher) {
                val id = dao.insert(Notebook(title = title, createdAt = System.currentTimeMillis()))
                dao.insertPage(Page(notebookId = id, position = 0, template = 0, strokes = null))
            }
            refresh()
        }
    }

    private fun confirmDelete(n: Notebook) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${n.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    withContext(DbDispatcher) { dao.deletePages(n.id); dao.deleteNotebook(n.id) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
