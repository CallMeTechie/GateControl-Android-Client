package com.gatecontrol.android.tv.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Functions of GateControl TV that may ask for the TV PIN (decision TV 5: the PIN is not required; per function the
 * settings say "mit PIN" or "ohne PIN"). A TV in the living room is shared: the PIN keeps children and guests away
 * from the door and the settings. Drive and the vault come with stage 2.
 */
enum class TvPinFunction(val wire: String) {
    /** Tür öffnen, auf- und zusperren (quick action of the door plugin, the doorbell's "Tür öffnen"). */
    DOOR("door"),

    /** Opening Einstellungen. */
    SETTINGS("settings"),

    /** Drive (stage 2). */
    DRIVE("drive"),

    /** Tresor (stage 2). */
    VAULT("vault");

    companion object {
        /** Shown in the settings now (Drive and the vault once they exist on the TV). */
        val AVAILABLE = listOf(DOOR, SETTINGS)

        fun fromWire(raw: String?): TvPinFunction? = entries.firstOrNull { it.wire == raw }
    }
}

/**
 * The PIN itself: 4 to 8 digits, kept only as PBKDF2-HMAC-SHA256 with a random salt (`v1$<iterations>$<salt>$<hash>`,
 * base64). Pure, unit-tested.
 */
object TvPinHash {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    /** About a quarter of a second on the Fire TV Stick 4K Max: fine once per entry, slow for guessing offline. */
    const val ITERATIONS = 60_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val VERSION = "v1"

    fun isValid(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): String {
        require(isValid(pin)) { "PIN must be 4 to 8 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val b64 = Base64.getEncoder()
        return listOf(VERSION, iterations.toString(), b64.encodeToString(salt), b64.encodeToString(derive(pin, salt, iterations))).joinToString("$")
    }

    /** [pin] against a stored [hash] (constant-time compare); false for anything unreadable. */
    fun verify(pin: String, hash: String?): Boolean {
        if (hash == null || !isValid(pin)) return false
        val parts = hash.split('$')
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1_000..10_000_000 } ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull()?.takeIf { it.size >= 8 } ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Wrong PINs lock the entry: from the [FREE_ATTEMPTS]th failure on a wait of 30 s, doubled with every further one,
 * at most [MAX_LOCK_MS]. A right PIN resets the count. Pure, unit-tested.
 */
object TvPinLock {
    const val FREE_ATTEMPTS = 5
    const val FIRST_LOCK_MS = 30_000L
    const val MAX_LOCK_MS = 30 * 60_000L

    /** How long the entry is locked after [failures] wrong PINs in a row (0 = not locked). */
    fun lockMs(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(16)
        return (FIRST_LOCK_MS shl doublings).coerceAtMost(MAX_LOCK_MS)
    }

    /** The entry is locked until this time (epoch ms), 0 = not locked. */
    fun lockedUntil(failures: Int, lastFailureAt: Long): Long = lockMs(failures).let { if (it == 0L) 0L else lastFailureAt + it }

    /** Attempts left before the entry locks (0: every further wrong PIN locks it again). */
    fun attemptsLeft(failures: Int): Int = (FREE_ATTEMPTS - failures).coerceAtLeast(0)
}

/** What the PIN store keeps: the hash, the functions that need it and the failure count. */
data class TvPinState(
    val hash: String? = null,
    val required: Set<TvPinFunction> = emptySet(),
    val failures: Int = 0,
    val lastFailureAt: Long = 0L,
) {
    val hasPin: Boolean get() = hash != null

    /** [function] asks for the PIN: a PIN exists and the settings say "mit PIN" for it. */
    fun needsPin(function: TvPinFunction): Boolean = hasPin && function in required

    fun lockedUntil(): Long = TvPinLock.lockedUntil(failures, lastFailureAt)

    fun isLocked(now: Long): Boolean = lockedUntil() > now
}

/** Outcome of an entered PIN. */
sealed interface TvPinCheck {
    data object Ok : TvPinCheck

    /** Wrong; [attemptsLeft] before a lock, [lockedUntil] > 0 when this failure locked the entry. */
    data class Wrong(val attemptsLeft: Int, val lockedUntil: Long) : TvPinCheck

    /** Still locked from earlier failures, nothing was checked. */
    data class Locked(val until: Long) : TvPinCheck
}

/** The rules over [TvPinState] (pure; the store persists the result). */
object TvPinRules {
    /** Checks [pin]: the new state and the outcome. */
    fun check(state: TvPinState, pin: String, now: Long): Pair<TvPinState, TvPinCheck> {
        if (!state.hasPin) return state to TvPinCheck.Ok
        if (state.isLocked(now)) return state to TvPinCheck.Locked(state.lockedUntil())
        if (TvPinHash.verify(pin, state.hash)) return state.copy(failures = 0, lastFailureAt = 0L) to TvPinCheck.Ok
        val next = state.copy(failures = state.failures + 1, lastFailureAt = now)
        return next to TvPinCheck.Wrong(TvPinLock.attemptsLeft(next.failures), next.lockedUntil())
    }

    /** A new PIN: hash stored, failures cleared; the first PIN switches the door on (the usual reason for one). */
    fun setPin(state: TvPinState, hash: String): TvPinState = state.copy(
        hash = hash,
        failures = 0,
        lastFailureAt = 0L,
        required = if (state.hasPin) state.required else state.required + TvPinFunction.DOOR,
    )

    /** PIN removed: nothing asks for one any more. */
    fun removePin(state: TvPinState): TvPinState = TvPinState()

    /** "mit PIN" / "ohne PIN" for [function]; switching on needs a PIN to exist (the settings ask to set one first). */
    fun setRequired(state: TvPinState, function: TvPinFunction, on: Boolean): TvPinState = when {
        on && !state.hasPin -> state
        on -> state.copy(required = state.required + function)
        else -> state.copy(required = state.required - function)
    }
}
