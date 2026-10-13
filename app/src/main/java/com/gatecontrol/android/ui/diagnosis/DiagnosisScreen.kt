package com.gatecontrol.android.ui.diagnosis

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatecontrol.android.R
import com.gatecontrol.android.diagnosis.CheckResult
import com.gatecontrol.android.diagnosis.CheckStatus
import com.gatecontrol.android.diagnosis.DiagnosisPayload
import com.gatecontrol.android.diagnosis.DiagnosisPhase
import com.gatecontrol.android.diagnosis.DiagnosisReport
import com.gatecontrol.android.diagnosis.DiagnosisSummary
import com.gatecontrol.android.diagnosis.DiagnosisUiState
import com.gatecontrol.android.diagnosis.DiagnosisViewModel
import com.gatecontrol.android.ui.components.GcCard
import com.gatecontrol.android.ui.components.GcIconSquare
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcOutlineButton
import com.gatecontrol.android.ui.components.GcPrimaryButton
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.components.GcTextField
import com.gatecontrol.android.ui.theme.GateControlTheme

/**
 * "Diagnose": summary header, the seven checks with status, result and a
 * hint, then "Erneut prüfen" and "Problem melden" (support bundle with a
 * description and the results).
 */
@Composable
fun DiagnosisScreen(
    onBack: () -> Unit,
    viewModel: DiagnosisViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    val running = state.phase == DiagnosisPhase.RUNNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        GcSubpageBar(
            title = stringResource(R.string.diag_title),
            onBack = onBack,
            backDescription = stringResource(R.string.common_back),
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SummaryCard(state = state, onStart = viewModel::start)

            GcCard(contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.Top) {
                state.checks.forEachIndexed { index, check ->
                    if (index > 0) GcRowDivider(Modifier.padding(horizontal = 16.dp))
                    CheckRow(check)
                }
            }

            if (state.phase != DiagnosisPhase.IDLE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GcOutlineButton(
                        text = stringResource(R.string.diag_rerun),
                        onClick = viewModel::start,
                        enabled = !running && !state.sending,
                        icon = GcIcons.Refresh,
                        modifier = Modifier.weight(1f),
                    )
                    GcPrimaryButton(
                        text = stringResource(R.string.diag_report),
                        onClick = viewModel::reportProblem,
                        enabled = !running,
                        loading = state.sending,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                GcOutlineButton(
                    text = stringResource(R.string.diag_report),
                    onClick = viewModel::reportProblem,
                    loading = state.sending,
                )
            }
            if (running && state.reportAfterRun) {
                Text(
                    stringResource(R.string.diag_report_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = GateControlTheme.extraColors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (state.reportDialogVisible) {
        ReportDialog(
            host = state.serverHost,
            description = state.description,
            onDescriptionChange = viewModel::setDescription,
            onSend = { viewModel.send(versionName) },
            onDismiss = viewModel::dismissReport,
        )
    }

    // Result of the upload as a toast (resolved in the app language).
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
}

@Composable
private fun SummaryCard(state: DiagnosisUiState, onStart: () -> Unit) {
    val extra = GateControlTheme.extraColors
    val report = state.report
    GcCard(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            state.phase == DiagnosisPhase.RUNNING -> {
                val done = state.checks.count { it.status.finished }
                val total = state.checks.size
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    HeaderRow(GcIcons.Activity, extra.blue, extra.blueBg, stringResource(R.string.diag_running, (done + 1).coerceAtMost(total), total))
                    state.checks.firstOrNull { it.status == CheckStatus.RUNNING }?.let {
                        Text(stringResource(it.id.title), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                    }
                    LinearProgressIndicator(
                        progress = { done / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = extra.panel2,
                    )
                }
            }
            report != null -> DoneSummary(report)
            else -> {
                HeaderRow(GcIcons.Activity, extra.accentText, extra.accentBg, stringResource(R.string.diag_intro_title))
                Text(stringResource(R.string.diag_intro_text), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
                GcPrimaryButton(text = stringResource(R.string.diag_start), onClick = onStart, icon = GcIcons.Activity)
            }
        }
    }
}

@Composable
private fun DoneSummary(report: DiagnosisReport) {
    val extra = GateControlTheme.extraColors
    val resources = LocalResources.current
    val top = report.topIssue
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        when (report.summary) {
            DiagnosisSummary.OK -> HeaderRow(GcIcons.ShieldCheck, extra.accentText, extra.accentBg, stringResource(R.string.diag_summary_ok))
            DiagnosisSummary.WARN -> HeaderRow(GcIcons.Alert, extra.warn, extra.warnBg, pluralStringResource(R.plurals.diag_summary_warn, report.issueCount, report.issueCount))
            DiagnosisSummary.FAIL -> HeaderRow(GcIcons.ShieldX, MaterialTheme.colorScheme.error, extra.errorBg, stringResource(R.string.diag_summary_fail))
        }
        if (top != null) {
            Text(
                stringResource(top.id.title) + (top.detail?.let { ": " + it.resolve(resources) } ?: ""),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            top.hint?.let { Text(it.resolve(resources), style = MaterialTheme.typography.bodyMedium, color = extra.muted) }
        } else {
            Text(stringResource(R.string.diag_summary_ok_text), style = MaterialTheme.typography.bodyMedium, color = extra.muted)
        }
        if (report.skippedCount > 0) {
            Text(
                pluralStringResource(R.plurals.diag_summary_skipped, report.skippedCount, report.skippedCount),
                style = MaterialTheme.typography.bodySmall,
                color = extra.faint,
            )
        }
    }
}

@Composable
private fun HeaderRow(icon: ImageVector, tint: Color, background: Color, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GcIconSquare(background = background) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CheckRow(check: CheckResult) {
    val extra = GateControlTheme.extraColors
    val resources = LocalResources.current
    val showHint = check.hint != null && (check.status == CheckStatus.WARN || check.status == CheckStatus.FAIL || check.status == CheckStatus.SKIPPED)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        StatusIcon(check.status)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(check.id.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            val detail = check.detail?.resolve(resources) ?: when (check.status) {
                CheckStatus.PENDING -> stringResource(R.string.diag_status_pending)
                CheckStatus.RUNNING -> stringResource(R.string.diag_status_running)
                else -> null
            }
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (check.status.finished) MaterialTheme.colorScheme.onSurface else extra.faint)
            }
            if (showHint) {
                Text(check.hint!!.resolve(resources), style = MaterialTheme.typography.bodySmall, color = extra.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun StatusIcon(status: CheckStatus) {
    val extra = GateControlTheme.extraColors
    val description = stringResource(
        when (status) {
            CheckStatus.PENDING -> R.string.diag_status_pending
            CheckStatus.RUNNING -> R.string.diag_status_running
            CheckStatus.OK -> R.string.diag_status_ok
            CheckStatus.WARN -> R.string.diag_status_warn
            CheckStatus.FAIL -> R.string.diag_status_fail
            CheckStatus.SKIPPED -> R.string.diag_status_skipped
        },
    )
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (status) {
            CheckStatus.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            CheckStatus.PENDING -> Box(Modifier.size(10.dp).background(extra.border2, shape = MaterialTheme.shapes.extraLarge))
            else -> {
                val (icon, tint, bg) = when (status) {
                    CheckStatus.OK -> Triple(GcIcons.Check, extra.accentText, extra.accentBg)
                    CheckStatus.WARN -> Triple(GcIcons.Alert, extra.warn, extra.warnBg)
                    CheckStatus.FAIL -> Triple(GcIcons.Close, MaterialTheme.colorScheme.error, extra.errorBg)
                    else -> Triple(GcIcons.Minus, extra.muted, extra.panel2)
                }
                GcIconSquare(size = 28.dp, background = bg) {
                    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun ReportDialog(
    host: String,
    description: String,
    onDescriptionChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val extra = GateControlTheme.extraColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.diag_report_title, host.ifBlank { "—" }), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                GcTextField(
                    value = description,
                    onValueChange = { onDescriptionChange(it.take(DiagnosisPayload.MAX_DESCRIPTION)) },
                    label = stringResource(R.string.diag_report_label),
                    placeholder = stringResource(R.string.diag_report_placeholder),
                    singleLine = false,
                )
                Text(
                    stringResource(R.string.diag_report_counter, description.length, DiagnosisPayload.MAX_DESCRIPTION),
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.faint,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.diag_report_includes), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.support_dialog_excludes), style = MaterialTheme.typography.bodySmall, color = extra.muted)
            }
        },
        confirmButton = { TextButton(onClick = onSend) { Text(stringResource(R.string.diag_report_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.support_dialog_cancel)) } },
    )
}
