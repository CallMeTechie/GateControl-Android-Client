package com.gatecontrol.android.support

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Exits Android recorded that the uncaught-exception handler never saw (ANR, native crash, ended for memory). */
class CrashReportsExitTest {

    @Test
    fun `crashes and ANRs are reported, the app ended in the background is not`() {
        assertTrue(CrashReports.exitReported(ApplicationExitInfo.REASON_ANR, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(CrashReports.exitReported(ApplicationExitInfo.REASON_CRASH_NATIVE, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertTrue(CrashReports.exitReported(ApplicationExitInfo.REASON_CRASH, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        // the system ended it for memory: only while it was seen
        assertTrue(CrashReports.exitReported(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertFalse(CrashReports.exitReported(ApplicationExitInfo.REASON_LOW_MEMORY, RunningAppProcessInfo.IMPORTANCE_CACHED))
        // closed by the person, updated, stopped: no report
        assertFalse(CrashReports.exitReported(ApplicationExitInfo.REASON_USER_REQUESTED, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
        assertFalse(CrashReports.exitReported(ApplicationExitInfo.REASON_EXIT_SELF, RunningAppProcessInfo.IMPORTANCE_FOREGROUND))
    }

    @Test
    fun `the report says what Android said, the trace of an ANR below`() {
        val text = CrashReports.exitText(
            ApplicationExitInfo.REASON_ANR, 0, RunningAppProcessInfo.IMPORTANCE_FOREGROUND, 412_000, 520_000,
            "com.gatecontrol.android", "Input dispatching timed out", "\"main\" prio=5 tid=1 Native",
        )
        assertTrue(text.startsWith("Exit: anr (status 0, importance 100)"))
        assertTrue(text.contains("Memory: pss 412000 kB, rss 520000 kB"))
        assertTrue(text.contains("Description: Input dispatching timed out"))
        assertTrue(text.trimEnd().endsWith("\"main\" prio=5 tid=1 Native"))
        assertEquals("native_crash", CrashReports.exitName(ApplicationExitInfo.REASON_CRASH_NATIVE))
    }
}
