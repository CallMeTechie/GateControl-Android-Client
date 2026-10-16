package com.gatecontrol.android.quickactions

import com.gatecontrol.android.network.QuickActionControl
import com.gatecontrol.android.network.QuickActionWidget
import com.google.gson.JsonElement
import java.text.NumberFormat
import java.util.Locale

/**
 * The widget side of a quick action as the app keeps it (core 1.171+): a
 * reading ([value] with [unit]), a [status] line, a [state] (on, off, good,
 * warn, crit or null) and up to four [controls]. Stored with the pins, so
 * the start tab shows the last known reading before the server answers.
 */
data class QuickActionWidgetItem(
    val value: String? = null,
    val unit: String? = null,
    val status: String? = null,
    val state: String? = null,
    val controls: List<QuickActionControlItem> = emptyList(),
) {
    /** Gson bypasses Kotlin defaults: an older stored shape may leave the list null. */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    fun normalized(): QuickActionWidgetItem =
        if (controls == null) copy(controls = emptyList()) else copy(controls = controls.filter { it != null && it.id != null && it.kind != null })

    companion object {
        const val MAX_CONTROLS = 4
        val STATES = setOf("on", "off", "good", "warn", "crit")

        /** The server's widget → the app's, null when there is none or it is unusable. */
        fun from(raw: QuickActionWidget?): QuickActionWidgetItem? {
            raw ?: return null
            val controls = raw.controls.orEmpty().mapNotNull(QuickActionControlItem::from).distinctBy { it.id }.take(MAX_CONTROLS)
            val value = textOf(raw.value, 24)
            val unit = raw.unit?.let(QuickActionRepository::clean)?.take(8)?.takeIf { it.isNotBlank() }
            val status = raw.status?.let(QuickActionRepository::clean)?.take(60)?.takeIf { it.isNotBlank() }
            val state = raw.state?.takeIf { it in STATES }
            if (value == null && status == null && controls.isEmpty()) return null
            return QuickActionWidgetItem(value = value, unit = unit, status = status, state = state, controls = controls)
        }

        /** A JSON string or number as display text, null for anything else. */
        internal fun textOf(v: JsonElement?, max: Int): String? {
            if (v == null || !v.isJsonPrimitive) return null
            val p = v.asJsonPrimitive
            return when {
                p.isString -> QuickActionRepository.clean(p.asString).take(max).takeIf { it.isNotBlank() }
                p.isNumber -> p.asDouble.takeIf { it.isFinite() }?.let { formatNumber(it) }
                else -> null
            }
        }

        /** 22 → "22", 21.5 → "21,5" (locale), at most one decimal. */
        fun formatNumber(d: Double, locale: Locale = Locale.getDefault()): String =
            NumberFormat.getInstance(locale).apply { maximumFractionDigits = 1; isGroupingUsed = false }.format(d)
    }
}

/**
 * One control of a widget: `stepper` and `slider` carry [number] in
 * [min]..[max] with [step]; `choice` carries the chosen option id in
 * [choice] and its [options].
 */
data class QuickActionControlItem(
    val id: String,
    val kind: String,
    val label: String? = null,
    val number: Double = 0.0,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val step: Double = 1.0,
    val unit: String? = null,
    val choice: String? = null,
    val options: List<QuickActionControlOptionItem> = emptyList(),
) {
    val isNumeric: Boolean get() = kind == KIND_STEPPER || kind == KIND_SLIDER

    /** The number of decimals the step needs (0.5 → 1), for display and rounding. */
    val decimals: Int get() = if (step % 1.0 == 0.0) 0 else 1

    companion object {
        const val KIND_STEPPER = "stepper"
        const val KIND_SLIDER = "slider"
        const val KIND_CHOICE = "choice"
        private val ID = Regex("^[A-Za-z0-9_-]{1,24}$")

        fun from(raw: QuickActionControl): QuickActionControlItem? {
            val id = raw.id?.takeIf { ID.matches(it) } ?: return null
            val kind = raw.kind ?: return null
            val label = raw.label?.let(QuickActionRepository::clean)?.take(20)?.takeIf { it.isNotBlank() }
            val unit = raw.unit?.let(QuickActionRepository::clean)?.take(8)?.takeIf { it.isNotBlank() }
            return when (kind) {
                KIND_STEPPER, KIND_SLIDER -> {
                    val min = raw.min?.takeIf { it.isFinite() } ?: return null
                    val max = raw.max?.takeIf { it.isFinite() && it > min } ?: return null
                    val step = raw.step?.takeIf { it.isFinite() && it > 0 } ?: return null
                    val value = raw.value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asDouble?.takeIf { it.isFinite() } ?: return null
                    QuickActionControlItem(id, kind, label, value.coerceIn(min, max), min, max, step, unit)
                }
                KIND_CHOICE -> {
                    val options = raw.options.orEmpty().mapNotNull { o ->
                        val oid = o.id?.takeIf { ID.matches(it) } ?: return@mapNotNull null
                        val text = o.label?.let(QuickActionRepository::clean)?.take(20)?.takeIf { it.isNotBlank() } ?: oid
                        QuickActionControlOptionItem(oid, text)
                    }.distinctBy { it.id }.take(8)
                    if (options.size < 2) return null
                    val chosen = raw.value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    QuickActionControlItem(
                        id = id, kind = KIND_CHOICE, label = label, unit = unit,
                        choice = options.firstOrNull { it.id == chosen }?.id ?: options.first().id,
                        options = options,
                    )
                }
                else -> null
            }
        }
    }
}

data class QuickActionControlOptionItem(val id: String, val label: String)
