package com.ping.verbead

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.ping.verbead.util.HapticUtil

class DictSettingsActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var adapter: DictAdapter

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val count = UserDictionary.importAndMergeFromCsv(this, stream)
                refreshList()
                Toast.makeText(this, getString(R.string.toast_dict_import_success, count), Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, R.string.toast_dict_import_cannot_read, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_dict_import_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val entries = UserDictionary.load(this)
            contentResolver.openOutputStream(uri)?.use { stream ->
                UserDictionary.exportToCsv(entries, stream)
                Toast.makeText(this, getString(R.string.toast_dict_export_success, entries.size), Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, R.string.toast_dict_export_cannot_write, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_dict_export_failed, e.message ?: ""), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        emptyState = findViewById(R.id.ll_empty_state)
        recycler = findViewById(R.id.rv_dict)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = DictAdapter(
            loadEntries(),
            onDelete = { from, to ->
                val displayLabel = if (from != to) "$from → $to" else to
                AlertDialog.Builder(this)
                    .setTitle("刪除專屬詞彙")
                    .setMessage("確定要刪除「$displayLabel」嗎？")
                    .setPositiveButton("刪除") { _, _ ->
                        UserDictionary.remove(this, from)
                        refreshList()
                        HapticUtil.click(this)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onEdit = { from, to ->
                showEditDialog(from, to)
            }
        )
        recycler.adapter = adapter
        refreshList()

        findViewById<View>(R.id.btn_add_entry).setOnClickListener { showAddDialog() }

        findViewById<View>(R.id.btn_import_csv).setOnClickListener {
            try {
                importCsvLauncher.launch(
                    arrayOf(
                        "text/comma-separated-values",
                        "text/csv",
                        "text/plain",
                        "*/*"
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_dict_file_picker_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btn_export_csv).setOnClickListener {
            try {
                exportCsvLauncher.launch("verbead_user_dict.csv")
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.toast_dict_save_dialog_error, e.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshList() {
        val entries = loadEntries()
        adapter.update(entries)
        emptyState.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun loadEntries() = UserDictionary.load(this).entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_entry, null)
        val etTo = view.findViewById<EditText>(R.id.et_to)
        val etFrom = view.findViewById<EditText>(R.id.et_from)

        AlertDialog.Builder(this)
            .setTitle("新增專屬詞彙 / 替換規則")
            .setView(view)
            .setPositiveButton("新增") { _, _ ->
                val to = etTo.text.toString().trim()
                val fromRaw = etFrom.text.toString().trim()
                if (to.isBlank()) {
                    Toast.makeText(this, R.string.toast_dict_target_word_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val from = if (fromRaw.isNotBlank()) fromRaw else to
                UserDictionary.add(this, from, to)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditDialog(existingFrom: String, existingTo: String) {
        val view = layoutInflater.inflate(R.layout.dialog_add_entry, null)
        val etTo = view.findViewById<EditText>(R.id.et_to)
        val etFrom = view.findViewById<EditText>(R.id.et_from)

        etTo.setText(existingTo)
        if (existingFrom != existingTo) {
            etFrom.setText(existingFrom)
        }

        AlertDialog.Builder(this)
            .setTitle("編輯專屬詞彙 / 替換規則")
            .setView(view)
            .setPositiveButton("儲存") { _, _ ->
                val to = etTo.text.toString().trim()
                val fromRaw = etFrom.text.toString().trim()
                if (to.isBlank()) {
                    Toast.makeText(this, R.string.toast_dict_target_word_empty, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val from = if (fromRaw.isNotBlank()) fromRaw else to
                if (from != existingFrom) {
                    UserDictionary.remove(this, existingFrom)
                }
                UserDictionary.add(this, from, to)
                refreshList()
                HapticUtil.click(this)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class DictAdapter(
        private var items: List<Pair<String, String>>,
        private val onDelete: (String, String) -> Unit,
        private val onEdit: (String, String) -> Unit,
    ) : RecyclerView.Adapter<DictAdapter.VH>() {

        fun update(newItems: List<Pair<String, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvTo: TextView      = view.findViewById(R.id.tv_to)
            val tvMapping: TextView = view.findViewById(R.id.tv_mapping)
            val btnDel: ImageButton = view.findViewById(R.id.btn_delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_dict_entry, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (from, to) = items[position]
            holder.tvTo.text = to
            if (from != to) {
                holder.tvMapping.visibility = View.VISIBLE
                holder.tvMapping.text = "自動替換：$from → $to"
            } else {
                holder.tvMapping.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onEdit(from, to) }
            holder.btnDel.setOnClickListener { onDelete(from, to) }
        }
    }
}
