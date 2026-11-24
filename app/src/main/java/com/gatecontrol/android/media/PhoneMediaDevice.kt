package com.gatecontrol.android.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.gatecontrol.android.BuildConfig
import com.gatecontrol.android.R
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone app for the shared [MediaHub]: "GateControl" in Jellyfin's devices with the phone's name, what it plays
 * itself ([MediaDeviceCaps.phone]: its decoders and screen; nothing goes on to a receiver) and the quality setting.
 * With headphones (wired, Bluetooth, USB) at most stereo is asked for: Jellyfin then mixes 5.1 down on the NAS.
 */
@Singleton
class PhoneMediaDevice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: MediaPhoneSettings,
) : MediaDevice {
    override val clientName: String = CLIENT

    override val deviceName: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.trim()?.ifEmpty { null }
            ?: "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}".trim().ifEmpty { "Android" }

    override val version: String = BuildConfig.VERSION_NAME

    override val quality: StateFlow<MediaQualitySetting> get() = settings.quality

    override fun setQuality(value: MediaQualitySetting) = settings.setQuality(value)

    override fun caps(): JellyfinDeviceCaps = MediaDeviceCaps.phone(context, CLIENT, MediaPhoneLogic.maxChannels(outputs()))

    override fun seasonName(season: Int): String = context.getString(R.string.media_season, season)

    /** The kinds of the sound outputs connected now (AudioDeviceInfo.TYPE_…). */
    private fun outputs(): Set<Int> = runCatching {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map(AudioDeviceInfo::getType)?.toSet()
    }.getOrNull().orEmpty()

    companion object {
        const val CLIENT = "GateControl"
    }
}
