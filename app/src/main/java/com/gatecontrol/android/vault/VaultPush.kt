package com.gatecontrol.android.vault

import com.gatecontrol.android.notify.model.PushMessage

/**
 * Vault's pushes (topics `share`, `emergency`; README "Mitteilungen"): a tap opens the Vault tab, an emergency push
 * the page "Notfallzugriff" where the grantor can refuse. A push also starts a sync (a vault shared just now).
 * Pushes never carry content or keys. Pure, unit-tested.
 */
object VaultPush {
    const val ROUTE = "plg-$VAULT_PLUGIN"
    private const val TOPIC_PREFIX = "plugin:$VAULT_PLUGIN:"

    fun isVaultMessage(message: PushMessage): Boolean = message.topic.startsWith(TOPIC_PREFIX) || message.route == ROUTE

    /** `emergency` for an emergency push, else `home`; null for other senders. */
    fun destination(message: PushMessage): String? = when {
        !isVaultMessage(message) -> null
        message.topic == TOPIC_PREFIX + "emergency" -> "emergency"
        else -> "home"
    }
}
