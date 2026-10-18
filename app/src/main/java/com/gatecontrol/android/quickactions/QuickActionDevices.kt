package com.gatecontrol.android.quickactions

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The quick actions of one device (a car, an air conditioner, a light, a
 * lock; core 1.172+), in the server's order. Actions without a device are
 * a device of their own. [carrier] is the action that brings the device's
 * widget; [powerAction] its switch when that is the device's power switch.
 */
data class QuickActionDeviceGroup(
    /** `<plugin>/<device id>`, or `<plugin>/@<action id>` for an action on its own. */
    val key: String,
    val plugin: String,
    val pluginName: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    val actions: List<QuickActionItem> = emptyList(),
) {
    val carrier: QuickActionItem? get() = actions.firstOrNull { it.widget != null }
    val widget: QuickActionWidgetItem? get() = carrier?.widget
    val powerAction: QuickActionItem? get() = carrier?.takeIf { it.toggle && it.widget?.power == true }

    /** Everything a widget of this device can show, in display order (see [WidgetPart]). */
    fun parts(): List<WidgetPart> = buildList {
        val w = widget
        powerAction?.let { add(WidgetPart(WidgetPart.action(it.id), WidgetPart.Kind.POWER, it.title)) }
        if (w != null && (w.value != null || w.label != null)) add(WidgetPart(WidgetPart.VALUE, WidgetPart.Kind.VALUE, w.label))
        w?.readings?.forEach { add(WidgetPart(WidgetPart.reading(it.id), WidgetPart.Kind.READING, it.label)) }
        if (w != null) add(WidgetPart(WidgetPart.STATUS, WidgetPart.Kind.STATUS, null))
        w?.controls?.forEach { add(WidgetPart(WidgetPart.control(it.id), WidgetPart.Kind.CONTROL, it.label ?: it.id)) }
        actions.filter { it != powerAction }.forEach { add(WidgetPart(WidgetPart.action(it.id), WidgetPart.Kind.ACTION, it.title)) }
    }
}

/**
 * One part of a widget the person can show or hide. [label] is the
 * plugin's name for it; null for [Kind.STATUS] and a nameless [Kind.VALUE]
 * (the app names those).
 */
data class WidgetPart(val id: String, val kind: Kind, val label: String?) {
    enum class Kind { POWER, VALUE, READING, STATUS, CONTROL, ACTION }

    companion object {
        const val VALUE = "value"
        const val STATUS = "status"
        fun reading(id: String) = "reading:$id"
        fun control(id: String) = "control:$id"
        fun action(id: String) = "action:$id"
    }
}

/** Grouping and pin edits (pure, unit-tested). */
object QuickActionDevices {

    /** The actions as devices, in the order their first action comes. */
    fun group(actions: List<QuickActionItem>): List<QuickActionDeviceGroup> =
        actions.groupBy { it.deviceKey }.map { (key, list) ->
            val first = list.first()
            val own = first.deviceId == null
            QuickActionDeviceGroup(
                key = key,
                plugin = first.plugin,
                pluginName = first.pluginName,
                title = if (own) first.title else first.deviceTitle ?: first.title,
                subtitle = if (own) first.subtitle else first.deviceSubtitle,
                icon = if (own) first.icon else first.deviceIcon ?: first.icon,
                actions = list,
            )
        }

    /**
     * [pins] with the buttons of [device] set to [actionIds]: the device's
     * pins that stay keep their place, the others of it go, newly chosen
     * ones are added at the end in the device's order; at most [max].
     */
    fun withButtons(pins: List<QuickActionItem>, device: QuickActionDeviceGroup, actionIds: Set<String>, max: Int): List<QuickActionItem> {
        val ofDevice = device.actions.map { it.key }.toSet()
        val kept = pins.filter { it.key !in ofDevice || it.id in actionIds }
        val present = kept.map { it.key }.toSet()
        val added = device.actions.filter { it.id in actionIds && it.key !in present }
        return (kept + added).take(max)
    }
}

