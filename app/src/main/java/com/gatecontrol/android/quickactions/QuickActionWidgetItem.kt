package com.gatecontrol.android.quickactions

import com.gatecontrol.android.network.QuickActionControl
import com.gatecontrol.android.network.QuickActionReading
import com.gatecontrol.android.network.QuickActionWidget
import com.google.gson.JsonElement
import java.text.NumberFormat
import java.util.Locale

/**
 * The widget side of a quick action as the app keeps it (core 1.171+): the
 * main reading ([value] with [unit], named [label]), a [status] line, a
 * [state] (on, off, good, warn, crit or null), small extra [readings] and up
 * to four [controls]. [power] false: the action carrying the widget is not
 * the device's power switch (it shows as a button like the others). Stored
 * with the pins, so the start tab shows the last known values before the
 * server answers.
 */
data class QuickActionWidgetItem(
    val value: String? = null,
    val unit: String? = null,
    val status: String? = null,
    val state: String? = null,
    val controls: List<QuickActionControlItem> = emptyList(),
    val label: String? = null,
    val readings: List<QuickActionReadingItem> = emptyList(),
    val power: Boolean = true,
    /** Shown in the widget's head right of the device name (core 1.175+), e.g. battery and charge level. */
    val badge: QuickActionReadingItem? = null,
) {
    /** Gson bypasses Kotlin defaults: an older stored shape may leave the lists null. */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    fun normalized(): QuickActionWidgetItem = copy(
        controls = (controls ?: emptyList()).filter { it != null && it.id != null && it.kind != null },
        readings = (readings ?: emptyList()).filter { it != null && it.id != null && it.label != null },
        badge = badge?.takeIf { it.icon != null && it.label != null },
    )

    companion object {
        const val MAX_CONTROLS = 4
        const val MAX_READINGS = 4
        val STATES = setOf("on", "off", "good", "warn", "crit")

        /** The server's widget → the app's, null when there is none or it is unusable. */
        fun from(raw: QuickActionWidget?): QuickActionWidgetItem? {
            raw ?: return null
            val controls = raw.controls.orEmpty().mapNotNull(QuickActionControlItem::from).distinctBy { it.id }.take(MAX_CONTROLS)
            val readings = raw.readings.orEmpty().mapNotNull(QuickActionReadingItem::from).distinctBy { it.id }.take(MAX_READINGS)
            val value = textOf(raw.value, 24)
            val unit = raw.unit?.let(QuickActionRepository::clean)?.take(8)?.takeIf { it.isNotBlank() }
            val status = raw.status?.let(QuickActionRepository::clean)?.take(60)?.takeIf { it.isNotBlank() }
            val state = raw.state?.takeIf { it in STATES }
            val label = raw.label?.let(QuickActionRepository::clean)?.take(24)?.takeIf { it.isNotBlank() }
            val badge = badgeOf(raw.badge)
            if (value == null && label == null && status == null && controls.isEmpty() && readings.isEmpty() && badge == null) return null
            return QuickActionWidgetItem(
                value = value, unit = unit, status = status, state = state, controls = controls,
                label = label, readings = readings, power = raw.power != false, badge = badge,
            )
        }

        /** The head's badge: an icon (required) with an optional short value; its label only names it. */
        internal fun badgeOf(raw: QuickActionReading?): QuickActionReadingItem? {
            raw ?: return null
            val icon = QuickActionIcons.sanitize(raw.icon) ?: return null
            val value = textOf(raw.value, 8)
            return QuickActionReadingItem(
                id = "badge",
                label = raw.label?.let(QuickActionRepository::clean)?.take(20)?.takeIf { it.isNotBlank() } ?: "",
                value = value,
                unit = raw.unit?.let(QuickActionRepository::clean)?.take(4)?.takeIf { it.isNotBlank() && value != null },
                icon = icon,
                state = raw.state?.takeIf { it in STATES },
            )
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
                        QuickActionControlOptionItem(oid, text, QuickActionIcons.sanitize(o.icon))
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

/**
 * One option of a choice control. [icon] is the server's path, if it sent
 * one; [glyph] is what a widget draws: that path, else a built-in one for a
 * common id (air conditioner modes), else null (then the label is shown).
 */
data class QuickActionControlOptionItem(val id: String, val label: String, val icon: String? = null) {
    val glyph: String? get() = icon ?: BUILT_IN[id]

    companion object {
        /** 24×24 stroke paths for the ids plugins use for modes (Klima: auto, cool, heat, dry, fan). */
        private val BUILT_IN = mapOf(
            "auto" to "M20 12a8 8 0 0 1-14 5.3M4 12a8 8 0 0 1 14-5.3M18 3v4h-4M6 21v-4h4",
            "cool" to "M12 2v20M3.3 7l17.4 10M3.3 17L20.7 7M9 4l3 2 3-2M9 20l3-2 3 2",
            "heat" to "M12 8a4 4 0 1 0 0 8a4 4 0 0 0 0-8M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
            "dry" to "M12 3c3.5 4.5 6 7.7 6 11a6 6 0 0 1-12 0c0-3.3 2.5-6.5 6-11z",
            "fan" to "M3 8h11a3 3 0 1 0-3-3M3 12h16a3 3 0 1 1-3 3M3 16h8",
            "off" to "M12 3v9M6.3 6.3a8 8 0 1 0 11.4 0",
            "on" to "M12 3v9M6.3 6.3a8 8 0 1 0 11.4 0",
        )
    }
}

/**
 * A small value of a widget or an Info card tile (core 1.172+): an icon
 * and/or a value with unit, coloured by [state]; [label] names it (picker,
 * screen readers). An icon alone is a state symbol, e.g. a green lock.
 */
data class QuickActionReadingItem(
    val id: String,
    val label: String,
    val value: String? = null,
    val unit: String? = null,
    val icon: String? = null,
    val state: String? = null,
) {
    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,24}$")

        /** [requireId] false: tile items carry no id; they get their position as one. */
        fun from(raw: QuickActionReading, requireId: Boolean = true, index: Int = 0): QuickActionReadingItem? {
            val id = raw.id?.takeIf { ID.matches(it) } ?: if (requireId) return null else "i$index"
            val label = raw.label?.let(QuickActionRepository::clean)?.take(20)?.takeIf { it.isNotBlank() } ?: return null
            val value = QuickActionWidgetItem.textOf(raw.value, 16)
            val icon = QuickActionIcons.sanitize(raw.icon)
            if (value == null && icon == null) return null
            return QuickActionReadingItem(
                id = id,
                label = label,
                value = value,
                unit = raw.unit?.let(QuickActionRepository::clean)?.take(8)?.takeIf { it.isNotBlank() }?.takeIf { value != null },
                icon = icon,
                state = raw.state?.takeIf { it in QuickActionWidgetItem.STATES },
            )
        }
    }
}
