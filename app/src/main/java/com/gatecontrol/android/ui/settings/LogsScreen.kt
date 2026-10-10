package com.gatecontrol.android.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.components.GcDot
import com.gatecontrol.android.ui.components.GcFilterChip
import com.gatecontrol.android.ui.components.GcIconButton
import com.gatecontrol.android.ui.components.GcIcons
import com.gatecontrol.android.ui.components.GcRowDivider
import com.gatecontrol.android.ui.components.GcSegmented
import com.gatecontrol.android.ui.components.GcSubpageBar
import com.gatecontrol.android.ui.theme.GateControlTheme
import com.gatecontrol.android.ui.theme.MonoFontFamily
import java.io.File
import java.util.concurrent.TimeUnit

enum class LogPeriod(val labelRes: Int) {
    All(R.string.logs_all),
    H24(R.string.logs_24h),
    H12(R.string.logs_12h),
    H1(R.string.logs_1h)
}

private enum class LogLevelFilter(val labelRes: Int) {
    All(R.string.logs_level_all),
    Info(R.string.logs_level_info),
    Warn(R.string.logs_level_warn),
    Error(R.string.logs_level_error),
}

/** One parsed line of the FileLoggingTree format "2026-04-07 09:55:57.123 I/Tag: message". */
internal data class LogEntry(val time: String, val level: Char, val tag: String, val message: String)

private val LINE_RE = Regex("""^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})\.\d{3} ([VDIWEA])/([^:]*): ?(.*)$""")
private const val MAX_ROWS = 1_000

internal fun parseLogLine(line: String): LogEntry? {
    val m = LINE_RE.matchEntire(line) ?: return null
    val (_, time, level, tag, msg) = m.destructured
    return LogEntry(time, level[0], tag.trim(), msg)
}

@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val extra = GateControlTheme.extraColors

    var selectedPeriod by remember { mutableStateOf(LogPeriod.All) }
    var selectedLevel by remember { mutableStateOf(LogLevelFilter.All) }
    var rawContent by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }

    fun loadLogs(period: LogPeriod) {
        val cutoffMs = when (period) {
            LogPeriod.All -> 0L
            LogPeriod.H24 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
            LogPeriod.H12 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(12)
            LogPeriod.H1 -> System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        }

        val logDir = File(context.cacheDir, "logs")
        val files = logDir.takeIf { it.exists() }?.listFiles()
            ?.filter { it.isFile && it.extension == "log" }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        val sb = StringBuilder()
        val parsed = ArrayList<LogEntry>()
        for (file in files) {
            try {
                for (line in file.readLines()) {
                    if (cutoffMs == 0L || isLineWithinPeriod(line, cutoffMs)) {
                        sb.appendLine(line)
                        val entry = parseLogLine(line)
                        if (entry != null) {
                            parsed.add(entry)
                        } else if (parsed.isNotEmpty() && line.isNotBlank()) {
                            // Continuation line (stack trace): append to the previous entry.
                            val last = parsed.removeAt(parsed.size - 1)
                            parsed.add(last.copy(message = last.message + "\n" + line.trim()))
                        }
                    }
                }
            } catch (e: Exception) {
                sb.appendLine("Error reading ${file.name}: ${e.message}")
            }
        }
        rawContent = sb.toString()
        entries = parsed.asReversed().take(MAX_ROWS)
    }

    LaunchedEffect(selectedPeriod) {
        loadLogs(selectedPeriod)
    }

    fun exportLogs() {
        try {
            // Only cacheDir/export/ is exposed through the FileProvider.
            val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
            val logFile = File(exportDir, "gatecontrol-export.log")
            logFile.writeText(rawContent)
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

    val visible = remember(entries, selectedLevel) {
        entries.filter { e ->
            when (selectedLevel) {
                LogLevelFilter.All -> true
                LogLevelFilter.Info -> e.level == 'I' || e.level == 'D' || e.level == 'V'
                LogLevelFilter.Warn -> e.level == 'W'
                LogLevelFilter.Error -> e.level == 'E' || e.level == 'A'
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        GcSubpageBar(
            title = stringResource(R.string.logs_title),
            onBack = onNavigateBack,
            backDescription = stringResource(R.string.common_back),
            actions = {
                GcIconButton(
                    icon = GcIcons.Refresh,
                    contentDescription = stringResource(R.string.logs_refresh),
                    onClick = { loadLogs(selectedPeriod) },
                    iconSize = 20.dp,
                )
                GcIconButton(
                    icon = GcIcons.Share,
                    contentDescription = stringResource(R.string.logs_export),
                    onClick = { exportLogs() },
                    iconSize = 20.dp,
                )
            },
        )
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogLevelFilter.entries.forEach { level ->
                    GcFilterChip(
                        text = stringResource(level.labelRes),
                        selected = selectedLevel == level,
                        onClick = { selectedLevel = level },
                    )
                }
            }
            GcSegmented(
                options = LogPeriod.entries.map { it to stringResource(it.labelRes) },
                selected = selectedPeriod,
                onSelect = { selectedPeriod = it },
            )
        }

        if (visible.isEmpty()) {
            Text(
                text = stringResource(R.string.logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = extra.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(visible) { index, entry ->
                    val first = index == 0
                    val last = index == visible.lastIndex
                    val shape = RoundedCornerShape(
                        topStart = if (first) 20.dp else 0.dp,
                        topEnd = if (first) 20.dp else 0.dp,
                        bottomStart = if (last) 20.dp else 0.dp,
                        bottomEnd = if (last) 20.dp else 0.dp,
                    )
                    LogRow(entry, shape, showDivider = !last)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, shape: RoundedCornerShape, showDivider: Boolean) {
    val extra = GateControlTheme.extraColors
    val (color, label) = when (entry.level) {
        'W' -> extra.warn to stringResource(R.string.logs_level_warn)
        'E', 'A' -> MaterialTheme.colorScheme.error to stringResource(R.string.logs_level_error)
        'D', 'V' -> extra.faint to "Debug"
        else -> extra.blue to stringResource(R.string.logs_level_info)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GcDot(color, Modifier.padding(top = 6.dp), size = 8.dp)
            Column(Modifier.weight(1f)) {
                Text(entry.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${entry.time} · ${entry.tag} · $label",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily, fontSize = 12.sp),
                    color = extra.muted,
                )
            }
        }
        if (showDivider) GcRowDivider()
    }
}

/**
 * Naive heuristic: attempts to parse a Timber/logcat timestamp at the start of the line.
 * Falls back to including the line if the timestamp cannot be parsed.
 */
private fun isLineWithinPeriod(line: String, cutoffMs: Long): Boolean {
    // FileLoggingTree format: "2026-04-07 09:55:57.123 I/Tag: message"
    return try {
        if (line.length < 23) return true
        val dateStr = line.substring(0, 23) // "2026-04-07 09:55:57.123"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val date = sdf.parse(dateStr) ?: return true
        date.time >= cutoffMs
    } catch (_: Exception) {
        true // Include lines that can't be parsed
    }
}
