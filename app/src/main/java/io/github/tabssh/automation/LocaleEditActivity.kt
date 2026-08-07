package io.github.tabssh.automation

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.launch

/**
 * Locale/Tasker plugin configuration screen (ACTION_EDIT_SETTING).
 * The host app launches this so the user picks an action and a target
 * connection inside TabSSH; the choice is returned as the plugin-private
 * EXTRA_BUNDLE the host later replays to [LocaleFireReceiver]. Because
 * configuration happens in-app against the user's own profile list, the
 * host never needs to know profile IDs or credentials.
 */
class LocaleEditActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var actionSpinner: Spinner
    private lateinit var connectionSpinner: Spinner
    private lateinit var commandInput: EditText
    private lateinit var keysInput: EditText
    private lateinit var waitCheck: CheckBox
    private var profiles: List<ConnectionProfile> = emptyList()

    private val actionValues = listOf(
        TaskerWorker.ACTION_CONNECT,
        TaskerWorker.ACTION_DISCONNECT,
        TaskerWorker.ACTION_SEND_COMMAND,
        TaskerWorker.ACTION_SEND_KEYS
    )
    private val actionLabels = listOf("Connect", "Disconnect", "Send Command", "Send Keys")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_locale_edit)
        app = application as TabSSHApplication

        actionSpinner = findViewById(R.id.spinner_action)
        connectionSpinner = findViewById(R.id.spinner_connection)
        commandInput = findViewById(R.id.input_command)
        keysInput = findViewById(R.id.input_keys)
        waitCheck = findViewById(R.id.check_wait_result)

        actionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, actionLabels
        )
        actionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldVisibility(actionValues[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.button_cancel).setOnClickListener { finish() }
        findViewById<Button>(R.id.button_save).setOnClickListener { saveAndFinish() }

        loadProfiles()
    }

    private fun updateFieldVisibility(action: String) {
        val isCommand = action == TaskerWorker.ACTION_SEND_COMMAND
        val isKeys = action == TaskerWorker.ACTION_SEND_KEYS
        commandInput.visibility = if (isCommand) View.VISIBLE else View.GONE
        waitCheck.visibility = if (isCommand) View.VISIBLE else View.GONE
        keysInput.visibility = if (isKeys) View.VISIBLE else View.GONE
    }

    private fun loadProfiles() {
        lifecycleScope.launch {
            profiles = app.database.connectionDao().getAllConnectionsList()
            connectionSpinner.adapter = ArrayAdapter(
                this@LocaleEditActivity,
                android.R.layout.simple_spinner_dropdown_item,
                profiles.map { it.name }
            )
            restoreFromExistingBundle()
        }
    }

    /** Pre-populates the form when the host re-opens an existing setting. */
    private fun restoreFromExistingBundle() {
        val bundle = intent.getBundleExtra(LocalePlugin.EXTRA_BUNDLE)
        if (!LocalePlugin.isBundleValid(bundle)) return
        requireNotNull(bundle)
        val action = bundle.getString(LocalePlugin.BUNDLE_KEY_ACTION)
        actionValues.indexOf(action).takeIf { it >= 0 }?.let { actionSpinner.setSelection(it) }
        val savedId = bundle.getString(LocalePlugin.BUNDLE_KEY_CONNECTION_ID)
        profiles.indexOfFirst { it.id == savedId }.takeIf { it >= 0 }
            ?.let { connectionSpinner.setSelection(it) }
        commandInput.setText(bundle.getString(LocalePlugin.BUNDLE_KEY_COMMAND).orEmpty())
        keysInput.setText(bundle.getString(LocalePlugin.BUNDLE_KEY_KEYS).orEmpty())
        waitCheck.isChecked = bundle.getBoolean(LocalePlugin.BUNDLE_KEY_WAIT_FOR_RESULT, false)
    }

    private fun saveAndFinish() {
        val profile = profiles.getOrNull(connectionSpinner.selectedItemPosition)
        if (profile == null) {
            Toast.makeText(this, "Add a connection in TabSSH first", Toast.LENGTH_LONG).show()
            return
        }
        val action = actionValues[actionSpinner.selectedItemPosition]
        val command = commandInput.text.toString().take(LocalePlugin.MAX_COMMAND_LENGTH)
        val keys = keysInput.text.toString().take(LocalePlugin.MAX_KEYS_LENGTH)
        if (action == TaskerWorker.ACTION_SEND_COMMAND && command.isBlank()) {
            Toast.makeText(this, "Enter a command to run", Toast.LENGTH_SHORT).show()
            return
        }
        if (action == TaskerWorker.ACTION_SEND_KEYS && keys.isBlank()) {
            Toast.makeText(this, "Enter the keys to send", Toast.LENGTH_SHORT).show()
            return
        }

        val bundle = LocalePlugin.buildBundle(
            action = action,
            connectionId = profile.id,
            connectionName = profile.name.take(LocalePlugin.MAX_NAME_LENGTH),
            command = command.ifBlank { null },
            keys = keys.ifBlank { null },
            waitForResult = waitCheck.isChecked
        )
        val blurb = LocalePlugin.buildBlurb(action, profile.name, command, keys)
        setResult(RESULT_OK, Intent().apply {
            putExtra(LocalePlugin.EXTRA_BUNDLE, bundle)
            putExtra(LocalePlugin.EXTRA_STRING_BLURB, blurb)
        })
        finish()
    }
}
