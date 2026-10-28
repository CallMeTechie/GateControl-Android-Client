package com.gatecontrol.android.drive.vault

import com.gatecontrol.android.drive.DVaultDevice
import com.gatecontrol.android.drive.DVaultWrap
import com.gatecontrol.android.drive.DriveJson
import com.gatecontrol.android.drive.vault.DriveVaultCrypto.DriveVaultException
import com.google.gson.JsonObject

/**
 * The bodies of a new vault key (`POST vaults/<id>/rotate`) and of a new recovery key (`PUT vaults/<id>/recovery`) —
 * pure, so the tests hold them against the plugin's vectors. The keys never leave this phone unwrapped.
 */
internal object DriveVaultRotation {

    /**
     * The new key [tk] of version [keyVersion] wrapped for exactly every approved device of [devices] (`deviceId` →
     * wrap, by id). A device whose id is not the one of its public key is refused (`invalid`): nothing is wrapped for a
     * key the server swapped. [ephemeral] gives the tests their fixed HPKE key (by the device's place in the list).
     */
    fun deviceWraps(
        tk: ByteArray,
        vaultId: String,
        keyVersion: Int,
        devices: List<DVaultDevice>,
        ephemeral: (Int) -> DriveVaultCrypto.KeyPair? = { null },
    ): List<Pair<String, String>> =
        devices.filter { it.state == "approved" }.sortedBy { it.deviceId }.mapIndexed { i, d ->
            val pub = DriveVaultCrypto.unb64u(d.publicKey)
            if (DriveVaultCrypto.deviceId(pub) != d.deviceId) throw DriveVaultException("invalid", "device id")
            d.deviceId to DriveVaultCrypto.wrapKey(tk, pub, vaultId, keyVersion, ephemeral(i))
        }

    /**
     * A new recovery key [key] (32 bytes): its public key, its proof and the wraps of every key version 1…[upTo] of
     * [tks]. A version this device lacks: `old_keys` (it cannot give the recovery key what it does not have).
     */
    fun recovery(
        key: ByteArray,
        vaultId: String,
        tks: Map<Int, ByteArray>,
        upTo: Int,
        ephemeral: (Int) -> DriveVaultCrypto.KeyPair? = { null },
    ): DriveJson.RecoveryPart {
        val pair = DriveVaultCrypto.recoveryKeyPair(key)
        try {
            val wraps = (1..upTo).map { v ->
                val tk = tks[v] ?: throw DriveVaultException("old_keys", "key version $v missing")
                DVaultWrap(v, DriveVaultCrypto.wrapKey(tk, pair.publicKey, vaultId, v, ephemeral(v)))
            }
            return DriveJson.RecoveryPart(DriveVaultCrypto.b64u(pair.publicKey), DriveVaultCrypto.recoveryAuth(key), wraps)
        } finally {
            pair.wipe()
        }
    }

    /**
     * `POST vaults/<id>/rotate`: version [keyVersion] (the vault's + 1) of the new key [tk] for every approved device
     * of [devices], and either its wrap for the recovery key there is ([recoveryPub]) or a new recovery key
     * ([recovery], every version).
     */
    fun rotateBody(
        tk: ByteArray,
        vaultId: String,
        keyVersion: Int,
        devices: List<DVaultDevice>,
        recoveryPub: String?,
        recovery: DriveJson.RecoveryPart?,
        ephemeral: (Int) -> DriveVaultCrypto.KeyPair? = { null },
        recoveryEphemeral: DriveVaultCrypto.KeyPair? = null,
    ): JsonObject {
        val wraps = deviceWraps(tk, vaultId, keyVersion, devices, ephemeral)
        val recoveryWrap = if (recovery == null) {
            val pub = recoveryPub ?: throw DriveVaultException("recovery_required")
            DriveVaultCrypto.wrapKey(tk, DriveVaultCrypto.unb64u(pub), vaultId, keyVersion, recoveryEphemeral)
        } else {
            null
        }
        return DriveJson.rotateBody(keyVersion, wraps, recoveryWrap, recovery)
    }
}
