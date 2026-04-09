# FreeRDP native library
-keep class com.freerdp.** { *; }
-dontwarn com.freerdp.**

# RDP models used via reflection/intents
-keep class com.gatecontrol.android.rdp.RdpConnectionParams { *; }
