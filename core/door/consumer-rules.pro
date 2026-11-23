# Models kept by field name: (de)serialized with Gson (pins, stored layout, the door's offline `me`).
-keep class com.gatecontrol.android.door.** { <fields>; }
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
