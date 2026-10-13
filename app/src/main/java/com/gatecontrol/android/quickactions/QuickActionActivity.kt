package com.gatecontrol.android.quickactions

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.R
import com.gatecontrol.android.support.CrashReports
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Invisible, NON-exported activity that runs one quick action — from an app
 * shortcut, the widget or a Quick Settings tile. Asks first when the action
 * wants a confirmation, shows the result as a toast and finishes.
 *
 * Only this app can start it (shortcut intents and the widget's pending
 * intents run with the app's identity); the action itself is checked again
 * by the server for the device's user.
 */
@AndroidEntryPoint
class QuickActionActivity : ComponentActivity() {

    @Inject lateinit var repository: QuickActionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val plugin = intent?.getStringExtra(EXTRA_PLUGIN)
        val id = intent?.getStringExtra(EXTRA_ID)
        if (plugin.isNullOrBlank() || id.isNullOrBlank()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val key = "$plugin/$id"
            val item = repository.currentPins().firstOrNull { it.key == key }
                ?: repository.actions.value.firstOrNull { it.key == key }
                ?: QuickActionItem(
                    plugin = plugin,
                    pluginName = plugin,
                    id = id,
                    title = intent.getStringExtra(EXTRA_TITLE) ?: id,
                    confirm = intent.getBooleanExtra(EXTRA_CONFIRM, false),
                )
            if (item.confirm) confirm(item) else runAndFinish(item)
        }
    }

    private fun confirm(item: QuickActionItem) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.quick_action_confirm_title, item.title))
            .setMessage(item.subtitle ?: item.pluginName)
            .setPositiveButton(R.string.quick_action_confirm_run) { _, _ -> runAndFinish(item) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun runAndFinish(item: QuickActionItem) {
        val app = applicationContext
        // Outlives the activity: the server call may take a few seconds.
        runScope.launch {
            val result = repository.run(item)
            withContext(Dispatchers.Main) { showResult(app, item, result) }
        }
        finish()
    }

    companion object {
        private const val EXTRA_PLUGIN = "com.gatecontrol.android.extra.QA_PLUGIN"
        private const val EXTRA_ID = "com.gatecontrol.android.extra.QA_ID"
        private const val EXTRA_TITLE = "com.gatecontrol.android.extra.QA_TITLE"
        private const val EXTRA_CONFIRM = "com.gatecontrol.android.extra.QA_CONFIRM"

        private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        /** Explicit intent to this non-exported component (shortcuts need an action). */
        fun intent(context: Context, item: QuickActionItem): Intent =
            Intent(context, QuickActionActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_PLUGIN, item.plugin)
                .putExtra(EXTRA_ID, item.id)
                .putExtra(EXTRA_TITLE, item.title)
                .putExtra(EXTRA_CONFIRM, item.confirm)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
                )

        fun showResult(context: Context, item: QuickActionItem, result: QuickActionResult) {
            val text = result.message?.takeIf { it.isNotBlank() }
                ?: context.getString(if (result.ok) R.string.quick_action_done else R.string.quick_action_failed, item.title)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
