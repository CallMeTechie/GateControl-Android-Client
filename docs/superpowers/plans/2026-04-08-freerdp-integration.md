# FreeRDP Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate FreeRDP as an embedded in-app RDP client, replacing external RDP app dependency while keeping external fallback.

**Architecture:** aFreeRDP fork as Git submodule → AAR in `core/rdp/libs/`. New `RdpSessionActivity` (fullscreen) hosts FreeRDP `SessionView`. New `RdpSessionService` provides foreground notification. `RdpManager.connect()` routes to embedded client when available, falls back to external.

**Tech Stack:** Kotlin, FreeRDP (aFreeRDP fork via AAR), Android VpnService, Jetpack Compose (UI unchanged — Activity is native Views), Hilt DI, JUnit 5 + MockK.

**Spec:** `docs/superpowers/specs/2026-04-08-freerdp-integration-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpConnectionParams.kt` | Typed parameter bundle for FreeRDP sessions |
| `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt` | Fullscreen Activity hosting FreeRDP SessionView + floating toolbar |
| `app/src/main/java/com/gatecontrol/android/service/RdpSessionService.kt` | Foreground service for RDP notification + heartbeat |
| `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpConnectionParamsTest.kt` | Unit tests for param validation & mapping |
| `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt` | Unit tests for embedded client availability & intent building |
| `app/src/test/java/com/gatecontrol/android/service/RdpSessionServiceTest.kt` | Unit tests for notification building & lifecycle |
| `app/src/test/java/com/gatecontrol/android/rdp/RdpSessionActivityTest.kt` | Unit tests for intent parsing & FLAG_SECURE |
| `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpCredentialSecurityTest.kt` | Security tests: credential wipe, no clipboard for embedded |
| `.github/workflows/freerdp-build.yml` | CI workflow for building FreeRDP AAR |
| `freerdp/VERSION` | FreeRDP upstream version tracking |

### Modified Files

