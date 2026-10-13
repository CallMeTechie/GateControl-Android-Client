package com.gatecontrol.android.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gatecontrol.android.ui.theme.GateControlTheme

/** A hinge or fold as the window reports it, in window pixels. */
data class HingeBounds(
    val isVertical: Boolean,
    /** Splits the content in two (half opened, or a hinge with a gap). */
    val isSeparating: Boolean,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Where a separating hinge cuts the window: start and end edge in window pixels. */
data class HingeSplit(val startPx: Float, val endPx: Float)

/**
 * How the app lays itself out in the current window (tablet, foldable,
 * phone in landscape). Decided once in AppNavigation and handed down via
 * [LocalGcWindowLayout].
 */
data class GcWindowLayout(
    val widthDp: Int = 0,
    val heightDp: Int = 0,
    /** Navigation rail on the left instead of the bottom tab bar. */
    val useRail: Boolean = false,
    /** List and detail side by side (settings, notifications) and the home screen in two columns. */
    val twoPane: Boolean = false,
    /** Book posture: the two panes split at this vertical hinge. */
    val verticalHinge: HingeSplit? = null,
    /** Tabletop posture: the home screen puts the connection above, the rest below the fold. */
    val horizontalHinge: HingeSplit? = null,
) {
    companion object {
        /** From here on the bottom bar becomes a rail (Material "medium" width). */
        const val RAIL_MIN_WIDTH_DP = 600

        /**
         * From here on two panes: a settings page (~360 dp) still has room next
         * to the list. Covers tablets in portrait, unfolded foldables and phones
         * in landscape.
         */
        const val TWO_PANE_MIN_WIDTH_DP = 720

        /** Narrowest list that still reads well beside a detail pane. */
        const val MIN_LIST_WIDTH_DP = 300

        /** Width of the navigation rail (AppNavigation). */
        const val RAIL_WIDTH_DP = 88

        /**
         * @param density pixels per dp, to place a hinge (window pixels) in dp.
         */
        fun compute(
            widthDp: Int,
            heightDp: Int,
            hinges: List<HingeBounds> = emptyList(),
            density: Float = 1f,
        ): GcWindowLayout {
            val separating = hinges.filter { it.isSeparating }
            var vertical = separating.firstOrNull { it.isVertical }?.let { HingeSplit(it.left, it.right) }
            val horizontal = separating.firstOrNull { !it.isVertical }?.let { HingeSplit(it.top, it.bottom) }
            var useRail = widthDp >= RAIL_MIN_WIDTH_DP
            if (vertical != null) {
                // Book posture: the list gets the half left of the hinge. A
                // rail would leave too little of it, so the bottom bar stays;
                // if even the whole half is too narrow, no split at the hinge.
                val leftDp = (vertical.startPx / density).toInt()
                if (useRail && leftDp - RAIL_WIDTH_DP < MIN_LIST_WIDTH_DP) useRail = false
                if (leftDp < MIN_LIST_WIDTH_DP) vertical = null
            }
            return GcWindowLayout(
                widthDp = widthDp,
                heightDp = heightDp,
                useRail = useRail,
                twoPane = widthDp >= TWO_PANE_MIN_WIDTH_DP || vertical != null,
                verticalHinge = vertical,
                horizontalHinge = horizontal,
            )
        }

        /**
         * Width of the first pane: up to the hinge when there is one (minus the
         * pane's own start offset in the window), else [preferred] but never
         * more than [maxFraction] of the available width.
         */
        fun firstPaneWidthPx(
            availablePx: Float,
            paneStartPx: Float,
            hinge: HingeSplit?,
            preferredPx: Float,
            maxFraction: Float = 0.45f,
        ): Float {
            if (hinge != null) {
                val atHinge = hinge.startPx - paneStartPx
                if (atHinge > 0f && atHinge < availablePx) return atHinge
            }
            return minOf(preferredPx, availablePx * maxFraction)
        }
    }
}

val LocalGcWindowLayout = staticCompositionLocalOf { GcWindowLayout() }

/** True inside the detail pane of a list-detail layout: sub pages drop their back arrow. */
val LocalInDetailPane = staticCompositionLocalOf { false }

@Composable
fun rememberGcWindowLayout(): GcWindowLayout {
    // Size of this window (split screen, free-form, unfolded inner display).
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = with(density) { windowSize.width.toDp().value.toInt() }
    val heightDp = with(density) { windowSize.height.toDp().value.toInt() }
    val posture = currentWindowAdaptiveInfo().windowPosture
    val hinges = posture.hingeList.map {
        HingeBounds(
            isVertical = it.isVertical,
            isSeparating = it.isSeparating,
            left = it.bounds.left,
            top = it.bounds.top,
            right = it.bounds.right,
            bottom = it.bounds.bottom,
        )
    }
    return remember(widthDp, heightDp, hinges, density.density) {
        GcWindowLayout.compute(widthDp, heightDp, hinges, density.density)
    }
}

/**
 * List and detail side by side. On a half-opened foldable the split follows
 * the hinge, else the list takes [listWidth] (at most 45 %). The detail pane
 * provides [LocalInDetailPane].
 */
@Composable
fun GcTwoPane(
    modifier: Modifier = Modifier,
    listWidth: Dp = 380.dp,
    /** Thin line between the panes (not across a hinge). */
    divider: Boolean = true,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val layout = LocalGcWindowLayout.current
    val density = LocalDensity.current
    var startPx by remember { mutableFloatStateOf(0f) }
    val hinge = layout.verticalHinge
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { startPx = it.positionInWindow().x },
    ) {
        val availablePx = with(density) { maxWidth.toPx() }
        val firstPx = GcWindowLayout.firstPaneWidthPx(
            availablePx = availablePx,
            paneStartPx = startPx,
            hinge = hinge,
            preferredPx = with(density) { listWidth.toPx() },
        )
        val gapPx = if (hinge != null && hinge.startPx - startPx == firstPx) hinge.endPx - hinge.startPx else 0f
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(with(density) { firstPx.toDp() }).fillMaxHeight()) { list() }
            if (gapPx > 0f) {
                Box(Modifier.width(with(density) { gapPx.toDp() }).fillMaxHeight())
            } else if (divider) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(GateControlTheme.extraColors.border))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalInDetailPane provides true) { detail() }
            }
        }
    }
}
