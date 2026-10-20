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
# Familie keeps its offline state as reflective Gson JSON (FamilieStorage,
# PluginDirectory): field names must stay stable across releases and field
# generic signatures must survive, or List<FamilieMember> reads back as
# LinkedTreeMap (ClassCastException in 1.29.0).
-keep class com.gatecontrol.android.familie.** { <fields>; }
# Stundenplan (Schule plugin, familie.school): answers are read from the Gson
# tree (SchoolJson); the offline copy stores the raw answer text in a small
# reflective wrapper (SchoolRepository.Stored) - covered by the rule above,
# named here so a package move cannot silently drop it.
-keep class com.gatecontrol.android.familie.school.** { <fields>; }
# Door tab (Tür & Kamera): the plugin's answers are parsed from the Gson tree
# (DoorJson) and only the raw `me` JSON is cached, but the models keep their
# field names the same way, in case they are ever (de)serialized by reflection.
-keep class com.gatecontrol.android.door.** { <fields>; }

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

# ── Room (WorkManager, used by the Glance widget) ──────
# R8 full mode keeps no constructor for a bare -keep class: Room creates the
# generated *_Impl by reflection, without it WorkManager crashes at app start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── Quick action pins (Gson, stored in DataStore across app updates) ──
# Pins and widget pins (StartWidgetPin with its device copy) nest further
# classes — widget, controls, readings, look: every class of the package keeps
# its field names and the fields' generic signatures, or a List<QuickActionItem>
# in a widget pin reads back as LinkedTreeMap and the pin is dropped (1.38.0).
-keep class com.gatecontrol.android.quickactions.QuickActionItem { *; }
-keep class com.gatecontrol.android.quickactions.** { <fields>; }
# Last location sent (Gson, settings storage).
-keep class com.gatecontrol.android.location.SentLocation { *; }

# ── Chats (messenger plugin) ─────────────────────────────
# The plugin's answers are parsed from the Gson tree (ChatJson) and cached in
# Room as JSON text (ChatSerializer) - no reflection. Room's generated DAO and
# entity code is kept by its own rules plus the RoomDatabase rule above; the
# entities and models keep their field names anyway, so a future reflective
# (de)serialisation or a Room schema check cannot be broken by renaming.
-keep class com.gatecontrol.android.chat.db.** { *; }
-keep class com.gatecontrol.android.chat.** { <fields>; }
# The outbox job is created by WorkManager from its class name.
-keep class com.gatecontrol.android.chat.ChatOutboxWorker { <init>(...); }

# ── General ───────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
