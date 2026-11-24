# The shopping lists' offline copy (ShoppingData, FamilieStorage) is reflective Gson JSON: fields by name, and the
# generic signatures must survive, or List<ShoppingItem> reads back as LinkedTreeMap.
-keep class com.gatecontrol.android.familie.shopping.** { <fields>; }
-keepattributes Signature
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
