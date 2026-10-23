package com.gatecontrol.android.vault.autofill

import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.data.SettingsRepository
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSecondaryButton
import com.gatecontrol.android.ui.components.GcSectionLabel
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.vault.VaultEntryRow
import com.gatecontrol.android.ui.vault.VaultLockGate
import com.gatecontrol.android.ui.vault.VaultRowsCard
import com.gatecontrol.android.ui.vault.VaultViewModel
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.EntryData
import com.gatecontrol.android.vault.VaultMatch
import com.gatecontrol.android.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Vault's page for autofill (mockup A7), opened by Android from a form: unlock if needed, then pick an entry (its
 * user name and password go into the form) — or, after a login was typed, save it in a vault. NOT exported: only the
 * service's own PendingIntents start it.
 */
@AndroidEntryPoint
class VaultAutofillActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val save = intent.getStringExtra(EXTRA_MODE) == MODE_SAVE
        @Suppress("DEPRECATION")
        val structure: AssistStructure? = if (save) null else if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
        val form = structure?.let { runCatching { VaultAutofillForm.parse(it) }.getOrNull() }
        val pending = if (save) VaultAutofillPending.take(intent.getStringExtra(EXTRA_TOKEN)) else null
        if ((save && pending == null) || (!save && form == null)) {
            finish()
            return
        }
        setContent {
            val theme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = "system")
            val sysLang = rememberSaveable { Locale.getDefault().language }
            val locale by settingsRepository.getLocale().collectAsStateWithLifecycle(initialValue = if (sysLang == "de") "de" else "en")
            LaunchedEffect(locale) {
                val config = Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            GateControlTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val viewModel: VaultViewModel = hiltViewModel()
                    val openApp = {
                        startActivity(packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                    VaultLockGate(viewModel, onForgot = openApp) {
                        if (pending != null) SavePage(viewModel, pending) else PickPage(viewModel, form!!)
                    }
                }
            }
        }
    }

    @Composable
    private fun PickPage(viewModel: VaultViewModel, form: VaultAutofillForm) {
        val x = GateControlTheme.extraColors
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        var query by rememberSaveable { mutableStateOf("") }
        // an app (no web page): offer to remember the pick for it
        var remember by rememberSaveable { mutableStateOf(form.webDomain == null) }
        val label = form.webDomain ?: VaultAutofillService.appLabel(this, form.packageName)
        val fitting = remember(entries, form) { VaultMatch.matches(entries, form.packageName, form.webDomain) }
        val live = entries.filter { !it.trashed && (it.data.username.isNotEmpty() || it.data.password.isNotEmpty()) }
        val shown = if (query.isBlank()) fitting.ifEmpty { live } else live.filter {
            listOf(it.data.title, it.data.username, it.data.urls.joinToString(" ")).any { s -> s.contains(query.trim(), true) }
        }
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_af_pick_title), onBack = { finish() }, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GcTextField(query, { query = it }, stringResource(R.string.vault_search))
                GcSectionLabel(stringResource(R.string.vault_af_header, label), Modifier.padding(start = 4.dp))
                if (shown.isEmpty()) Text(stringResource(R.string.vault_af_no_entries), color = x.muted)
                VaultRowsCard {
                    shown.take(50).forEachIndexed { i, e ->
                        if (i > 0) GcRowDivider()
                        VaultEntryRow(e, shared = false, showCode = false, onClick = { pick(viewModel, form, e, remember) })
                    }
                }
                if (form.webDomain == null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(remember, role = Role.Checkbox) { remember = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(remember, onCheckedChange = null)
                        Text(stringResource(R.string.vault_af_remember), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private fun pick(viewModel: VaultViewModel, form: VaultAutofillForm, e: Entry, rememberApp: Boolean) {
        val ds = VaultAutofillService.dataset(this, form, e) ?: return
        if (rememberApp && form.webDomain == null && form.packageName.isNotEmpty() && !VaultMatch.fitsApp(e.data, form.packageName)) {
            viewModel.save(e.id, e.vaultId, e.data.copy(apps = e.data.apps + form.packageName)) { }
        }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds))
        finish()
    }

    @Composable
    private fun SavePage(viewModel: VaultViewModel, save: VaultAutofillPending.Save) {
        val x = GateControlTheme.extraColors
        val vaults by viewModel.vaults.collectAsStateWithLifecycle()
        val entries by viewModel.entries.collectAsStateWithLifecycle()
        val label = save.webDomain?.let { VaultMatch.host(it) } ?: VaultAutofillService.appLabel(this, save.packageName)
        var title by rememberSaveable { mutableStateOf(label) }
        var username by rememberSaveable { mutableStateOf(save.username) }
        var busy by remember { mutableStateOf(false) }
        // the same login already there (same site / app and user name): its password is updated
        val existing = remember(entries) {
            VaultMatch.matches(entries, save.packageName, save.webDomain).firstOrNull { it.data.username.equals(save.username, true) }
        }
        val target = existing?.vaultId ?: vaults.firstOrNull { it.vault.personal }?.id ?: vaults.firstOrNull { it.vault.canWrite }?.id
        val saved = stringResource(R.string.vault_af_saved)
        Column(Modifier.fillMaxSize()) {
            GcSubpageBar(stringResource(R.string.vault_af_save_title), onBack = { finish() }, backDescription = stringResource(R.string.vault_back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GcTextField(title, { title = it }, stringResource(R.string.vault_field_title))
                GcTextField(username, { username = it }, stringResource(R.string.vault_field_username))
                Text("•".repeat(minOf(save.password.length, 16)), color = x.muted)
                GcPrimaryButton(stringResource(R.string.vault_save), enabled = target != null && title.isNotBlank() && !busy, loading = busy, icon = GcIcons.Check, onClick = {
                    busy = true
                    val data = existing?.data?.copy(fields = existing.data.fields + ("password" to save.password) + ("username" to username))
                        ?: EntryData(
                            type = "login", title = title.trim(),
                            urls = listOfNotNull(save.webDomain?.let { "https://$it" }),
                            apps = if (save.webDomain == null) listOf(save.packageName) else emptyList(),
                            fields = mapOf("username" to username, "password" to save.password),
                        )
                    viewModel.save(existing?.id, target!!, data) { r ->
                        busy = false
                        if (r is VaultRepository.Save.Ok) Toast.makeText(this@VaultAutofillActivity, saved, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                })
                GcSecondaryButton(stringResource(R.string.vault_cancel), onClick = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "com.gatecontrol.android.vault.extra.MODE"
        private const val EXTRA_TOKEN = "com.gatecontrol.android.vault.extra.TOKEN"
        private const val MODE_FILL = "fill"
        private const val MODE_SAVE = "save"
        private var request = 0

        fun fillSender(context: Context): IntentSender = VaultAutofillService.sender(
            context,
            Intent(context, VaultAutofillActivity::class.java).putExtra(EXTRA_MODE, MODE_FILL),
            1000 + (request++ % 1000),
        )

        fun saveSender(context: Context, token: String): IntentSender = VaultAutofillService.sender(
            context,
            Intent(context, VaultAutofillActivity::class.java).putExtra(EXTRA_MODE, MODE_SAVE).putExtra(EXTRA_TOKEN, token),
            3000 + (request++ % 1000),
        )
    }
}
