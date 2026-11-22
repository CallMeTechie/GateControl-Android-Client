# GateControl TV. Stage 0 has no network code of its own yet; the shared core modules bring their
# consumer rules. Rules for Retrofit/Gson models come with the first feature that uses them (stage 1).

# OkHttp platform adapters
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.**
