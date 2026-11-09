package com.gatecontrol.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.R
import com.gatecontrol.android.ui.theme.GateControlTheme

/*
 * The parts of a selection shared by Drive and Mail: the head while items are ticked, the bar of actions below, the
 * round tick of a row and a row of a "Mehr" sheet. Drive's look is the model (DriveBulkUi wraps these).
 */

/**
 * The bar on top while items are ticked: ✕ (ends the selection), [title] ("N ausgewählt") with [line] below (the
 * summary, or a hint), [actions] (icons of its own) and "Alle" / "Keine" on the right ([onAll], none without it).
 */
@Composable
fun GcSelectionHead(
    count: Int,
    line: String?,
    allSelected: Boolean,
    onClose: () -> Unit,
    onAll: (() -> Unit)?,
    title: String = pluralStringResource(R.plurals.drive_sel_count, count, count),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GcIconButton(GcIcons.Close, contentDescription = stringResource(R.string.drive_sel_end), onClick = onClose, tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                // TalkBack says the new count when it changes
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() },
            )
            // one line: the head is as high whatever it says (a swap with the page's bar keeps the page below in place)
            if (!line.isNullOrBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = x.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        if (onAll != null) {
            val desc = stringResource(if (allSelected) R.string.drive_sel_none_desc else R.string.drive_sel_all_desc)
            TextButton(onClick = onAll, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc }) {
                Text(stringResource(if (allSelected) R.string.drive_sel_none else R.string.drive_sel_all), color = x.accentText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** An action of the bar below a selection; not [enabled]: greyed out, a tap says [why] (when there is one). */
data class GcBarAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val why: String? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/** The bar below a selection (in place of the round button): icon over a short label, each ≥ 56 dp high. */
@Composable
fun GcSelectionActionBar(actions: List<GcBarAction>, modifier: Modifier = Modifier) {
    val x = GateControlTheme.extraColors
    val context = LocalContext.current
    val barLabel = stringResource(R.string.drive_sel_actions)
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).semantics { contentDescription = barLabel }) {
        GcRowDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            actions.forEach { a ->
                val why = a.why?.takeIf { !a.enabled }
                val tint = when {
                    !a.enabled -> x.faint
                    a.danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val unavailable = why?.let { stringResource(R.string.drive_sel_unavailable, a.label, it) }
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = a.label) {
                            if (a.enabled) a.onClick() else if (why != null) Toast.makeText(context, why, Toast.LENGTH_SHORT).show()
                        }
                        .semantics { if (unavailable != null) stateDescription = unavailable }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(a.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(a.label, style = MaterialTheme.typography.labelMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The round tick of a row or tile while selecting: empty, or filled with a check. */
@Composable
fun GcSelectCheck(selected: Boolean, modifier: Modifier = Modifier, onImage: Boolean = false) {
    val x = GateControlTheme.extraColors
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) accent else if (onImage) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
            .border(2.dp, if (selected) accent else if (onImage) Color.White else x.muted, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(GcIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
    }
}

/**
 * A row of a "Mehr" sheet: the icon in its square, [title] and [description] below (greyed out when not [enabled]),
 * [tint] for the icon and the title (e.g. the error color of "Löschen").
 */
@Composable
fun GcMoreRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val x = GateControlTheme.extraColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GcIconSquare(size = 40.dp, background = x.panel2) {
            Icon(icon, contentDescription = null, tint = if (enabled) tint else x.faint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) tint else x.faint)
            if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = if (enabled) x.muted else x.faint)
        }
    }
}

/**
 * The title row of a page that ticks items: [bar] normally, [selection] (✕, "N ausgewählt", …) while [selecting] — in
 * one row exactly as high as the higher of both, measured either way, so starting or ending a selection moves nothing
 * below (the finger of the long press stays on the item it pressed).
 */
@Composable
fun GcSelectionSwap(selecting: Boolean, bar: @Composable () -> Unit, selection: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(bar, selection), modifier = modifier) { (bars, heads), constraints ->
        val width = constraints.maxWidth
        val height = maxOf(
            bars.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0,
            heads.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0,
        ).coerceIn(constraints.minHeight, constraints.maxHeight)
        // only the one shown is measured and placed (the other has no input and no semantics)
        val shown = (if (selecting) heads else bars).map { it.measure(Constraints(constraints.minWidth, width, height, height)) }
        layout(width, height) { shown.forEach { it.placeRelative(0, 0) } }
    }
}

/** While items are ticked: dimmed, and taps and TalkBack pass it by (it stays in place, nothing below moves). */
fun Modifier.gcInert(on: Boolean): Modifier = if (!on) this else this
    .alpha(INERT_ALPHA)
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

private const val INERT_ALPHA = 0.45f
