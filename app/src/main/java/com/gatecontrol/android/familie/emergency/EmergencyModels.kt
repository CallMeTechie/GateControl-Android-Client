package com.gatecontrol.android.familie.emergency

/**
 * Notfall-Karte (Familie 1.1, module `emergency`): health data of every
 * member and the family's important numbers. Sensitive: kept on the
 * family's own server and, for offline use, encrypted on the phone
 * ([EmergencyCache]); never logged, never in pushes.
 */

data class EmergencyContact(
    val label: String,
    /** Digits, spaces and `+ / ( ) -` only. */
    val phone: String,
    val note: String,
) {
    companion object {
        const val MAX_LABEL = 60
        const val MAX_PHONE = 30
        const val MAX_NOTE = 80
        val PHONE_RE = Regex("^(?=.*\\d)[0-9 +/()\\-]{1,30}$")

        /** Phone input as the server accepts it: other characters out, capped. */
        fun cleanPhone(value: String): String = value.filter { it.isDigit() || it in " +/()-" }.trim().take(MAX_PHONE)
    }
}

data class EmergencyInsurance(val name: String, val number: String) {
    companion object {
        const val MAX_NAME = 60
        const val MAX_NUMBER = 40
    }
}

data class EmergencyMember(
    val userId: Int,
    val name: String,
    /** `YYYY-MM-DD` or null. */
    val birthdate: String?,
    val age: Int?,
    /** A+ … 0- or null (unknown). */
    val bloodType: String?,
    val weightKg: Double?,
    val allergies: String,
    val medications: String,
    val conditions: String,
    val notes: String,
    val insurance: EmergencyInsurance?,
    val contacts: List<EmergencyContact>,
    val updatedAt: String?,
    val updatedBy: Int?,
    val updatedByName: String?,
) {
    /** Nothing entered yet (the card shows a hint instead of empty boxes). */
    val isEmpty: Boolean
        get() = bloodType == null && weightKg == null && allergies.isBlank() && medications.isBlank() &&
            conditions.isBlank() && notes.isBlank() && insurance == null && contacts.isEmpty()

    companion object {
        const val MAX_TEXT = 300
        const val MAX_NOTES = 500
        const val MAX_CONTACTS = 10
        const val MIN_WEIGHT = 0.5
        const val MAX_WEIGHT = 400.0

        /** Weight as the server takes it: 0.5–400 kg in 0.1 steps, else null. */
        fun weight(kg: Double?): Double? =
            kg?.takeIf { it.isFinite() && it in MIN_WEIGHT..MAX_WEIGHT }?.let { Math.round(it * 10) / 10.0 }

        /** The wire values; "0" (zero) as written in Germany, "O" accepted when reading. */
        val BLOOD_TYPES = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "0+", "0-")
    }
}

/** `GET emergency`. */
data class EmergencyCard(
    val members: List<EmergencyMember>,
    /** Family-wide numbers (Kinderarzt, Giftnotruf, …). */
    val contacts: List<EmergencyContact>,
    val updatedAt: String?,
) {
    fun member(userId: Int?): EmergencyMember? = members.firstOrNull { it.userId == userId }

    companion object {
        const val MAX_FAMILY_CONTACTS = 20
    }
}

/** Body of `PUT emergency/members/<userId>`. */
data class EmergencyMemberDraft(
    val bloodType: String?,
    val weightKg: Double?,
    val allergies: String,
    val medications: String,
    val conditions: String,
    val notes: String,
    val insurance: EmergencyInsurance?,
    val contacts: List<EmergencyContact>,
)
