# ── Retrofit + OkHttp ─────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class com.gatecontrol.android.network.** { *; }
-keep class com.gatecontrol.android.rdp.RdpModels** { *; }

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

# ── FreeRDP ───────────────────────────────────────────────
-keep class com.freerdp.** { *; }
-dontwarn com.freerdp.**
-keep class com.gatecontrol.android.rdp.RdpConnectionParams { *; }

# ── Hilt / Dagger ─────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep @dagger.hilt.android.EarlyEntryPoint class * { *; }
-dontwarn dagger.hilt.android.internal.**

# ── Kotlin Coroutines ─────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── DataStore ─────────────────────────────────────────────
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# ── Compose ───────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── CameraX / ML Kit ─────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── EncryptedSharedPreferences / Security Crypto ─────────
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }

# ── DataStore ─────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# ── Compose Navigation ───────────────────────────────────
-keep class androidx.navigation.** { *; }
-keepnames class * extends androidx.lifecycle.ViewModel { *; }

# ── Hilt ViewModels ──────────────────────────────────────
-keep,allowobfuscation,allowshrinking class * extends androidx.lifecycle.ViewModel
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel { *; }

# ── General ───────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
