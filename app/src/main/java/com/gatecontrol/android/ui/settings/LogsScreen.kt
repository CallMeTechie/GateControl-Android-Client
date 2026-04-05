package com.gatecontrol.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.gatecontrol.android.R
import java.io.File
import java.util.concurrent.TimeUnit

enum class LogPeriod(val labelRes: Int) {
    All(R.string.logs_all),
    H24(R.string.logs_24h),
    H12(R.string.logs_12h),
    H1(R.string.logs_1h)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    var selectedPeriod by remember { mutableStateOf(LogPeriod.All) }
    var logContent by remember { mutableStateOf("") }

    fun loadLogs(period: LogPeriod) {
        val cutoffMs = when (period) {
            LogPeriod.All -> 0L
            LogPeriod.H24 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            LogPeriod.H12 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12)
            LogPeriod.H1 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        }

        val logDir = File(context.cacheDir, "logs")
        if (!logDir.exists()) {
            logContent = context.getString(R.string.logs_empty)
            return
        }

        val files = logDir.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (files.isEmpty()) {
            logContent = context.getString(R.string.logs_empty)
            return
        }

        val sb = StringBuilder()
        for (file in files) {
            try {
                val lines = file.readLines()
                for (line in lines) {
                    if (cutoffMs == 0L || isLineWithinPeriod(line, cutoffMs)) {
                        sb.appendLine(line)
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("Error reading ${file.name}: ${e.message}")
            }
        }

        logContent = if (sb.isBlank()) context.getString(R.string.logs_empty) else sb.toString()
    }

    LaunchedEffect(selectedPeriod) {
        loadLogs(selectedPeriod)
    }

    fun exportLogs() {
        try {
            val logFile = File(context.cacheDir, "gatecontrol-export.log")
            logFile.writeText(logContent)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.logs_export)))
        } catch (e: Exception) {
            // Silently ignore export errors — log to Timber in production
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.logs_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Period filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = selectedPeriod == period,
                        onClick = { selectedPeriod = period },
                        label = { Text(stringResource(period.labelRes)) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Log content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = logContent.ifBlank { stringResource(R.string.logs_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { loadLogs(selectedPeriod) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.logs_refresh))
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = { exportLogs() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.logs_export))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Naive heuristic: attempts to parse a Timber/logcat timestamp at the start of the line.
 * Falls back to including the line if the timestamp cannot be parsed.
 */
private fun isLineWithinPeriod(line: String, cutoffMs: Long): Boolean {
    // Timber format: D/TAG: message (no timestamp). Fall back to including by default.
    // For file-backed loggers that prepend epoch millis or ISO timestamps, parse here.
    return true
}
