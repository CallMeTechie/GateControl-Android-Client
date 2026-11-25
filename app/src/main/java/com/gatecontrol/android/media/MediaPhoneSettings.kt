package com.gatecontrol.android.media

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone's settings of Medien (Medien › Quellen): the quality setting of [MediaDevice], the limit on mobile data
 * (question 28: 3 Mbit/s by default, "keine" switches it off) and whether "Weiterschauen" stands on Start. Nothing
 * secret: the sources and their access are in the encrypted [MediaSourceStore].
 */
@Singleton
class MediaPhoneSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _quality = MutableStateFlow(MediaQualitySetting.fromWire(prefs.getString(KEY_QUALITY, null)))
    val quality: StateFlow<MediaQualitySetting> = _quality.asStateFlow()

    private val _mobileCap = MutableStateFlow(MediaPhoneLogic.capFromStored(prefs.getLong(KEY_MOBILE_CAP, MediaPhoneLogic.DEFAULT_MOBILE_CAP_BPS)))

    /** At most this many bit/s on mobile data; null: no limit. */
    val mobileCap: StateFlow<Long?> = _mobileCap.asStateFlow()

    private val _startCard = MutableStateFlow(prefs.getBoolean(KEY_START_CARD, true))

    /** "Weiterschauen auf Start" (on by default). */
    val startCard: StateFlow<Boolean> = _startCard.asStateFlow()

    /** The note on the data volume (question 28) was shown once, at the first playback on mobile data. */
    val mobileHintSeen: Boolean get() = prefs.getBoolean(KEY_MOBILE_HINT, false)

    fun setMobileHintSeen() = prefs.edit { putBoolean(KEY_MOBILE_HINT, true) }

    fun setQuality(value: MediaQualitySetting) {
        prefs.edit { putString(KEY_QUALITY, value.wire) }
        _quality.value = value
    }

    fun setMobileCap(bps: Long?) {
        prefs.edit { putLong(KEY_MOBILE_CAP, bps ?: 0L) }
        _mobileCap.value = MediaPhoneLogic.capFromStored(bps ?: 0L)
    }

    fun setStartCard(on: Boolean) {
        prefs.edit { putBoolean(KEY_START_CARD, on) }
        _startCard.value = on
    }

    private companion object {
        const val PREFS = "media_phone"
        const val KEY_QUALITY = "quality"
        const val KEY_MOBILE_CAP = "mobile_cap_bps"
        const val KEY_START_CARD = "start_card"
        const val KEY_MOBILE_HINT = "mobile_hint_seen"
    }
}
