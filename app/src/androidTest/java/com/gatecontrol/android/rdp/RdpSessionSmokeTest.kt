package com.gatecontrol.android.rdp

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gatecontrol.android.rdp.freerdp.RdpSessionEvent
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smoke test: launches RdpSessionActivity pointing at a local xrdp target
 * and verifies the session reaches ConnectionSuccess within 15 seconds.
 *
 * Prerequisites (not checked at test time):
 *   - xrdp reachable at `10.0.2.2:3389` (host loopback as seen from inside
 *     the Android emulator). CI provides this via a sidecar container in
 *     `.github/workflows/instrumentation.yml`.
 *   - Emulator with API 34+ and arm64-v8a.
 *
 * The test is intentionally workflow_dispatch-only in CI and does NOT run
 * on every push; a broken xrdp sidecar would otherwise block all merges.
 * Real release gating happens via the manual QA checklist in
 * `docs/FREERDP_INTEGRATION.md`.
 */
@RunWith(AndroidJUnit4::class)
class RdpSessionSmokeTest {

    @Test
    fun connectToXrdpReachesSuccess() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(ctx, RdpSessionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("rdp_host", "10.0.2.2")
            putExtra("rdp_port", 3389)
            putExtra("rdp_username", "test")
            putExtra("rdp_password", "test")
            putExtra("rdp_resolution_width", 1024)
            putExtra("rdp_resolution_height", 768)
            putExtra("rdp_color_depth", 32)
            putExtra("rdp_route_name", "xrdp-smoke")
        }

        val latch = CountDownLatch(1)
        val scenario = ActivityScenario.launch<RdpSessionActivity>(intent)
        scenario.onActivity { activity ->
            activity.lifecycleScope.launch {
                activity.controllerForTest.events.collect { event ->
                    if (event is RdpSessionEvent.ConnectionSuccess) {
                        latch.countDown()
                    }
                }
            }
        }

        val success = latch.await(15, TimeUnit.SECONDS)
        scenario.close()
        assertTrue("ConnectionSuccess not reached within 15s", success)
    }
}
