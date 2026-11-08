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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.app.database.FileDatabaseHistoryAction
import com.kunzisoft.keepass.biometric.DeviceUnlockFragment
import com.kunzisoft.keepass.biometric.deviceUnlockError
import com.kunzisoft.keepass.database.ContextualDatabase
import com.kunzisoft.keepass.database.MainCredential
import com.kunzisoft.keepass.database.element.Database.Companion.DEFAULT_PASSWORD_ENCODING
import com.kunzisoft.keepass.model.CipherDecryptDatabase
import com.kunzisoft.keepass.model.CredentialStorage
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.utils.LOCK_ACTION
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.viewmodels.DeviceUnlockViewModel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Entry screen of the simplified mode:
 * only allows to unlock the configured database with the device unlock (fingerprint)
 * and to exit the app. The database is always opened in read-only mode.
 */
class SimpleUnlockActivity : DatabaseModeActivity() {

    private lateinit var coordinatorLayout: CoordinatorLayout
    private var unavailableView: TextView? = null

    private var mConfiguration: SimpleMode.Configuration? = null
    // Avoid launching the entry list twice (database retrieved + load action finished)
    private var mEntryListLaunched = false
    // The entry list is only shown after a fingerprint unlock performed from this screen
    private var mLoadRequested = false

    private val mDeviceUnlockViewModel: DeviceUnlockViewModel? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ViewModelProvider(this)[DeviceUnlockViewModel::class.java]
        } else null
    }

    override fun manageDatabaseInfo(): Boolean = false

    override fun errorCoordinatorView(): View = coordinatorLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val configuration = SimpleMode.getConfiguration(this)
        if (configuration == null) {
            // Simplified mode no longer configured, back to the normal app
            SimpleMode.lockAndRestartApp(this)
            return
        }
        mConfiguration = configuration
        mLoadRequested = savedInstanceState?.getBoolean(LOAD_REQUESTED_KEY, false) ?: false

        setContentView(R.layout.activity_simple_unlock)
        coordinatorLayout = findViewById(R.id.simple_unlock_coordinator_layout)
        unavailableView = findViewById(R.id.simple_unlock_unavailable)

        findViewById<TextView>(R.id.simple_unlock_database_name).text =
            configuration.databaseName
        findViewById<TextView>(R.id.simple_unlock_group_name).text =
            configuration.groupName

        findViewById<Button>(R.id.simple_unlock_exit_button).setOnClickListener {
            SimpleMode.lockAndCloseApp(this)
        }
        findViewById<TextView>(R.id.simple_unlock_normal_mode).setOnClickListener {
            askToLeaveSimpleMode(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mDeviceUnlockViewModel?.connect(configuration.databaseUri)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    mDeviceUnlockViewModel?.let { deviceUnlockViewModel ->
                        deviceUnlockViewModel.uiState.collect { uiState ->
                            uiState.cipherDecryptDatabase?.let { cipherDecryptDatabase ->
                                onCredentialDecrypted(cipherDecryptDatabase)
                                deviceUnlockViewModel.consumeCredentialDecrypted()
                            }
                            // Never store a new credential from the simplified mode
                            uiState.cipherEncryptDatabase?.let {
                                deviceUnlockViewModel.consumeCredentialEncrypted()
                            }
                            uiState.exception?.let { error ->
                                Snackbar.make(
                                    coordinatorLayout,
                                    deviceUnlockError(error, this@SimpleUnlockActivity),
                                    Snackbar.LENGTH_LONG
                                ).asError().show()
                                deviceUnlockViewModel.exceptionShown()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mEntryListLaunched = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && PreferencesUtil.isDeviceUnlockEnable(this)) {
            unavailableView?.isVisible = false
            if (supportFragmentManager.findFragmentByTag(UNLOCK_FRAGMENT_TAG) == null) {
                supportFragmentManager.commit {
                    replace(
                        R.id.simple_unlock_device_unlock_container,
                        DeviceUnlockFragment(),
                        UNLOCK_FRAGMENT_TAG
                    )
                }
            }
        } else {
            unavailableView?.isVisible = true
        }
    }

    override fun onDatabaseRetrieved(database: ContextualDatabase) {
        super.onDatabaseRetrieved(database)
        openEntriesIfLoaded(database)
    }

    override fun onDatabaseActionFinished(
        database: ContextualDatabase,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        if (actionTask == ACTION_DATABASE_LOAD_TASK && result.isSuccess) {
            openEntriesIfLoaded(database)
        }
        result.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(LOAD_REQUESTED_KEY, mLoadRequested)
        super.onSaveInstanceState(outState)
    }

    private fun openEntriesIfLoaded(database: ContextualDatabase) {
        val configuration = mConfiguration ?: return
        if (!database.loaded)
            return
        if (!mLoadRequested) {
            // A database is still opened (ie: from the normal mode),
            // close it so that the fingerprint is always required
            sendBroadcast(Intent(LOCK_ACTION))
            return
        }
        if (database.fileUri != null && database.fileUri != configuration.databaseUri) {
            // Another database is opened, it must be closed first
            Toast.makeText(this, R.string.warning_database_already_opened, Toast.LENGTH_LONG).show()
            return
        }
        if (!mEntryListLaunched) {
            mEntryListLaunched = true
            SimpleEntryListActivity.launch(this, database)
        }
    }

    private fun onCredentialDecrypted(cipherDecryptDatabase: CipherDecryptDatabase) {
        val configuration = mConfiguration ?: return
        if (cipherDecryptDatabase.credentialStorage != CredentialStorage.PASSWORD)
            return
        val charBuffer = DEFAULT_PASSWORD_ENCODING.decode(
            ByteBuffer.wrap(cipherDecryptDatabase.decryptedValue)
        )
        val password = CharArray(charBuffer.remaining())
        charBuffer.get(password)
        // Retrieve the remembered key file / hardware key, if any
        FileDatabaseHistoryAction.getInstance(applicationContext)
            .getDatabaseFile(configuration.databaseUri) { databaseFile ->
                val mainCredential = MainCredential(
                    password = password,
                    keyFileUri = if (PreferencesUtil.rememberKeyFileLocations(this))
                        databaseFile?.keyFileUri else null,
                    hardwareKey = if (PreferencesUtil.rememberHardwareKey(this))
                        databaseFile?.hardwareKey else null
                )
                mLoadRequested = true
                mDatabaseViewModel.loadDatabase(
                    databaseUri = configuration.databaseUri,
                    mainCredential = mainCredential,
                    // The simplified mode is always read-only
                    readOnly = true,
                    allowUserVerification = false,
                    cipherEncryptDatabase = null
                )
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mDeviceUnlockViewModel?.disconnect()
        }
    }

    companion object {
        private const val UNLOCK_FRAGMENT_TAG = "SIMPLE_UNLOCK_FRAGMENT_TAG"
        private const val LOAD_REQUESTED_KEY = "LOAD_REQUESTED_KEY"

        fun launch(context: Context) {
            context.startActivity(Intent(context, SimpleUnlockActivity::class.java))
        }

        /**
         * Ask a confirmation before leaving the simplified mode
         */
        fun askToLeaveSimpleMode(activity: android.app.Activity) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.simple_mode_exit_title)
                .setMessage(R.string.simple_mode_exit_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.simple_mode_exit_confirm) { _, _ ->
                    SimpleMode.disable(activity)
                    SimpleMode.lockAndRestartApp(activity)
                }
                .create().show()
        }
    }
}
