package com.gatecontrol.android.vault.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.gatecontrol.android.R
import com.gatecontrol.android.vault.Entry
import com.gatecontrol.android.vault.VaultMatch
import com.gatecontrol.android.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** A login to save, held in this process only (never in an Intent) until the save page takes it. */
object VaultAutofillPending {
    data class Save(val packageName: String, val webDomain: String?, val username: String, val password: String, val atMs: Long)

    private val saves = ConcurrentHashMap<String, Save>()

    fun put(save: Save): String {
        val now = System.currentTimeMillis()
        saves.entries.removeIf { now - it.value.atMs > 10 * 60_000 }
        return UUID.randomUUID().toString().also { saves[it] = save }
    }

    fun take(token: String?): Save? = token?.let { saves.remove(it) }
}

/**
 * Vault as Android's autofill service (mockup A7): fitting entries for the app's package or the page's domain, an
 * "unlock" entry while locked, "In Vault suchen …", and saving a new login. Only while the person has Vault set
 * up; never for GateControl's own fields. The suggestions carry titles and user names only — the password goes into
 * the field when picked.
 */
@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject lateinit var repository: VaultRepository

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val form = runCatching { VaultAutofillForm.parse(structure) }.getOrNull()
        if (form == null || form.packageName == packageName || form.ids.isEmpty()) return callback.onSuccess(null)
        repository.ensureLoaded()
        if (repository.state.value.account == null) return callback.onSuccess(null)
        val label = form.webDomain ?: appLabel(this, form.packageName)
        val response = FillResponse.Builder()
        if (!repository.unlocked.value) {
            response.setAuthentication(
                form.ids.toTypedArray(),
                VaultAutofillActivity.fillSender(this),
                presentation(this, getString(R.string.vault_af_unlock), label),
            )
        } else {
            VaultMatch.matches(repository.entries.value, form.packageName, form.webDomain).take(MAX_SUGGESTIONS).forEach { e ->
                dataset(this, form, e)?.let { response.addDataset(it) }
            }
            @Suppress("DEPRECATION")
            val search = Dataset.Builder(presentation(this, getString(R.string.vault_af_search), label))
                .apply { form.ids.forEach { setValue(it, null) } }
                .setAuthentication(VaultAutofillActivity.fillSender(this))
                .build()
            response.addDataset(search)
        }
        if (form.passwordIds.isNotEmpty()) {
            val type = if (form.usernameId != null) SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD else SaveInfo.SAVE_DATA_TYPE_PASSWORD
            response.setSaveInfo(SaveInfo.Builder(type, form.ids.toTypedArray()).build())
        }
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val form = runCatching { VaultAutofillForm.parse(structure) }.getOrNull()
        if (form == null || form.password.isEmpty() || form.packageName == packageName) return callback.onSuccess()
        val token = VaultAutofillPending.put(VaultAutofillPending.Save(form.packageName, form.webDomain, form.username, form.password, System.currentTimeMillis()))
        // the save page opens from here (unlock if needed, pick the vault, save)
        callback.onSuccess(VaultAutofillActivity.saveSender(this, token))
    }

    companion object {
        private const val MAX_SUGGESTIONS = 5

        fun appLabel(context: Context, pkg: String): String =
            runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

        fun presentation(context: Context, title: String, sub: String?): RemoteViews =
            RemoteViews(context.packageName, R.layout.vault_autofill_item).apply {
                setTextViewText(R.id.vault_af_title, title)
                setTextViewText(R.id.vault_af_sub, sub.orEmpty())
            }

        /** One entry as a dataset: user name and password into their fields. */
        fun dataset(context: Context, form: VaultAutofillForm, e: Entry): Dataset? {
            val user = e.data.username
            val pw = e.data.password
            if (user.isEmpty() && pw.isEmpty()) return null
            @Suppress("DEPRECATION")
            val b = Dataset.Builder(presentation(context, e.data.title, user.ifEmpty { null }))
            var any = false
            form.usernameId?.takeIf { user.isNotEmpty() }?.let { @Suppress("DEPRECATION") b.setValue(it, AutofillValue.forText(user)); any = true }
            if (pw.isNotEmpty()) form.passwordIds.forEach { id: AutofillId -> @Suppress("DEPRECATION") b.setValue(id, AutofillValue.forText(pw)); any = true }
            return if (any) b.build() else null
        }

        fun sender(context: Context, intent: Intent, request: Int): IntentSender =
            PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender
    }
}
