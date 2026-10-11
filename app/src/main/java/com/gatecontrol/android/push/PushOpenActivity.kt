package com.gatecontrol.android.push

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Invisible, NON-exported activity behind a tapped message and its
 * "open" actions (open_app_route, open_portal). Marks the message read,
 * then hands the target to the navigation ([PushNavigation]) and brings up
 * MainActivity — which itself acts on no intent extras — or opens the
 * portal with the one-time login link.
 */
@AndroidEntryPoint
class PushOpenActivity : ComponentActivity() {

    @Inject lateinit var repository: PushRepository
    @Inject lateinit var portalLinkResolver: PortalLinkResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
        val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE)
        val target = intent.getStringExtra(EXTRA_TARGET)
        if (id <= 0) {
            finish()
            return
        }
        ackScope.launch { repository.markRead(listOf(id), actionId) }

        if (actionType == PushActionType.OPEN_PORTAL) {
            // Stays (invisible) until the link is there: starting the browser
            // from the background after finishing would be blocked.
            lifecycleScope.launch {
                val url = withTimeoutOrNull(LINK_TIMEOUT_MS) { portalLinkResolver.resolve(target) }
                if (url != null) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                } else {
                    Toast.makeText(this@PushOpenActivity, R.string.push_portal_unavailable, Toast.LENGTH_LONG).show()
                }
                finish()
            }
            return
        }

        // A tap on the message itself opens its detail; an action goes to its app route.
        val navTarget = if (actionType == PushActionType.OPEN_APP_ROUTE) PushNavTarget.forRoute(target, id) else PushNavTarget.Detail(id)
        PushNavigation.request(navTarget)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    companion object {
        private const val EXTRA_ID = "com.gatecontrol.android.push.extra.ID"
        private const val EXTRA_ACTION_ID = "com.gatecontrol.android.push.extra.ACTION_ID"
        private const val EXTRA_ACTION_TYPE = "com.gatecontrol.android.push.extra.ACTION_TYPE"
        private const val EXTRA_TARGET = "com.gatecontrol.android.push.extra.TARGET"
        private const val LINK_TIMEOUT_MS = 8_000L

        /** Outlives the (immediately finished) activity; the ack is short. */
        private val ackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Explicit intent to this non-exported activity. */
        fun intent(context: Context, id: Long, action: PushAction?): Intent =
            Intent(context, PushOpenActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .apply {
                    if (action != null) {
                        putExtra(EXTRA_ACTION_ID, action.id)
                        putExtra(EXTRA_ACTION_TYPE, action.type)
                        putExtra(EXTRA_TARGET, action.target)
                    }
                }
    }
}
