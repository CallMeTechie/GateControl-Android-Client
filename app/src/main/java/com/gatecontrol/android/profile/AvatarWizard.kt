package com.gatecontrol.android.profile

/** Steps of the profile picture wizard. */
enum class AvatarWizardStep { CROP, COLOR }

/** What "Fertig" sends: the crop to upload (null = keep the picture) and the new colour (null = unchanged). */
data class AvatarWizardPlan(val crop: CropRect?, val colorKey: String?) {
    val nothing: Boolean get() = crop == null && colorKey == null
}

/**
 * The full-screen wizard of the profile screen, pure (unit-tested): after a
 * picked photo step 1 crop → "Weiter" → step 2 ring colour → "Fertig";
 * the colour badge opens step 2 directly (no photo, the current picture
 * or initials in the preview). Back / cancel at any step discards
 * everything (the caller drops the wizard); nothing is sent before
 * "Fertig".
 */
data class AvatarWizard(
    val step: AvatarWizardStep,
    /** Started from a picked photo (crop step first); false = colour only. */
    val withPhoto: Boolean,
    /** The crop confirmed in step 1. */
    val crop: CropRect? = null,
    /** The colour picked in step 2 (palette key). */
    val colorKey: String? = null,
    /** The colour when the wizard opened: unchanged means no colour call. */
    val startColorKey: String? = null,
) {
    /** 1-based step for the indicator ("1/2"). */
    val stepNumber: Int get() = if (withPhoto && step == AvatarWizardStep.COLOR) 2 else 1

    /** Steps of this run: 2 with a photo, 1 for colour only (no indicator then). */
    val stepCount: Int get() = if (withPhoto) 2 else 1

    /** Step 1 → step 2 with the confirmed crop. */
    fun next(rect: CropRect): AvatarWizard =
        if (step == AvatarWizardStep.CROP) copy(step = AvatarWizardStep.COLOR, crop = rect) else this

    /** A swatch in step 2 (red never comes from the selectable palette). */
    fun pick(key: String): AvatarWizard = if (step == AvatarWizardStep.COLOR) copy(colorKey = key) else this

    /** "Fertig" is only offered on the colour step, and a photo run needs its crop. */
    val canFinish: Boolean get() = step == AvatarWizardStep.COLOR && (!withPhoto || crop != null)

    /** What to send on "Fertig", null while that is not possible. */
    fun plan(): AvatarWizardPlan? {
        if (!canFinish) return null
        return AvatarWizardPlan(
            crop = if (withPhoto) crop else null,
            colorKey = colorKey?.takeIf { it != startColorKey },
        )
    }

    companion object {
        /** After the photo picker: crop first. */
        fun forPhoto(currentColor: String?): AvatarWizard =
            AvatarWizard(AvatarWizardStep.CROP, withPhoto = true, colorKey = currentColor, startColorKey = currentColor)

        /** The colour badge: straight to the colour step. */
        fun colorOnly(currentColor: String?): AvatarWizard =
            AvatarWizard(AvatarWizardStep.COLOR, withPhoto = false, colorKey = currentColor, startColorKey = currentColor)

        /**
         * The current colour as a palette key: the stored choice, else the
         * key of the colour the server derived (`palette[id % 10]`).
         */
        fun currentColorKey(profile: MyProfile): String? =
            profile.color ?: AvatarPalette.selectable(profile.palette)
                .firstOrNull { it.hex.equals(profile.colorHex, ignoreCase = true) }?.key
    }
}
