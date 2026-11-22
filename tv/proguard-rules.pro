# GateControl TV. The rules of the phone app (app/proguard-rules.pro) for what the TV uses of core/*:
# Retrofit with Gson models (pairing, enrollment, update check), WireGuard's backend.

# ── Retrofit + OkHttp ─────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class com.gatecontrol.android.network.** { *; }

# Retrofit uses reflection on generic parameters
-keepattributes InnerClasses,EnclosingMethod
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp platform adapters
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.**

# ── Gson ──────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── WireGuard ─────────────────────────────────────────────
-keep class com.wireguard.** { *; }
-keep class org.amnezia.** { *; }
-dontwarn com.wireguard.**
