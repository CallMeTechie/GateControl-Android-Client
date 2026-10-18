package com.gatecontrol.android.push

import com.gatecontrol.android.notify.model.PushAction
import com.gatecontrol.android.notify.model.PushActionType
import com.gatecontrol.android.notify.model.PushMessage
import java.time.Instant

/** How a notification button is wired. */
enum class ButtonKind {
    /** "Gesehen": marks read. */
    SEEN,

    /** mute_1h / done / ack / callback: runs in the background. */
    BACKGROUND,

    /** open_app_route / open_portal: opens the app or the portal. */
    OPEN,

    /** quick_action: runs the plugin's quick action in the background (PushActionReceiver). */
    QUICK_ACTION,
}

/** One planned button; [authRequired] → `setAuthenticationRequired(true)` (device unlock first). */
data class NotificationButton(val action: PushAction, val kind: ButtonKind, val authRequired: Boolean)

/**
 * The buttons of a message notification and the checks before a
 * notification quick action runs. Pure, so the decisions are unit-tested.
 */
object NotificationQuickActions {

    /** Android shows three buttons. */
    const val MAX_BUTTONS = 3

    /**
     * Server actions with "Gesehen" after the first one (mockup: Details ·
     * Gesehen · 1 h stumm). A quick_action with `auth: device` needs the
     * device unlocked (biometrics / PIN) before Android sends it; without
     * auth it runs straight away — `confirm` is a question for the app
     * screen, the notification never asks (the unlock is the confirmation).
     */
    fun plan(actions: List<PushAction>, seen: PushAction): List<NotificationButton> =
        // Callback buttons ("In 1 Std.", "Erledigt") mark the message read themselves: no "Gesehen" beside them.
        (if (actions.any { it.type == PushActionType.CALLBACK }) actions else actions.take(1) + seen + actions.drop(1)).take(MAX_BUTTONS).map { action ->
            when {
                action === seen -> NotificationButton(action, ButtonKind.SEEN, authRequired = false)
                action.type == PushActionType.QUICK_ACTION ->
                    // Any auth:'device' asks for the unlock, even on a malformed action the receiver refuses anyway.
                    NotificationButton(action, ButtonKind.QUICK_ACTION, authRequired = action.auth == PushAction.AUTH_DEVICE)
                action.type in PushActionType.BACKGROUND -> NotificationButton(action, ButtonKind.BACKGROUND, authRequired = false)
                else -> NotificationButton(action, ButtonKind.OPEN, authRequired = false)
            }
        }

    /** Outcome of the checks before a quick action of a message runs. */
    enum class Decision {
        RUN,

        /** The message is gone from this device (inbox cleared, revoked). */
        UNKNOWN_MESSAGE,

        /** The message has no such quick action (stale or forged intent). */
        UNKNOWN_ACTION,

        /** The message expired (e.g. a doorbell after an hour): nothing runs any more. */
        EXPIRED,

        /** auth:device but the device is still locked: Android should have asked; refuse. */
        LOCKED,
    }

    /**
     * May the quick action [actionId] of [plugin] from [message] run now?
     * It must be one of that message's own quick_action buttons, the message
     * must not have expired, and an `auth: device` action only runs on an
     * unlocked device ([deviceLocked]).
     */
    fun decide(message: PushMessage?, plugin: String?, actionId: String?, nowMs: Long, deviceLocked: Boolean): Decision {
        if (message == null) return Decision.UNKNOWN_MESSAGE
        val action = message.actions.firstOrNull {
            it.isQuickAction && it.id == actionId && it.plugin == plugin
        } ?: return Decision.UNKNOWN_ACTION
        val expires = message.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (expires != null && expires <= nowMs) return Decision.EXPIRED
        if (action.needsDeviceAuth && deviceLocked) return Decision.LOCKED
        return Decision.RUN
    }
}
