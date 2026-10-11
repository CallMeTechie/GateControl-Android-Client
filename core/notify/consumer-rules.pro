# Inbox cache entries are (de)serialized with Gson by field name.
-keep class com.gatecontrol.android.notify.model.** { *; }

# Generic types of the cache/store (Gson TypeToken subclasses keep their signature).
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
