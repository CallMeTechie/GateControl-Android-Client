package com.gatecontrol.android.network

/**
 * Which GateControl app is talking to the server: the phone app or GateControl TV (Fire TV, Android TV).
 *
 * Each app binds its own value (Hilt, SingletonComponent). It goes out as `X-Client-Platform` on every request
 * ([AuthInterceptor]), as `platform` of the enrollment and the TV pairing, and as `client`/`platform` of the update
 * check, so the server hands the TV its own APK (`GateControl-TV-<version>.apk`, server ≥ 1.316) and the phone never
 * the TV's.
 */
enum class ClientPlatform(val wire: String) {
    ANDROID("android"),
    ANDROID_TV("android-tv"),
    ;

    /** Query of `GET /api/v1/client/update/check` for this app: `client` and `platform` (both the same value). */
    val updateQuery: UpdateQuery get() = UpdateQuery(client = wire, platform = wire)
}

/** `client` and `platform` of the update check. */
data class UpdateQuery(val client: String, val platform: String)
