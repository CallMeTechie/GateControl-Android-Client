# The media JSON (Jellyfin, the stored sources) is read and written by hand: no Gson reflection, nothing to keep.

# ── SMB shares (smbj) ─────────────────────────────────────
# smbj's event bus (mbassador) finds its listeners' @Handler methods by reflection, and smbj picks its SPNEGO
# authenticator by name (Class.forName). Kept whole: small, and a stripped message class only fails at the NAS.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
# Kerberos/GSS of the JDK (SPNEGO, never used on the TV: NTLM only), mbassador's optional EL conditions, SLF4J's
# providers (none: smbj's log lines are dropped)
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn com.sun.security.jgss.**
-dontwarn org.slf4j.**