| File | Change |
|------|--------|
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpModels.kt` | Add `RdpProgress.SERVICE_START` step |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt` | Add `launchSession()` method |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpManager.kt` | Replace TODO with embedded client logic + step 8 |
| `core/rdp/src/main/java/com/gatecontrol/android/rdp/di/RdpModule.kt` | No changes needed (RdpEmbeddedClient already provided) |
| `core/rdp/build.gradle.kts` | Uncomment AAR dependency |
| `core/rdp/consumer-rules.pro` | Add FreeRDP ProGuard rules |
| `app/src/main/AndroidManifest.xml` | Register RdpSessionActivity + RdpSessionService |
| `app/proguard-rules.pro` | Add FreeRDP keep rules |
| `app/src/main/res/values/strings.xml` | Add embedded RDP strings (EN) |
| `app/src/main/res/values-de/strings.xml` | Add embedded RDP strings (DE) |
| `app/src/test/java/com/gatecontrol/android/ui/rdp/RdpViewModelTest.kt` | Add embedded client connect tests |
| `.github/workflows/pr-check.yml` | Add security credential leak check |
| `.github/workflows/release.yml` | Add AAR existence check + emulator tests |
| `settings.gradle.kts` | No changes needed (core:rdp already included) |

---

### Task 1: RdpConnectionParams Data Class

**Files:**
- Create: `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpConnectionParams.kt`
- Test: `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpConnectionParamsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpConnectionParamsTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.RdpRoute
import com.gatecontrol.android.network.RdpRouteStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpConnectionParamsTest {

    private val fullRoute = RdpRoute(
        id = 1,
        name = "Test Server",
        host = "10.0.0.10",
        port = 3389,
        externalHostname = null,
        externalPort = null,
        accessMode = "direct",
        credentialMode = "full",
        domain = "CORP",
        resolutionMode = "custom",
        resolutionWidth = 1920,
        resolutionHeight = 1080,
        multiMonitor = false,
        colorDepth = 32,
        redirectClipboard = true,
        redirectPrinters = false,
        redirectDrives = true,
        audioMode = "local",
        networkProfile = "auto",
        sessionTimeout = 3600,
        adminSession = false,
        wolEnabled = false,
        maintenanceEnabled = false,
        status = RdpRouteStatus(online = true, lastCheck = null)
    )

    @Test
    fun `fromRoute maps all fields correctly`() {
        val params = RdpConnectionParams.fromRoute(
            route = fullRoute,
            username = "admin",
            password = "secret",
            domain = "CORP"
        )

        assertEquals("10.0.0.10", params.host)
        assertEquals(3389, params.port)
        assertEquals("admin", params.username)
        assertEquals("secret", params.password)
        assertEquals("CORP", params.domain)
        assertEquals(1920, params.resolutionWidth)
        assertEquals(1080, params.resolutionHeight)
        assertEquals(32, params.colorDepth)
        assertTrue(params.redirectClipboard)
        assertFalse(params.redirectPrinters)
        assertTrue(params.redirectDrives)
        assertEquals("local", params.audioMode)
        assertFalse(params.adminSession)
    }

    @Test
    fun `fromRoute uses defaults for null optional fields`() {
        val minimalRoute = fullRoute.copy(
            resolutionWidth = null,
            resolutionHeight = null,
            colorDepth = null,
            redirectClipboard = null,
            redirectPrinters = null,
            redirectDrives = null,
            audioMode = null,
            adminSession = null
        )

        val params = RdpConnectionParams.fromRoute(
            route = minimalRoute,
            username = null,
            password = null,
            domain = null
        )

        assertEquals(0, params.resolutionWidth)
        assertEquals(0, params.resolutionHeight)
        assertEquals(32, params.colorDepth)
        assertFalse(params.redirectClipboard)
        assertFalse(params.redirectPrinters)
        assertFalse(params.redirectDrives)
        assertEquals("local", params.audioMode)
        assertFalse(params.adminSession)
        assertNull(params.username)
        assertNull(params.password)
    }

    @Test
    fun `fromRoute prefers explicit domain over route domain`() {
        val params = RdpConnectionParams.fromRoute(
            route = fullRoute,
            username = "admin",
            password = null,
            domain = "OVERRIDE"
        )
        assertEquals("OVERRIDE", params.domain)
    }

    @Test
    fun `fromRoute falls back to route domain when explicit is null`() {
        val params = RdpConnectionParams.fromRoute(
            route = fullRoute,
            username = "admin",
            password = null,
            domain = null
        )
        assertEquals("CORP", params.domain)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /root/android-client && ./gradlew :core:rdp:test --tests "com.gatecontrol.android.rdp.RdpConnectionParamsTest" --continue`
Expected: FAIL — `RdpConnectionParams` class not found.

- [ ] **Step 3: Write minimal implementation**

Create `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpConnectionParams.kt`:

```kotlin
package com.gatecontrol.android.rdp

import com.gatecontrol.android.network.RdpRoute

data class RdpConnectionParams(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val domain: String?,
    val resolutionWidth: Int,
    val resolutionHeight: Int,
    val colorDepth: Int,
    val redirectClipboard: Boolean,
    val redirectPrinters: Boolean,
    val redirectDrives: Boolean,
    val audioMode: String,
    val adminSession: Boolean,
    val routeName: String
) {
    companion object {
        fun fromRoute(
            route: RdpRoute,
            username: String?,
            password: String?,
            domain: String?
        ): RdpConnectionParams = RdpConnectionParams(
            host = route.host,
            port = route.port,
            username = username,
            password = password,
            domain = domain ?: route.domain,
            resolutionWidth = route.resolutionWidth ?: 0,
            resolutionHeight = route.resolutionHeight ?: 0,
            colorDepth = route.colorDepth ?: 32,
            redirectClipboard = route.redirectClipboard ?: false,
            redirectPrinters = route.redirectPrinters ?: false,
            redirectDrives = route.redirectDrives ?: false,
            audioMode = route.audioMode ?: "local",
            adminSession = route.adminSession ?: false,
            routeName = route.name
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /root/android-client && ./gradlew :core:rdp:test --tests "com.gatecontrol.android.rdp.RdpConnectionParamsTest" --continue`
Expected: PASS (all 4 tests green).

- [ ] **Step 5: Commit**

```bash
cd /root/android-client
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpConnectionParams.kt \
        core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpConnectionParamsTest.kt
git commit -m "feat: add RdpConnectionParams data class with factory method"
```

---

### Task 2: Extend RdpProgress Enum

**Files:**
- Modify: `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpModels.kt:11-16`

- [ ] **Step 1: Update RdpProgress enum to include SERVICE_START step**

In `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpModels.kt`, replace the `RdpProgress` enum:

```kotlin
enum class RdpProgress(val step: Int) {
    VPN_CHECK(1),
    TCP_CHECK(2),
    CREDENTIALS(3),
    CLIENT_LAUNCH(4),
    SESSION_START(5),
    SERVICE_START(6),
    COMPLETE(7)
}
```

- [ ] **Step 2: Run existing tests to verify nothing breaks**

Run: `cd /root/android-client && ./gradlew test --continue`
Expected: All existing tests pass (step numbers shifted but tests use enum names, not ints).

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpModels.kt
git commit -m "feat: add SERVICE_START step to RdpProgress enum"
```

---

### Task 3: Add i18n Strings for Embedded RDP

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-de/strings.xml`

- [ ] **Step 1: Add English strings**

In `app/src/main/res/values/strings.xml`, add after line 85 (after `rdp_error_server`):

```xml
    <string name="rdp_embedded_connecting">Connecting to remote desktop&#8230;</string>
    <string name="rdp_embedded_reconnecting">Reconnecting&#8230; (%1$d/%2$d)</string>
    <string name="rdp_embedded_disconnect_confirm">End RDP session?</string>
    <string name="rdp_embedded_disconnect_message">The remote desktop session will be disconnected.</string>
    <string name="rdp_embedded_connection_lost">Connection lost</string>
    <string name="rdp_embedded_reconnect_failed">Reconnection failed after %1$d attempts</string>
    <string name="rdp_progress_service">Starting service&#8230;</string>
    <string name="notif_rdp_session">RDP: %1$s</string>
    <string name="notif_rdp_duration">Session: %1$s</string>
    <string name="notif_rdp_disconnect">Disconnect</string>
```

- [ ] **Step 2: Add German strings**

In `app/src/main/res/values-de/strings.xml`, add after line 85 (after `rdp_error_server`):

```xml
    <string name="rdp_embedded_connecting">Verbinde mit Remote-Desktop&#8230;</string>
    <string name="rdp_embedded_reconnecting">Verbinde erneut&#8230; (%1$d/%2$d)</string>
    <string name="rdp_embedded_disconnect_confirm">RDP-Sitzung beenden?</string>
    <string name="rdp_embedded_disconnect_message">Die Remote-Desktop-Sitzung wird getrennt.</string>
    <string name="rdp_embedded_connection_lost">Verbindung verloren</string>
    <string name="rdp_embedded_reconnect_failed">Wiederverbindung nach %1$d Versuchen fehlgeschlagen</string>
    <string name="rdp_progress_service">Starte Dienst&#8230;</string>
    <string name="notif_rdp_session">RDP: %1$s</string>
    <string name="notif_rdp_duration">Sitzung: %1$s</string>
    <string name="notif_rdp_disconnect">Trennen</string>
```

- [ ] **Step 3: Run lint to verify no string issues**

Run: `cd /root/android-client && ./gradlew lintRelease 2>&1 | grep -i "string\|i18n\|translation" | head -20`
Expected: No errors about missing translations.

- [ ] **Step 4: Commit**

```bash
cd /root/android-client
git add app/src/main/res/values/strings.xml app/src/main/res/values-de/strings.xml
git commit -m "feat: add i18n strings for embedded RDP session (EN+DE)"
```

---

### Task 4: Extend RdpEmbeddedClient with launchSession

**Files:**
- Modify: `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt`
- Create: `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Context
import android.content.Intent
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpEmbeddedClientTest {

    private val client = RdpEmbeddedClient()

    @Test
    fun `isAvailable returns false when FreeRDP not on classpath`() {
        // FreeRDP AAR is not loaded in test classpath
        assertFalse(client.isAvailable())
    }

    @Test
    fun `buildSessionIntent contains all connection params as extras`() {
        val params = RdpConnectionParams(
            host = "10.0.0.5",
            port = 3389,
            username = "admin",
            password = "pass123",
            domain = "CORP",
            resolutionWidth = 1920,
            resolutionHeight = 1080,
            colorDepth = 32,
            redirectClipboard = true,
            redirectPrinters = false,
            redirectDrives = true,
            audioMode = "local",
            adminSession = false,
            routeName = "Dev Server"
        )

        val intent = client.buildSessionIntent(mockk(relaxed = true), params)

        assertEquals("10.0.0.5", intent.getStringExtra("rdp_host"))
        assertEquals(3389, intent.getIntExtra("rdp_port", 0))
        assertEquals("admin", intent.getStringExtra("rdp_username"))
        assertEquals("pass123", intent.getStringExtra("rdp_password"))
        assertEquals("CORP", intent.getStringExtra("rdp_domain"))
        assertEquals(1920, intent.getIntExtra("rdp_resolution_width", 0))
        assertEquals(1080, intent.getIntExtra("rdp_resolution_height", 0))
        assertEquals(32, intent.getIntExtra("rdp_color_depth", 0))
        assertTrue(intent.getBooleanExtra("rdp_redirect_clipboard", false))
        assertFalse(intent.getBooleanExtra("rdp_redirect_printers", true))
        assertTrue(intent.getBooleanExtra("rdp_redirect_drives", false))
        assertEquals("local", intent.getStringExtra("rdp_audio_mode"))
        assertFalse(intent.getBooleanExtra("rdp_admin_session", true))
        assertEquals("Dev Server", intent.getStringExtra("rdp_route_name"))
    }

    @Test
    fun `buildSessionIntent has FLAG_ACTIVITY_NEW_TASK`() {
        val params = RdpConnectionParams(
            host = "10.0.0.5", port = 3389, username = null, password = null,
            domain = null, resolutionWidth = 0, resolutionHeight = 0,
            colorDepth = 32, redirectClipboard = false, redirectPrinters = false,
            redirectDrives = false, audioMode = "local", adminSession = false,
            routeName = "Test"
        )
        val intent = client.buildSessionIntent(mockk(relaxed = true), params)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `buildSessionIntent handles null credentials`() {
        val params = RdpConnectionParams(
            host = "10.0.0.5", port = 3389, username = null, password = null,
            domain = null, resolutionWidth = 0, resolutionHeight = 0,
            colorDepth = 32, redirectClipboard = false, redirectPrinters = false,
            redirectDrives = false, audioMode = "local", adminSession = false,
            routeName = "Test"
        )
        val intent = client.buildSessionIntent(mockk(relaxed = true), params)
        assertEquals(null, intent.getStringExtra("rdp_username"))
        assertEquals(null, intent.getStringExtra("rdp_password"))
        assertEquals(null, intent.getStringExtra("rdp_domain"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /root/android-client && ./gradlew :core:rdp:test --tests "com.gatecontrol.android.rdp.RdpEmbeddedClientTest" --continue`
Expected: FAIL — `buildSessionIntent` method not found.

- [ ] **Step 3: Implement buildSessionIntent and launchSession**

Replace `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Embedded FreeRDP client for Android.
 *
 * This client wraps the FreeRDP Android library to provide an in-app RDP experience
 * without requiring an external RDP application. When the FreeRDP AAR is not available,
 * [isAvailable] returns false and the [RdpManager] should fall back to [RdpExternalClient].
 */
class RdpEmbeddedClient {

    companion object {
        private const val FREERDP_CLASS = "com.freerdp.freerdpcore.services.LibFreeRDP"

        // Intent extra keys
        const val EXTRA_HOST = "rdp_host"
        const val EXTRA_PORT = "rdp_port"
        const val EXTRA_USERNAME = "rdp_username"
        const val EXTRA_PASSWORD = "rdp_password"
        const val EXTRA_DOMAIN = "rdp_domain"
        const val EXTRA_RESOLUTION_WIDTH = "rdp_resolution_width"
        const val EXTRA_RESOLUTION_HEIGHT = "rdp_resolution_height"
        const val EXTRA_COLOR_DEPTH = "rdp_color_depth"
        const val EXTRA_REDIRECT_CLIPBOARD = "rdp_redirect_clipboard"
        const val EXTRA_REDIRECT_PRINTERS = "rdp_redirect_printers"
        const val EXTRA_REDIRECT_DRIVES = "rdp_redirect_drives"
        const val EXTRA_AUDIO_MODE = "rdp_audio_mode"
        const val EXTRA_ADMIN_SESSION = "rdp_admin_session"
        const val EXTRA_ROUTE_NAME = "rdp_route_name"
    }

    /**
     * Returns true if the FreeRDP library is available on the classpath.
     */
    fun isAvailable(): Boolean {
        return try {
            Class.forName(FREERDP_CLASS)
            true
        } catch (_: ClassNotFoundException) {
            Timber.d("FreeRDP library not available — embedded client disabled")
            false
        }
    }

    /**
     * Build an Intent to launch [RdpSessionActivity] with the given connection parameters.
     * The activity class name is resolved by convention (same app package).
     */
    fun buildSessionIntent(context: Context, params: RdpConnectionParams): Intent {
        val intent = Intent().apply {
            setClassName(context.packageName, "com.gatecontrol.android.rdp.RdpSessionActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(EXTRA_HOST, params.host)
            putExtra(EXTRA_PORT, params.port)
            putExtra(EXTRA_USERNAME, params.username)
            putExtra(EXTRA_PASSWORD, params.password)
            putExtra(EXTRA_DOMAIN, params.domain)
            putExtra(EXTRA_RESOLUTION_WIDTH, params.resolutionWidth)
            putExtra(EXTRA_RESOLUTION_HEIGHT, params.resolutionHeight)
            putExtra(EXTRA_COLOR_DEPTH, params.colorDepth)
            putExtra(EXTRA_REDIRECT_CLIPBOARD, params.redirectClipboard)
            putExtra(EXTRA_REDIRECT_PRINTERS, params.redirectPrinters)
            putExtra(EXTRA_REDIRECT_DRIVES, params.redirectDrives)
            putExtra(EXTRA_AUDIO_MODE, params.audioMode)
            putExtra(EXTRA_ADMIN_SESSION, params.adminSession)
            putExtra(EXTRA_ROUTE_NAME, params.routeName)
        }
        return intent
    }

    /**
     * Launch the embedded RDP session activity.
     */
    fun launchSession(context: Context, params: RdpConnectionParams) {
        val intent = buildSessionIntent(context, params)
        context.startActivity(intent)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /root/android-client && ./gradlew :core:rdp:test --tests "com.gatecontrol.android.rdp.RdpEmbeddedClientTest" --continue`
Expected: PASS (all 4 tests green).

- [ ] **Step 5: Run all existing tests to check for regressions**

Run: `cd /root/android-client && ./gradlew test --continue`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
cd /root/android-client
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpEmbeddedClient.kt \
        core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpEmbeddedClientTest.kt
git commit -m "feat: add launchSession and buildSessionIntent to RdpEmbeddedClient"
```

---

### Task 5: Create RdpSessionService (Foreground Notification)

**Files:**
- Create: `app/src/main/java/com/gatecontrol/android/service/RdpSessionService.kt`
- Create: `app/src/test/java/com/gatecontrol/android/service/RdpSessionServiceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/gatecontrol/android/service/RdpSessionServiceTest.kt`:

```kotlin
package com.gatecontrol.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RdpSessionServiceTest {

    @Test
    fun `CHANNEL_ID is rdp_session`() {
        assertEquals("rdp_session", RdpSessionService.CHANNEL_ID)
    }

    @Test
    fun `NOTIF_ID is distinct from VPN notification`() {
        val rdpNotifId = RdpSessionService.NOTIF_ID
        val vpnNotifId = VpnForegroundService.NOTIF_ID
        assert(rdpNotifId != vpnNotifId) { "RDP and VPN notification IDs must be different" }
    }

    @Test
    fun `ACTION_DISCONNECT is namespaced`() {
        assertEquals(
            "com.gatecontrol.android.ACTION_RDP_DISCONNECT",
            RdpSessionService.ACTION_DISCONNECT
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /root/android-client && ./gradlew :app:test --tests "com.gatecontrol.android.service.RdpSessionServiceTest" --continue`
Expected: FAIL — `RdpSessionService` not found.

- [ ] **Step 3: Implement RdpSessionService**

Create `app/src/main/java/com/gatecontrol/android/service/RdpSessionService.kt`:

```kotlin
package com.gatecontrol.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.gatecontrol.android.MainActivity
import com.gatecontrol.android.R
import com.gatecontrol.android.common.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class RdpSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "rdp_session"
        const val NOTIF_ID = 1002
        const val ACTION_DISCONNECT = "com.gatecontrol.android.ACTION_RDP_DISCONNECT"
        const val EXTRA_ROUTE_NAME = "route_name"
        const val EXTRA_CONNECTED_SINCE = "connected_since"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var updateJob: Job? = null

    private var routeName: String = ""
    private var connectedSinceMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Timber.d("RdpSessionService: disconnect action received")
            stopSelf()
            return START_NOT_STICKY
        }

        routeName = intent?.getStringExtra(EXTRA_ROUTE_NAME) ?: ""
        connectedSinceMs = intent?.getLongExtra(EXTRA_CONNECTED_SINCE, System.currentTimeMillis())
            ?: System.currentTimeMillis()

        startForeground(NOTIF_ID, buildNotification())
        startDurationUpdater()

        Timber.d("RdpSessionService: started, route=$routeName")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_rdp),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_rdp)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val disconnectIntent = Intent(this, RdpSessionService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 2, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val uptimeSec = (System.currentTimeMillis() - connectedSinceMs) / 1000
        val title = getString(R.string.notif_rdp_session, routeName)
        val body = getString(R.string.notif_rdp_duration, Formatters.formatDuration(uptimeSec))

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.notif_rdp_disconnect),
                disconnectPending,
            )
            .build()
    }

    private fun startDurationUpdater() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive) {
                delay(1_000)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification())
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /root/android-client && ./gradlew :app:test --tests "com.gatecontrol.android.service.RdpSessionServiceTest" --continue`
Expected: PASS (3 tests green).

- [ ] **Step 5: Commit**

```bash
cd /root/android-client
git add app/src/main/java/com/gatecontrol/android/service/RdpSessionService.kt \
        app/src/test/java/com/gatecontrol/android/service/RdpSessionServiceTest.kt
git commit -m "feat: add RdpSessionService foreground service with notification"
```

---

### Task 6: Create RdpSessionActivity (Fullscreen FreeRDP Host)

**Files:**
- Create: `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`
- Create: `app/src/test/java/com/gatecontrol/android/rdp/RdpSessionActivityTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/gatecontrol/android/rdp/RdpSessionActivityTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.Intent
import android.view.WindowManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RdpSessionActivityTest {

    @Test
    fun `parseConnectionParams extracts all extras from intent`() {
        val intent = Intent().apply {
            putExtra("rdp_host", "10.0.0.5")
            putExtra("rdp_port", 3389)
            putExtra("rdp_username", "admin")
            putExtra("rdp_password", "secret")
            putExtra("rdp_domain", "CORP")
            putExtra("rdp_resolution_width", 1920)
            putExtra("rdp_resolution_height", 1080)
            putExtra("rdp_color_depth", 32)
            putExtra("rdp_redirect_clipboard", true)
            putExtra("rdp_redirect_printers", false)
            putExtra("rdp_redirect_drives", true)
            putExtra("rdp_audio_mode", "local")
            putExtra("rdp_admin_session", false)
            putExtra("rdp_route_name", "Test Server")
        }

        val params = RdpSessionActivity.parseConnectionParams(intent)

        assertNotNull(params)
        assertEquals("10.0.0.5", params!!.host)
        assertEquals(3389, params.port)
        assertEquals("admin", params.username)
        assertEquals("secret", params.password)
        assertEquals("CORP", params.domain)
        assertEquals(1920, params.resolutionWidth)
        assertEquals(1080, params.resolutionHeight)
        assertEquals(32, params.colorDepth)
        assertTrue(params.redirectClipboard)
        assertEquals("local", params.audioMode)
    }

    @Test
    fun `parseConnectionParams returns null when host is missing`() {
        val intent = Intent().apply {
            putExtra("rdp_port", 3389)
        }
        val params = RdpSessionActivity.parseConnectionParams(intent)
        assertEquals(null, params)
    }

    @Test
    fun `FLAG_SECURE constant is correct`() {
        assertEquals(
            WindowManager.LayoutParams.FLAG_SECURE,
            RdpSessionActivity.WINDOW_FLAG_SECURE
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /root/android-client && ./gradlew :app:test --tests "com.gatecontrol.android.rdp.RdpSessionActivityTest" --continue`
Expected: FAIL — `RdpSessionActivity` not found.

- [ ] **Step 3: Implement RdpSessionActivity**

Create `app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.gatecontrol.android.R
import com.gatecontrol.android.service.RdpSessionService
import timber.log.Timber

class RdpSessionActivity : AppCompatActivity() {

    companion object {
        val WINDOW_FLAG_SECURE = WindowManager.LayoutParams.FLAG_SECURE

        fun parseConnectionParams(intent: Intent): RdpConnectionParams? {
            val host = intent.getStringExtra("rdp_host") ?: return null
            return RdpConnectionParams(
                host = host,
                port = intent.getIntExtra("rdp_port", 3389),
                username = intent.getStringExtra("rdp_username"),
                password = intent.getStringExtra("rdp_password"),
                domain = intent.getStringExtra("rdp_domain"),
                resolutionWidth = intent.getIntExtra("rdp_resolution_width", 0),
                resolutionHeight = intent.getIntExtra("rdp_resolution_height", 0),
                colorDepth = intent.getIntExtra("rdp_color_depth", 32),
                redirectClipboard = intent.getBooleanExtra("rdp_redirect_clipboard", false),
                redirectPrinters = intent.getBooleanExtra("rdp_redirect_printers", false),
                redirectDrives = intent.getBooleanExtra("rdp_redirect_drives", false),
                audioMode = intent.getStringExtra("rdp_audio_mode") ?: "local",
                adminSession = intent.getBooleanExtra("rdp_admin_session", false),
                routeName = intent.getStringExtra("rdp_route_name") ?: ""
            )
        }
    }

    private var params: RdpConnectionParams? = null
    private var sessionStartMs: Long = 0L

    // FreeRDP references — populated when AAR is available
    private var freerdpSession: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security: prevent screenshots and screen recording
        window.setFlags(WINDOW_FLAG_SECURE, WINDOW_FLAG_SECURE)

        // Immersive fullscreen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        params = parseConnectionParams(intent)
        if (params == null) {
            Timber.e("RdpSessionActivity: no connection params in intent")
            finish()
            return
        }

        sessionStartMs = System.currentTimeMillis()

        // Create layout: FrameLayout for FreeRDP SessionView + floating toolbar
        val rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        // Start FreeRDP connection
        initFreeRdpSession(rootLayout, params!!)

        // Start foreground service
        startRdpService()

        // Handle back press with confirmation dialog
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showDisconnectConfirmation()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFreeRdp()
        stopRdpService()
        // Wipe credentials from memory
        params = null
    }

    private fun initFreeRdpSession(container: FrameLayout, params: RdpConnectionParams) {
        try {
            // Check if FreeRDP is available
            val libClass = Class.forName("com.freerdp.freerdpcore.services.LibFreeRDP")

            // Initialize FreeRDP session via reflection
            val newInstanceMethod = libClass.getMethod("newInstance", android.content.Context::class.java)
            freerdpSession = newInstanceMethod.invoke(null, this) as Long

            // Configure connection settings
            configureFreeRdpSettings(libClass, params)

            // Create SessionView and add to container
            val sessionViewClass = Class.forName("com.freerdp.freerdpcore.presentation.SessionView")
            val sessionView = sessionViewClass
                .getConstructor(android.content.Context::class.java)
                .newInstance(this) as View
            container.addView(sessionView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Connect
            val connectMethod = libClass.getMethod("connect", Long::class.javaPrimitiveType)
            connectMethod.invoke(null, freerdpSession)

            Timber.i("FreeRDP session started for ${params.routeName}")
        } catch (e: ClassNotFoundException) {
            Timber.e("FreeRDP library not found — cannot start embedded session")
            Toast.makeText(this, getString(R.string.rdp_embedded_connection_lost), Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FreeRDP session")
            Toast.makeText(this, getString(R.string.rdp_embedded_connection_lost), Toast.LENGTH_LONG).show()
            finish()
        }

        // Add floating toolbar
        addFloatingToolbar(container)
    }

    private fun configureFreeRdpSettings(libClass: Class<*>, params: RdpConnectionParams) {
        val setMethod = libClass.getMethod(
            "setConnectionInfo",
            Long::class.javaPrimitiveType,     // session
            String::class.java,                 // hostname
            Int::class.javaPrimitiveType,       // port
            String::class.java,                 // username
            String::class.java,                 // password
            String::class.java,                 // domain
            Int::class.javaPrimitiveType,       // width
            Int::class.javaPrimitiveType,       // height
            Int::class.javaPrimitiveType,       // colorDepth
            Boolean::class.javaPrimitiveType,   // redirectClipboard
            Boolean::class.javaPrimitiveType,   // redirectPrinters
            Boolean::class.javaPrimitiveType,   // redirectDrives
            String::class.java,                 // audioMode
            Boolean::class.javaPrimitiveType,   // adminSession
            Int::class.javaPrimitiveType        // autoReconnectMaxRetries
        )
        setMethod.invoke(
            null,
            freerdpSession,
            params.host,
            params.port,
            params.username ?: "",
            params.password ?: "",
            params.domain ?: "",
            params.resolutionWidth,
            params.resolutionHeight,
            params.colorDepth,
            params.redirectClipboard,
            params.redirectPrinters,
            params.redirectDrives,
            params.audioMode,
            params.adminSession,
            5 // auto-reconnect max retries
        )
    }

    private fun addFloatingToolbar(container: FrameLayout) {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC333333.toInt())
            setPadding(16, 8, 16, 8)
        }

        // Disconnect button
        val disconnectBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setOnClickListener { showDisconnectConfirmation() }
            contentDescription = getString(R.string.rdp_disconnect)
        }
        toolbar.addView(disconnectBtn)

        // Keyboard toggle button
        val keyboardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            setOnClickListener { toggleSoftKeyboard() }
            contentDescription = "Keyboard"
        }
        toolbar.addView(keyboardBtn)

        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 16
        }

        container.addView(toolbar, layoutParams)
    }

    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rdp_embedded_disconnect_confirm))
            .setMessage(getString(R.string.rdp_embedded_disconnect_message))
            .setPositiveButton(getString(R.string.rdp_disconnect)) { _, _ -> finish() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun disconnectFreeRdp() {
        if (freerdpSession != 0L) {
            try {
                val libClass = Class.forName("com.freerdp.freerdpcore.services.LibFreeRDP")
                val disconnectMethod = libClass.getMethod("disconnect", Long::class.javaPrimitiveType)
                disconnectMethod.invoke(null, freerdpSession)
                val freeMethod = libClass.getMethod("freeSession", Long::class.javaPrimitiveType)
                freeMethod.invoke(null, freerdpSession)
            } catch (e: Exception) {
                Timber.w(e, "Error disconnecting FreeRDP session")
            }
            freerdpSession = 0L
        }
    }

    private fun startRdpService() {
        val serviceIntent = Intent(this, RdpSessionService::class.java).apply {
            putExtra(RdpSessionService.EXTRA_ROUTE_NAME, params?.routeName ?: "")
            putExtra(RdpSessionService.EXTRA_CONNECTED_SINCE, sessionStartMs)
        }
        startForegroundService(serviceIntent)
    }

    private fun stopRdpService() {
        stopService(Intent(this, RdpSessionService::class.java))
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.toggleSoftInput(
            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
            0
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /root/android-client && ./gradlew :app:test --tests "com.gatecontrol.android.rdp.RdpSessionActivityTest" --continue`
Expected: PASS (3 tests green).

- [ ] **Step 5: Commit**

```bash
cd /root/android-client
git add app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt \
        app/src/test/java/com/gatecontrol/android/rdp/RdpSessionActivityTest.kt
git commit -m "feat: add RdpSessionActivity with fullscreen FreeRDP hosting"
```

---

### Task 7: Update RdpManager to Use Embedded Client

**Files:**
- Modify: `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpManager.kt:149-170`

- [ ] **Step 1: Replace TODO and add step 8**

In `core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpManager.kt`, replace lines 149-170 (Step 5 through Step 6 start):

```kotlin
        // Step 5: Launch client (prefer embedded FreeRDP, fall back to external)
        onProgress(RdpProgress.CLIENT_LAUNCH)
        val useEmbedded = embeddedClient.isAvailable()
        val isExternal = !useEmbedded

        if (useEmbedded) {
            val params = RdpConnectionParams.fromRoute(
                route = route,
                username = resolvedUsername,
                password = resolvedPassword,
                domain = resolvedDomain
            )
            try {
                embeddedClient.launchSession(context, params)
            } catch (e: Exception) {
                credentialHandler.clear()
                return ConnectResult.Error(
                    "Failed to launch embedded RDP client: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        } else {
            // Fall back to external client — copy password to clipboard
            // (rdp:// URI and .rdp files don't support password passing)
            if (!resolvedPassword.isNullOrEmpty()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("rdp-password", resolvedPassword))
            }

            val intent = externalClient.launchIntent(
                host = host,
                port = port,
                username = resolvedUsername,
                domain = resolvedDomain
            )
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                credentialHandler.clear()
                return ConnectResult.Error(
                    "No RDP client could be launched: ${e.message}",
                    RdpProgress.CLIENT_LAUNCH
                )
            }
        }
```

- [ ] **Step 2: Run all tests to verify no regressions**

Run: `cd /root/android-client && ./gradlew test --continue`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add core/rdp/src/main/java/com/gatecontrol/android/rdp/RdpManager.kt
git commit -m "feat: route RDP connections through embedded client with external fallback"
```

---

### Task 8: Update AndroidManifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Register RdpSessionActivity and RdpSessionService**

In `app/src/main/AndroidManifest.xml`, add after the `BootReceiver` closing tag (line 38), before `<provider`:

```xml
        <activity
            android:name=".rdp.RdpSessionActivity"
            android:exported="false"
            android:theme="@style/Theme.GateControl"
            android:screenOrientation="landscape"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:windowSoftInputMode="adjustResize" />

        <service
            android:name=".service.RdpSessionService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />
```

- [ ] **Step 2: Build to verify manifest is valid**

Run: `cd /root/android-client && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add app/src/main/AndroidManifest.xml
git commit -m "feat: register RdpSessionActivity and RdpSessionService in manifest"
```

---

### Task 9: Update ProGuard Rules

**Files:**
- Modify: `app/proguard-rules.pro`
- Modify: `core/rdp/consumer-rules.pro`

- [ ] **Step 1: Add FreeRDP rules to app proguard**

In `app/proguard-rules.pro`, add after line 29 (after WireGuard section):

```
# ── FreeRDP ───────────────────────────────────────────────
-keep class com.freerdp.** { *; }
-dontwarn com.freerdp.**
-keep class com.gatecontrol.android.rdp.RdpConnectionParams { *; }
```

- [ ] **Step 2: Add FreeRDP rules to consumer-rules.pro**

Write to `core/rdp/consumer-rules.pro`:

```
# FreeRDP native library
-keep class com.freerdp.** { *; }
-dontwarn com.freerdp.**

# RDP models used via reflection/intents
-keep class com.gatecontrol.android.rdp.RdpConnectionParams { *; }
```

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add app/proguard-rules.pro core/rdp/consumer-rules.pro
git commit -m "feat: add FreeRDP ProGuard keep rules"
```

---

### Task 10: Activate AAR Dependency in build.gradle.kts

**Files:**
- Modify: `core/rdp/build.gradle.kts:40`

- [ ] **Step 1: Uncomment FreeRDP AAR dependency**

In `core/rdp/build.gradle.kts`, replace line 40:

```kotlin
    // FreeRDP - uncomment when AAR is available locally
    // implementation(files("libs/freerdp-android.aar"))
```

with:

```kotlin
    // FreeRDP embedded client AAR (built from CallMeTechie/aFreeRDP fork)
    implementation(files("libs/freerdp-android.aar"))
```

- [ ] **Step 2: Create libs directory placeholder**

Run: `mkdir -p /root/android-client/core/rdp/libs && touch /root/android-client/core/rdp/libs/.gitkeep`

Note: The actual `freerdp-android.aar` will be placed here by the `freerdp-build.yml` CI workflow (Task 12). Until the AAR exists, `RdpEmbeddedClient.isAvailable()` returns false and the external client fallback is used.

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add core/rdp/build.gradle.kts core/rdp/libs/.gitkeep
git commit -m "feat: activate FreeRDP AAR dependency in build config"
```

---

### Task 11: Security Tests

**Files:**
- Create: `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpCredentialSecurityTest.kt`

- [ ] **Step 1: Write credential security tests**

Create `core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpCredentialSecurityTest.kt`:

```kotlin
package com.gatecontrol.android.rdp

import android.content.ClipboardManager
import android.content.Context
import com.gatecontrol.android.network.ApiClient
import com.gatecontrol.android.network.RdpConnectResponse
import com.gatecontrol.android.network.RdpConnection
import com.gatecontrol.android.network.RdpRouteStatus
import com.gatecontrol.android.network.RdpRouteStatusResponse
import com.gatecontrol.android.network.RdpSessionResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RdpCredentialSecurityTest {

    private lateinit var rdpManager: RdpManager
    private lateinit var credentialHandler: RdpCredentialHandler
    private lateinit var embeddedClient: RdpEmbeddedClient
    private lateinit var externalClient: RdpExternalClient
    private lateinit var monitor: RdpMonitor
    private lateinit var context: Context
    private lateinit var clipboardManager: ClipboardManager

    private val testRoute = com.gatecontrol.android.network.RdpRoute(
        id = 1, name = "Test", host = "10.0.0.10", port = 3389,
        externalHostname = null, externalPort = null,
        accessMode = "direct", credentialMode = "full", domain = null,
        resolutionMode = null, resolutionWidth = null, resolutionHeight = null,
        multiMonitor = null, colorDepth = null, redirectClipboard = null,
        redirectPrinters = null, redirectDrives = null, audioMode = null,
        networkProfile = null, sessionTimeout = null, adminSession = null,
        wolEnabled = false, maintenanceEnabled = false,
        status = RdpRouteStatus(online = true, lastCheck = null)
    )

    @BeforeEach
    fun setUp() {
        clipboardManager = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboardManager
        }
        credentialHandler = mockk(relaxed = true)
        embeddedClient = mockk(relaxed = true)
        externalClient = mockk(relaxed = true)
        monitor = mockk(relaxed = true)

        rdpManager = RdpManager(
            context = context,
            credentialHandler = credentialHandler,
            externalClient = externalClient,
            embeddedClient = embeddedClient,
            monitor = monitor,
            wolClient = mockk(relaxed = true)
        )
    }

    @Test
    fun `embedded client does not copy password to clipboard`() = runTest {
        every { embeddedClient.isAvailable() } returns true
        every { credentialHandler.generatePublicKey() } returns "pubkey"
        every { credentialHandler.decryptCredentials(any()) } returns RdpCredentials("admin", "secret", null)

        val apiClient = mockk<ApiClient> {
            coEvery { getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(true, RdpRouteStatus(true, null))
            coEvery { getRdpConnection(any(), any()) } returns RdpConnectResponse(
                ok = true,
                connection = RdpConnection(1, "Test", "10.0.0.10", 3389, "full", null, mockk(relaxed = true))
            )
            coEvery { startRdpSession(any()) } returns RdpSessionResponse(
                ok = true,
                session = com.gatecontrol.android.network.RdpSession(1, 1, "2026-01-01", null)
            )
        }

        rdpManager.connect(
            route = testRoute,
            apiClient = apiClient,
            isVpnConnected = true
        )

        // Clipboard should NOT be written for embedded client
        verify(exactly = 0) { clipboardManager.setPrimaryClip(any()) }
    }

    @Test
    fun `credentialHandler clear is called after connect`() = runTest {
        every { embeddedClient.isAvailable() } returns true
        every { credentialHandler.generatePublicKey() } returns "pubkey"
        every { credentialHandler.decryptCredentials(any()) } returns RdpCredentials("admin", "secret", null)

        val apiClient = mockk<ApiClient> {
            coEvery { getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(true, RdpRouteStatus(true, null))
            coEvery { getRdpConnection(any(), any()) } returns RdpConnectResponse(
                ok = true,
                connection = RdpConnection(1, "Test", "10.0.0.10", 3389, "full", null, mockk(relaxed = true))
            )
            coEvery { startRdpSession(any()) } returns RdpSessionResponse(
                ok = true,
                session = com.gatecontrol.android.network.RdpSession(1, 1, "2026-01-01", null)
            )
        }

        rdpManager.connect(
            route = testRoute,
            apiClient = apiClient,
            isVpnConnected = true
        )

        verify(atLeast = 1) { credentialHandler.clear() }
    }

    @Test
    fun `credentialHandler clear is called on credential fetch error`() = runTest {
        every { embeddedClient.isAvailable() } returns true
        every { credentialHandler.generatePublicKey() } returns "pubkey"

        val apiClient = mockk<ApiClient> {
            coEvery { getRdpRouteStatus(any()) } returns RdpRouteStatusResponse(true, RdpRouteStatus(true, null))
            coEvery { getRdpConnection(any(), any()) } throws RuntimeException("Decrypt failed")
        }

        val result = rdpManager.connect(
            route = testRoute,
            apiClient = apiClient,
            isVpnConnected = true
        )

        assertTrue(result is RdpManager.ConnectResult.Error)
        verify(atLeast = 1) { credentialHandler.clear() }
    }
}
```

- [ ] **Step 2: Run security tests**

Run: `cd /root/android-client && ./gradlew :core:rdp:test --tests "com.gatecontrol.android.rdp.RdpCredentialSecurityTest" --continue`
Expected: PASS (3 security tests green).

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add core/rdp/src/test/java/com/gatecontrol/android/rdp/RdpCredentialSecurityTest.kt
git commit -m "test: add credential security tests for embedded RDP client"
```

---

### Task 12: Update ConnectingView for New Progress Step

**Files:**
- Modify: `app/src/main/java/com/gatecontrol/android/ui/rdp/RdpConnectSheet.kt:233-240`

- [ ] **Step 1: Add SERVICE_START to progress steps list**

In `app/src/main/java/com/gatecontrol/android/ui/rdp/RdpConnectSheet.kt`, replace lines 233-240:

```kotlin
        val steps = listOf(
            RdpProgress.VPN_CHECK to stringResource(R.string.rdp_progress_vpn),
            RdpProgress.TCP_CHECK to stringResource(R.string.rdp_progress_tcp),
            RdpProgress.CREDENTIALS to stringResource(R.string.rdp_progress_creds),
            RdpProgress.CLIENT_LAUNCH to stringResource(R.string.rdp_progress_launch),
            RdpProgress.SESSION_START to stringResource(R.string.rdp_progress_session),
            RdpProgress.SERVICE_START to stringResource(R.string.rdp_progress_service),
            RdpProgress.COMPLETE to stringResource(R.string.rdp_progress_done)
        )
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd /root/android-client && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add app/src/main/java/com/gatecontrol/android/ui/rdp/RdpConnectSheet.kt
git commit -m "feat: add SERVICE_START progress step to RDP connect sheet"
```

---

### Task 13: Create FreeRDP Build CI Workflow

**Files:**
- Create: `.github/workflows/freerdp-build.yml`
- Create: `freerdp/VERSION`

- [ ] **Step 1: Create VERSION file**

Create `freerdp/VERSION`:

```
3.10.3
```

- [ ] **Step 2: Create CI workflow**

Create `.github/workflows/freerdp-build.yml`:

```yaml
name: Build FreeRDP AAR

on:
  workflow_dispatch:
    inputs:
      freerdp_ref:
        description: 'FreeRDP git ref to build (tag or branch)'
        required: false
        default: 'master'
  push:
    paths:
      - 'freerdp/**'

env:
  FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true

jobs:
  build-aar:
    name: Build FreeRDP AAR
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - name: Setup Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Setup NDK
        run: |
          sdkmanager --install "ndk;26.1.10909125"
          echo "ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125" >> "$GITHUB_ENV"

      - name: Build FreeRDP native libraries
        working-directory: freerdp
        run: |
          mkdir -p build && cd build
          cmake .. \
            -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
            -DANDROID_ABI="arm64-v8a" \
            -DANDROID_PLATFORM=android-31 \
            -DWITH_CLIENT=ON \
            -DWITH_SERVER=OFF \
            -DWITH_OPENSSL=ON \
            -DBUILD_SHARED_LIBS=ON
          cmake --build . --parallel $(nproc)

      - name: Build AAR
        working-directory: freerdp
        run: |
          ./gradlew :freerdpcore:assembleRelease

      - name: Run FreeRDP tests
        working-directory: freerdp
        run: |
          cd build && ctest --output-on-failure --parallel $(nproc)

      - name: Validate AAR contents
        run: |
          AAR_PATH=$(find freerdp -name "*.aar" -path "*/release/*" | head -1)
          if [ -z "$AAR_PATH" ]; then
            echo "::error::No AAR file found"
            exit 1
          fi
          # Check for required ABIs
          unzip -l "$AAR_PATH" | grep -q "arm64-v8a" || { echo "::error::Missing arm64-v8a"; exit 1; }
          echo "AAR validated: $AAR_PATH"
          cp "$AAR_PATH" core/rdp/libs/freerdp-android.aar

      - name: Run Android unit tests with AAR
        run: ./gradlew :core:rdp:test --continue

      - name: Upload AAR artifact
        uses: actions/upload-artifact@v4
        with:
          name: freerdp-android-aar
          path: core/rdp/libs/freerdp-android.aar

      - name: Commit AAR if changed
        run: |
          if git diff --name-only | grep -q "freerdp-android.aar"; then
            git config user.name "github-actions"
            git config user.email "github-actions@github.com"
            git add core/rdp/libs/freerdp-android.aar
            VERSION=$(cat freerdp/VERSION)
            git commit -m "chore: update FreeRDP AAR to v$VERSION"
            git push
          fi
```

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
mkdir -p freerdp
git add .github/workflows/freerdp-build.yml freerdp/VERSION
git commit -m "feat: add FreeRDP AAR build CI workflow"
```

---

### Task 14: Update PR Check and Release Workflows

**Files:**
- Modify: `.github/workflows/pr-check.yml`
- Modify: `.github/workflows/release.yml`

- [ ] **Step 1: Add security credential leak check to pr-check.yml**

In `.github/workflows/pr-check.yml`, add after the HTTP body logging check (after line 38):

```yaml
      - name: Check for credential leaks in logs
        run: |
          if grep -rn 'Timber\.\(d\|i\|w\|e\).*\(password\|Password\|PASSWORD\|secret\|credential\)' \
            --include='*.kt' --include='*.java' . \
            | grep -v 'Test\|test\|\.gradle'; then
            echo "::error::Found potential credential logging. Never log passwords or secrets."
            exit 1
          fi

      - name: Check FLAG_SECURE on RDP Activity
        run: |
          if grep -rn 'class RdpSessionActivity' --include='*.kt' -l .; then
            if ! grep -n 'FLAG_SECURE' app/src/main/java/com/gatecontrol/android/rdp/RdpSessionActivity.kt; then
              echo "::error::RdpSessionActivity must set FLAG_SECURE to prevent screenshots"
              exit 1
            fi
          fi
```

- [ ] **Step 2: Add AAR check and emulator tests to release.yml**

In `.github/workflows/release.yml`, add after the lint step in `test-gate` job (after line 37):

```yaml
      - name: Verify FreeRDP AAR exists
        run: |
          if [ ! -f core/rdp/libs/freerdp-android.aar ] && [ ! -f core/rdp/libs/.gitkeep ]; then
            echo "::warning::FreeRDP AAR not found — embedded RDP will use external client fallback"
          fi
```

- [ ] **Step 3: Run lint on workflows**

Run: `cd /root/android-client && python3 -c "import yaml; yaml.safe_load(open('.github/workflows/pr-check.yml'))" 2>&1 && echo "YAML valid" || echo "YAML invalid"`
Expected: "YAML valid"

- [ ] **Step 4: Commit**

```bash
cd /root/android-client
git add .github/workflows/pr-check.yml .github/workflows/release.yml
git commit -m "feat: add security checks and AAR validation to CI workflows"
```

---

### Task 15: Update RdpViewModel Tests for Embedded Client

**Files:**
- Modify: `app/src/test/java/com/gatecontrol/android/ui/rdp/RdpViewModelTest.kt`

- [ ] **Step 1: Add test for embedded client connect flow**

Append to `app/src/test/java/com/gatecontrol/android/ui/rdp/RdpViewModelTest.kt`, before the closing `}` of the class:

```kotlin
    @Test
    fun `connect with embedded client sets Connected with passwordCopied false`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedSession = RdpSession(
            routeId = onlineRoute.id,
            sessionId = 42,
            host = onlineRoute.host,
            startTime = System.currentTimeMillis(),
            isExternal = false
        )

        coEvery {
            rdpManager.connect(
                route = onlineRoute,
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } returns RdpManager.ConnectResult.Success(expectedSession, passwordCopied = false)

        viewModel.connect(onlineRoute.id)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.connectState.value
        assertTrue(state is ConnectState.Connected)
        assertEquals(false, (state as ConnectState.Connected).passwordCopied)
    }

    @Test
    fun `connect with SERVICE_START progress step is emitted`() = runTest {
        coEvery { apiClient.getRdpRoutes() } returns RdpRoutesResponse(
            ok = true,
            routes = listOf(onlineRoute)
        )
        viewModel.loadRoutes()
        testDispatcher.scheduler.advanceUntilIdle()

        val expectedSession = RdpSession(
            routeId = onlineRoute.id,
            sessionId = 42,
            host = onlineRoute.host,
            startTime = System.currentTimeMillis(),
            isExternal = false
        )

        coEvery {
            rdpManager.connect(
                route = onlineRoute,
                apiClient = apiClient,
                isVpnConnected = true,
                userPassword = null,
                forceMaintenanceBypass = false,
                onProgress = any()
            )
        } coAnswers {
            val onProgress = arg<(RdpProgress) -> Unit>(5)
            onProgress(RdpProgress.VPN_CHECK)
            onProgress(RdpProgress.TCP_CHECK)
            onProgress(RdpProgress.CREDENTIALS)
            onProgress(RdpProgress.CLIENT_LAUNCH)
            onProgress(RdpProgress.SESSION_START)
            onProgress(RdpProgress.SERVICE_START)
            onProgress(RdpProgress.COMPLETE)
            RdpManager.ConnectResult.Success(expectedSession)
        }

        viewModel.connectState.test {
            assertEquals(ConnectState.Idle, awaitItem())
            viewModel.connect(onlineRoute.id)

            assertEquals(ConnectState.Connecting(RdpProgress.VPN_CHECK), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.TCP_CHECK), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.CREDENTIALS), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.CLIENT_LAUNCH), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.SESSION_START), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.SERVICE_START), awaitItem())
            assertEquals(ConnectState.Connecting(RdpProgress.COMPLETE), awaitItem())

            val finalState = awaitItem()
            assertTrue(finalState is ConnectState.Connected)

            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Run ViewModel tests**

Run: `cd /root/android-client && ./gradlew :app:test --tests "com.gatecontrol.android.ui.rdp.RdpViewModelTest" --continue`
Expected: PASS (all tests including 2 new ones).

- [ ] **Step 3: Commit**

```bash
cd /root/android-client
git add app/src/test/java/com/gatecontrol/android/ui/rdp/RdpViewModelTest.kt
git commit -m "test: add ViewModel tests for embedded client and SERVICE_START progress"
```

---

### Task 16: Final Integration Test & Push

- [ ] **Step 1: Run all tests**

Run: `cd /root/android-client && ./gradlew test --continue`
Expected: All tests pass (existing + new).

- [ ] **Step 2: Run lint**

Run: `cd /root/android-client && ./gradlew lintRelease`
Expected: No errors.

- [ ] **Step 3: Build debug APK**

Run: `cd /root/android-client && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Push and monitor CI**

```bash
cd /root/android-client && git push
```

Monitor CI until green. Fix any failures immediately.

- [ ] **Step 5: Verify CI passes**

Run: `cd /root/android-client && gh run list --limit 3`
Expected: Latest run shows ✓ (success).
