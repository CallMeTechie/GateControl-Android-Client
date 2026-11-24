package com.gatecontrol.android.tv.media

import android.content.Context
import com.gatecontrol.android.media.MediaDevice
import com.gatecontrol.android.media.MediaQualitySetting
import com.gatecontrol.android.media.jellyfin.JellyfinDeviceCaps
import com.gatecontrol.android.media.player.SinkCaps
import com.gatecontrol.android.tv.BuildConfig
import com.gatecontrol.android.tv.R
import com.gatecontrol.android.tv.TvSettings
import com.gatecontrol.android.tv.pair.TvPairingApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GateControl TV for the shared [com.gatecontrol.android.media.MediaHub]: "GateControl TV" in Jellyfin's devices with
 * the TV's name, what the stick plays (DTS and Dolby to the AV receiver, from the HDMI sink), the quality setting.
 */
@Singleton
class TvMediaDevice @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TvSettings,
    private val pairing: TvPairingApi,
) : MediaDevice {
    override val clientName: String = CLIENT

    override val deviceName: String get() = pairing.deviceName()

    override val version: String = BuildConfig.VERSION_NAME

    override val quality: StateFlow<MediaQualitySetting> get() = settings.mediaQuality

    override fun setQuality(value: MediaQualitySetting) = settings.setMediaQuality(value)

    override fun caps(): JellyfinDeviceCaps = SinkCaps.of(context, CLIENT)

    override fun seasonName(season: Int): String = context.getString(R.string.tv_media_season, season)

    companion object {
        const val CLIENT = "GateControl TV"
    }
}
