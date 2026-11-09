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
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import com.kunzisoft.keepass.activities.FileDatabaseSelectActivity
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.element.Entry
import com.kunzisoft.keepass.database.element.Group
import com.kunzisoft.keepass.utils.LOCK_ACTION

/**
 * Simplified UI mode: a read-only, minimal interface for inexperienced users.
 *
 * When enabled, the app only allows to:
 *  - unlock a single database with the device unlock (fingerprint),
 *  - browse / search the entries of a single folder (group) of this database,
 *  - exit the app (or go back to the normal mode).
 *
 * The configuration is stored in a dedicated private SharedPreferences file
 * so it never interferes with the regular app settings.
 */
object SimpleMode {

    private const val PREFERENCES_NAME = "simple_mode"
    private const val KEY_ENABLED = "simple_mode_enabled"
    private const val KEY_DATABASE_URI = "simple_mode_database_uri"
    private const val KEY_DATABASE_NAME = "simple_mode_database_name"
    private const val KEY_GROUP_ID = "simple_mode_group_id"
    private const val KEY_GROUP_NAME = "simple_mode_group_name"

    data class Configuration(
        val databaseUri: Uri,
        val databaseName: String,
        val groupId: String,
        val groupName: String
    )

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return getConfiguration(context) != null
    }

    fun getConfiguration(context: Context): Configuration? {
        val prefs = preferences(context)
        if (!prefs.getBoolean(KEY_ENABLED, false))
            return null
        val databaseUri = prefs.getString(KEY_DATABASE_URI, null)
        val groupId = prefs.getString(KEY_GROUP_ID, null)
        if (databaseUri.isNullOrEmpty() || groupId.isNullOrEmpty())
            return null
        return Configuration(
            databaseUri = databaseUri.toUri(),
            databaseName = prefs.getString(KEY_DATABASE_NAME, null) ?: "",
            groupId = groupId,
            groupName = prefs.getString(KEY_GROUP_NAME, null) ?: ""
        )
    }

    fun enable(context: Context, configuration: Configuration) {
        preferences(context).edit {
            putBoolean(KEY_ENABLED, true)
            putString(KEY_DATABASE_URI, configuration.databaseUri.toString())
            putString(KEY_DATABASE_NAME, configuration.databaseName)
            putString(KEY_GROUP_ID, configuration.groupId)
            putString(KEY_GROUP_NAME, configuration.groupName)
        }
    }

    fun disable(context: Context) {
        preferences(context).edit {
            clear()
        }
    }

    /**
     * Retrieve the group stored in the configuration, or null if it no longer exists
     */
    fun findGroup(database: ContextualDatabase, groupId: String): Group? {
        val root = database.rootGroup ?: return null
        return findGroup(root, groupId)
    }

    private fun findGroup(group: Group, groupId: String): Group? {
        if (group.nodeId.toString() == groupId)
            return group
        group.getChildGroups().forEach { child ->
            findGroup(child, groupId)?.let { return it }
        }
        return null
    }

    /**
     * Special groups never shown in the simplified mode: recycle bin and templates
     */
    private fun excludedGroups(database: ContextualDatabase): List<Group> {
        return listOfNotNull(database.recycleBin, database.templatesGroup)
    }

    /**
     * All the groups of the database that can be selected for the simplified mode
     * (the recycle bin, the templates group and their content are excluded),
     * each with its full path.
     */
    fun selectableGroups(database: ContextualDatabase): List<Pair<Group, String>> {
        val result = mutableListOf<Pair<Group, String>>()
        val root = database.rootGroup ?: return result
        val excludedGroups = excludedGroups(database)
        fun visit(group: Group, path: String) {
            if (excludedGroups.contains(group))
                return
            result.add(Pair(group, path))
            group.getChildGroups()
                .sortedBy { it.title.lowercase() }
                .forEach { child ->
                    visit(child, if (path.isEmpty()) child.title else "$path / ${child.title}")
                }
        }
        visit(root, root.title)
        return result
    }

    /**
     * All the entries of [group] and of its sub-groups (sub-folders are hidden
     * in the simplified mode), excluding the recycle bin and the templates, sorted by title.
     */
    fun entriesOf(database: ContextualDatabase, group: Group): List<Entry> {
        val result = mutableListOf<Entry>()
        val excludedGroups = excludedGroups(database)
        fun visit(current: Group) {
            if (excludedGroups.contains(current))
                return
            result.addAll(current.getChildEntries())
            current.getChildGroups().forEach { visit(it) }
        }
        visit(group)
        return result.sortedBy { it.getVisualTitle().lowercase() }
    }

    /**
     * Lock the opened database and close every screen of the app
     */
    fun lockAndCloseApp(activity: Activity) {
        activity.sendBroadcast(Intent(LOCK_ACTION))
        activity.finishAffinity()
    }

    /**
     * Lock the opened database and restart the app from its entry point,
     * used when the simplified mode is switched on or off
     */
    fun lockAndRestartApp(activity: Activity) {
        activity.sendBroadcast(Intent(LOCK_ACTION))
        activity.startActivity(
            Intent(activity, FileDatabaseSelectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        activity.finishAffinity()
    }
}
