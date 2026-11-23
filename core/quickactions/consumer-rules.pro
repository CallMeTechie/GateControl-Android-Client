# Models kept by field name: (de)serialized with Gson (pins, stored layout, the door's offline `me`).
-keep class com.gatecontrol.android.quickactions.** { <fields>; }
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keep class com.gatecontrol.android.quickactions.QuickActionItem { *; }
-keep class com.gatecontrol.android.layout.** { <fields>; }
