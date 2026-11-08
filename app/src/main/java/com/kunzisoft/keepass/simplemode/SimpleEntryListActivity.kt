/*
 * Copyright 2026 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.simplemode

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.EntryActivity
import com.kunzisoft.keepass.activities.legacy.DatabaseLockActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.timeout.TimeoutHelper

/**
 * Main screen of the simplified mode: read-only list of the entries of the
 * configured folder, with a search field, a big "Exit" button and a small
 * link to go back to the normal mode. No edit / delete / add action is available.
 */
class SimpleEntryListActivity : DatabaseLockActivity() {

    private var rootView: View? = null
    private var searchView: EditText? = null
    private var emptyView: TextView? = null
    private var titleView: TextView? = null
    private val mAdapter = SimpleEntryAdapter { entry -> openEntry(entry) }

    private var mEntries: List<Entry> = emptyList()

    private val mEntryResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun viewToInvalidateTimeout(): View? = rootView

    override fun manageDatabaseInfo(): Boolean = false

    override fun errorCoordinatorView(): View? = rootView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_entry_list)

        rootView = findViewById(R.id.simple_entry_list_root)
        titleView = findViewById(R.id.simple_entry_list_title)
        searchView = findViewById(R.id.simple_entry_list_search)
        emptyView = findViewById(R.id.simple_entry_list_empty)

        val ta = theme.obtainStyledAttributes(intArrayOf(R.attr.colorSecondary))
        mAdapter.iconColor = ta.getColor(0, Color.WHITE)
        ta.recycle()

        findViewById<RecyclerView>(R.id.simple_entry_list_recycler).apply {
            layoutManager = LinearLayoutManager(this@SimpleEntryListActivity)
            adapter = mAdapter
        }

        searchView?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                resetAppTimeout()
                applyFilter()
            }
        })

        findViewById<Button>(R.id.simple_entry_list_exit_button).setOnClickListener {
            SimpleMode.lockAndCloseApp(this)
        }
        findViewById<TextView>(R.id.simple_entry_list_normal_mode).setOnClickListener {
            SimpleUnlockActivity.askToLeaveSimpleMode(this)
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        if (!database.loaded)
            return
        val configuration = SimpleMode.getConfiguration(this)
        if (configuration == null) {
            SimpleMode.lockAndRestartApp(this)
            return
        }
        mAdapter.database = database
        val group = SimpleMode.findGroup(database, configuration.groupId)
        if (group == null) {
            // The folder was deleted or moved to another database
            titleView?.text = configuration.groupName
            mEntries = emptyList()
            emptyView?.setText(R.string.simple_mode_folder_not_found)
        } else {
            titleView?.text = group.title
            mEntries = SimpleMode.entriesOf(database, group)
            emptyView?.setText(R.string.simple_mode_no_entry)
        }
        applyFilter()
    }

    private fun applyFilter() {
        val query = searchView?.text?.toString()?.trim()?.lowercase() ?: ""
        val filtered = if (query.isEmpty()) mEntries else mEntries.filter { entry ->
            entry.getVisualTitle().lowercase().contains(query)
                    || entry.username.lowercase().contains(query)
                    || entry.url.lowercase().contains(query)
        }
        mAdapter.submit(filtered)
        emptyView?.isVisible = filtered.isEmpty()
    }

    private fun openEntry(entry: Entry) {
        mDatabase?.let { database ->
            EntryActivity.launch(
                activity = this,
                database = database,
                entryId = entry.nodeId,
                activityResultLauncher = mEntryResultLauncher
            )
        }
    }

    override fun onDatabaseBackPressed() {
        if (searchView?.text?.isNotEmpty() == true) {
            searchView?.setText("")
        } else {
            // Back from the main simplified screen = exit the app
            SimpleMode.lockAndCloseApp(this)
        }
    }

    private class SimpleEntryAdapter(
        private val onEntryClick: (Entry) -> Unit
    ) : RecyclerView.Adapter<SimpleEntryAdapter.EntryViewHolder>() {

        var database: ContextualDatabase? = null
        var iconColor: Int = Color.WHITE
        private var entries: List<Entry> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(newEntries: List<Entry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_simple_entry, parent, false)
            return EntryViewHolder(view)
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            val entry = entries[position]
            holder.title.text = entry.getVisualTitle()
            holder.subtitle.text = entry.username
            holder.subtitle.isVisible = entry.username.isNotEmpty()
            database?.iconDrawableFactory?.assignDatabaseIcon(holder.icon, entry.icon, iconColor)
            holder.itemView.setOnClickListener { onEntryClick(entry) }
        }

        override fun getItemCount(): Int = entries.size

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.simple_entry_icon)
            val title: TextView = view.findViewById(R.id.simple_entry_title)
            val subtitle: TextView = view.findViewById(R.id.simple_entry_subtitle)
        }
    }

    companion object {
        fun launch(activity: Activity, database: ContextualDatabase) {
            if (database.loaded && TimeoutHelper.checkTimeAndLockIfTimeout(activity)) {
                activity.startActivity(Intent(activity, SimpleEntryListActivity::class.java))
            }
        }
    }
}
