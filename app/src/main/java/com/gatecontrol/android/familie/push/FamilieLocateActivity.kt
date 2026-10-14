package com.gatecontrol.android.familie.push

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.gatecontrol.android.R
import com.gatecontrol.android.familie.LocateFailCode
import com.gatecontrol.android.familie.locate.AnswerOutcome
import com.gatecontrol.android.familie.locate.LocateAnswerer
import com.gatecontrol.android.location.DeviceLocator
import com.gatecontrol.android.notify.PushRepository
import com.gatecontrol.android.support.CrashReports
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.theme.GateControlTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Standort senden" from a locate notification (mode "ich bestätige") when
 * the app may not locate in the background: a small card on top of
 * whatever is on screen locates while it is visible (location "in use"),
 * asks for the location permission first if needed, sends the answer and
 * closes. NOT exported.
 */
@AndroidEntryPoint
class FamilieLocateActivity : ComponentActivity() {

    @Inject lateinit var answerer: LocateAnswerer
    @Inject lateinit var pushRepository: PushRepository

    private sealed interface Step {
        data object Locating : Step
        data class Done(val text: String) : Step
        data class Error(val text: String) : Step
    }

    private var step by mutableStateOf<Step>(Step.Locating)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { start() }

    private var requestId = -1
    private var messageId = -1L
    private var notificationId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getIntExtra(EXTRA_REQUEST, -1)
        messageId = intent.getLongExtra(EXTRA_MESSAGE, -1L)
        notificationId = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        if (requestId <= 0) {
            finish()
            return
        }
        setContent {
            GateControlTheme(darkTheme = isSystemInDarkTheme()) {
                Box(
                    Modifier.fillMaxSize().background(GateControlTheme.extraColors.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    GcCard(modifier = Modifier.padding(24.dp).widthIn(max = 420.dp)) {
                        when (val s = step) {
                            Step.Locating -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.familie_locate_sending), style = MaterialTheme.typography.titleMedium)
                            }
                            is Step.Done -> {
                                Text(s.text, style = MaterialTheme.typography.titleMedium)
                            }
                            is Step.Error -> {
                                Text(s.text, style = MaterialTheme.typography.bodyLarge)
                                GcPrimaryButton(text = stringResource(R.string.familie_close), onClick = { finish() })
                            }
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            if (DeviceLocator.hasPermission(this)) {
                start()
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private fun start() {
        step = Step.Locating
        lifecycleScope.launch {
            val outcome = answerer.answer(requestId, appVisible = true)
            when (outcome) {
                is AnswerOutcome.Sent -> {
                    done()
                    step = Step.Done(getString(R.string.familie_locate_sent, outcome.accuracyM))
                    delay(1_500)
                    finish()
                }
                is AnswerOutcome.Failed -> {
                    // The parent learns the reason; the request is closed either way.
                    done()
                    step = Step.Error(
                        getString(
                            when (outcome.code) {
                                LocateFailCode.NO_PERMISSION -> R.string.familie_locate_fail_permission
                                LocateFailCode.LOCATION_OFF -> R.string.familie_locate_fail_off
                                LocateFailCode.TIMEOUT -> R.string.familie_locate_fail_timeout
                            },
                        ),
                    )
                }
                is AnswerOutcome.NotDelivered -> step = Step.Error(
                    getString(if (outcome.code == "offline") R.string.familie_error_offline else R.string.familie_locate_not_open),
                )
                AnswerOutcome.Busy -> finish()
            }
        }
    }

    private fun done() {
        if (notificationId != 0) NotificationManagerCompat.from(this).cancel(notificationId)
        if (messageId > 0) ackScope.launch { runCatching { pushRepository.markRead(listOf(messageId), "send_location") } }
    }

    companion object {
        private const val EXTRA_REQUEST = "com.gatecontrol.android.familie.extra.REQUEST"
        private const val EXTRA_MESSAGE = "com.gatecontrol.android.familie.extra.MESSAGE"
        private const val EXTRA_NOTIFICATION = "com.gatecontrol.android.familie.extra.NOTIFICATION"

        private val ackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReports.coroutineHandler)

        fun intent(context: Context, requestId: Int, messageId: Long, notificationId: Int): Intent =
            Intent(context, FamilieLocateActivity::class.java)
                .putExtra(EXTRA_REQUEST, requestId)
                .putExtra(EXTRA_MESSAGE, messageId)
                .putExtra(EXTRA_NOTIFICATION, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