/**
 * A device pinned to the start tab as a widget. [device] is its key,
 * [anchor] the key of one of its actions (to find it again when the plugin
 * changes the device id), [parts] the shown parts (null = all, the pins
 * 1.37 had), [wide] full instead of half width, [snapshot] the device as
 * last seen (shown while the server cannot be asked), [known] the parts the
 * device had when [parts] was chosen (null: chosen before 1.47).
 */
data class StartWidgetPin(
    val device: String,
    val anchor: String? = null,
    val parts: List<String>? = null,
    val wide: Boolean = false,
    val snapshot: QuickActionDeviceGroup? = null,
    val known: List<String>? = null,
) {
    /** The device now: by key, else by the anchor action, else the copy. */
    fun resolve(devices: List<QuickActionDeviceGroup>): QuickActionDeviceGroup? =
        devices.firstOrNull { it.key == device }
            ?: anchor?.let { a -> devices.firstOrNull { d -> d.actions.any { it.key == a } } }
            ?: snapshot

    /** This pin following [devices]: the device's current key and a fresh copy; unchanged when not found. */
    fun synced(devices: List<QuickActionDeviceGroup>): StartWidgetPin {
        val now = devices.firstOrNull { it.key == device }
            ?: anchor?.let { a -> devices.firstOrNull { d -> d.actions.any { it.key == a } } }
            ?: return this
        return copy(device = now.key, anchor = anchor?.takeIf { a -> now.actions.any { it.key == a } } ?: now.actions.firstOrNull()?.key, snapshot = now)
    }

    /**
     * The parts of [group] this pin shows, in the device's order: the
     * chosen ones and those the device got since (a plugin update adding
     * the outdoor temperature shows it until the person hides it). For a
     * choice from before [known] existed, readings not chosen count as new.
     */
    fun shownParts(group: QuickActionDeviceGroup): List<WidgetPart> {
        val all = group.parts()
        val chosen = parts ?: return all
        val known = known
        return all.filter { part ->
            part.id in chosen || if (known != null) part.id !in known else part.kind == WidgetPart.Kind.READING
        }
    }

    companion object {
        private val gson = Gson()
        private val type = object : TypeToken<List<StartWidgetPin>>() {}.type

        /** [pins] with [device] shown as a widget of [parts] (replacing its pin, else added at the end); at most [max]. */
        fun withWidget(pins: List<StartWidgetPin>, device: QuickActionDeviceGroup, parts: List<String>, wide: Boolean, max: Int): List<StartWidgetPin> {
            val pin = StartWidgetPin(device.key, device.actions.firstOrNull()?.key, parts, wide, device, device.parts().map { it.id })
            return if (pins.any { it.device == device.key }) pins.map { if (it.device == device.key) pin else it }
            else (pins + pin).take(max)
        }

        @Suppress("SENSELESS_COMPARISON")
        fun decode(raw: String?): List<StartWidgetPin> = try {
            if (raw.isNullOrBlank()) emptyList()
            // Gson bypasses Kotlin null-safety: drop broken entries, mend the copy's lists.
            else gson.fromJson<List<StartWidgetPin>>(raw, type).orEmpty()
                .filter { it != null && it.device != null }
                .map { pin ->
                    pin.copy(parts = pin.parts?.filter { it != null }, snapshot = pin.snapshot?.let(::mend), known = pin.known?.filter { it != null })
                }
        } catch (e: Exception) {
            emptyList()
        }

        fun encode(pins: List<StartWidgetPin>): String = gson.toJson(pins)

        @Suppress("SENSELESS_COMPARISON")
        private fun mend(group: QuickActionDeviceGroup): QuickActionDeviceGroup? {
            if (group.key == null || group.plugin == null || group.title == null) return null
            val actions = (group.actions ?: emptyList())
                .filter { it != null && it.plugin != null && it.id != null && it.title != null && it.pluginName != null }
                .map { if (it.widget != null) it.copy(widget = it.widget.normalized()) else it }
            return group.copy(pluginName = group.pluginName ?: group.plugin, actions = actions)
        }
    }
}
