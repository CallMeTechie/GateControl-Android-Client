package com.gatecontrol.android.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Locale

class UpdateLogicTest {

    @Nested
    inner class Versions {
        @Test
        fun `version code to name follows release yml`() {
            assertEquals("1.46.0", UpdateVersions.name(14600))
            assertEquals("1.45.2", UpdateVersions.name(14502))
            assertEquals("2.0.13", UpdateVersions.name(20013))
            assertEquals("10.99.99", UpdateVersions.name(109999))
        }

        @Test
        fun `name to code and back`() {
            assertEquals(14600, UpdateVersions.code("1.46.0"))
            assertEquals(14502, UpdateVersions.code(" 1.45.2 "))
            for (code in listOf(10000, 14502, 14600, 20101)) assertEquals(code, UpdateVersions.code(UpdateVersions.name(code)))
        }

        @Test
        fun `malformed names have no code`() {
            assertNull(UpdateVersions.code("1.46"))
            assertNull(UpdateVersions.code("1.46.0-beta"))
            assertNull(UpdateVersions.code("1.100.0"))
            assertNull(UpdateVersions.code("a.b.c"))
        }
    }

    @Nested
    inner class Changelog {
        private val md = """
            # Changelog

            ## [1.47.0] - 2026-10-20

            ### Features
            - too new, never shown (#99)

            ---

            ## [1.46.1] - 2026-10-19

            ### Fixes
            - widget no longer flickers (#97)

            ---

            ## [1.46.0] - 2026-10-19

            ### Features
            - Messenger 1.5 – reactions and replies (#95, #96)
            - status and stories (#94)

            ### Fixes
            - Door camera reconnects
            -${"   "}
            ---

            ## [1.45.2] - 2026-10-18

            ### Fixes
            - installed already, not shown (#93)
        """.trimIndent()

        @Test
        fun `takes the sections after the installed version up to the target`() {
            val notes = ChangelogParser.parse(md, currentCode = 14502, targetCode = 14601)
            assertEquals(
                listOf(
                    ReleaseNote("Messenger 1.5 – reactions and replies", ReleaseNote.Kind.Feature),
                    ReleaseNote("Status and stories", ReleaseNote.Kind.Feature),
                    ReleaseNote("Widget no longer flickers", ReleaseNote.Kind.Fix),
                    ReleaseNote("Door camera reconnects", ReleaseNote.Kind.Fix),
                ),
                notes,
            )
        }

        @Test
        fun `the target is included, the installed version and newer ones are not`() {
            val notes = ChangelogParser.parse(md, currentCode = 14600, targetCode = 14601)
            assertEquals(listOf(ReleaseNote("Widget no longer flickers", ReleaseNote.Kind.Fix)), notes)
            assertTrue(ChangelogParser.parse(md, currentCode = 14601, targetCode = 14601).isEmpty())
        }

        @Test
        fun `cleanup drops the PR reference and capitalises`() {
            assertEquals("Story send button visible", ChangelogParser.clean("story send button visible (#93)"))
            assertEquals("Two PRs", ChangelogParser.clean("two PRs (#1, #2)"))
            assertEquals("Keeps (#notes) in the middle", ChangelogParser.clean("keeps (#notes) in the middle"))
            assertEquals("Äpfel", ChangelogParser.clean("äpfel"))
        }

        @Test
        fun `title and detail split at the dash`() {
            val note = ReleaseNote("Messenger 1.4 – status, stories", ReleaseNote.Kind.Feature)
            assertEquals("Messenger 1.4", note.title)
            assertEquals("Status, stories", note.detail)
            val plain = ReleaseNote("Fix the widget", ReleaseNote.Kind.Fix)
            assertEquals("Fix the widget", plain.title)
            assertNull(plain.detail)
        }

        @Test
        fun `garbage yields nothing`() {
            assertTrue(ChangelogParser.parse("<html>404</html>", 14502, 14600).isEmpty())
        }

        @Test
        fun `cached notes survive encoding`() {
            val notes = listOf(ReleaseNote("A – b", ReleaseNote.Kind.Feature), ReleaseNote("C|d", ReleaseNote.Kind.Fix))
            assertEquals(notes, UpdatePrefs.decodeNotes(UpdatePrefs.encodeNotes(notes)))
        }
    }

    @Nested
    inner class Eta {
        @Test
        fun `remaining time from the measured rate`() {
            val eta = EtaEstimator()
            eta.add(0, 1_000_000)
            assertNull(eta.remainingSeconds(18_000_000)) // one sample: no rate yet
            eta.add(2_000, 2_000_000) // 0.5 MB/s
            // 16 MB left at 0.5 MB/s
            assertEquals(32, eta.remainingSeconds(18_000_000))
        }

        @Test
        fun `rounds up and needs a minimal span`() {
            val eta = EtaEstimator(minSpanMs = 1_500)
            eta.add(0, 0)
            eta.add(1_000, 1_000_000)
            assertNull(eta.remainingSeconds(10_000_000))
            eta.add(3_000, 3_000_000) // 1 MB/s
            assertEquals(7, eta.remainingSeconds(10_000_000))
            assertEquals(1, eta.remainingSeconds(3_000_001))
        }

        @Test
        fun `old samples leave the window`() {
            val eta = EtaEstimator(windowMs = 10_000)
            eta.add(0, 0)
            eta.add(10_000, 1_000_000) // slow start: 0.1 MB/s
            eta.add(20_000, 11_000_000) // now 1 MB/s
            assertEquals(4, eta.remainingSeconds(15_000_000))
        }

        @Test
        fun `a restarted download starts over`() {
            val eta = EtaEstimator()
            eta.add(0, 5_000_000)
            eta.add(2_000, 6_000_000)
            eta.add(3_000, 0)
            assertNull(eta.remainingSeconds(10_000_000))
            eta.reset()
            assertNull(eta.remainingSeconds(10_000_000))
        }

        @Test
        fun `no progress means no estimate`() {
            val eta = EtaEstimator()
            eta.add(0, 1_000)
            eta.add(5_000, 1_000)
            assertNull(eta.remainingSeconds(10_000))
            assertNull(eta.remainingSeconds(0))
        }
    }

    @Nested
    inner class Marker {
        @Test
        fun `marker round trip`() {
            val m = PostUpdateMarker(14502, 14600, vpnWasConnected = true)
            assertEquals(m, PostUpdateMarker.decode(m.encode()))
            assertEquals(PostUpdateMarker(14502, 14600, false), PostUpdateMarker.decode("14502|14600|0"))
        }

        @Test
        fun `broken markers are ignored`() {
            assertNull(PostUpdateMarker.decode(null))
            assertNull(PostUpdateMarker.decode(""))
            assertNull(PostUpdateMarker.decode("14502|14600"))
            assertNull(PostUpdateMarker.decode("x|14600|1"))
            assertNull(PostUpdateMarker.decode("14600|14502|1")) // not an update
        }

        @Test
        fun `shown once on the target version`() {
            val m = PostUpdateMarker(14502, 14600, true)
            assertEquals(PostUpdate.Decision.Show, PostUpdate.decide(m, 14600))
        }

        @Test
        fun `kept while the old version still runs`() {
            assertEquals(PostUpdate.Decision.Keep, PostUpdate.decide(PostUpdateMarker(14502, 14600, false), 14502))
        }

        @Test
        fun `discarded on any other version`() {
            val m = PostUpdateMarker(14502, 14600, false)
            assertEquals(PostUpdate.Decision.Discard, PostUpdate.decide(m, 14601))
            assertEquals(PostUpdate.Decision.Discard, PostUpdate.decide(m, 14400))
            assertNull(PostUpdate.decide(null, 14600))
        }
    }

    @Nested
    inner class Format {
        @Test
        fun sizes() {
            assertEquals("18 MB", UpdateFormat.size(18_000_000, Locale.GERMAN))
            assertEquals("4,2 MB", UpdateFormat.size(4_200_000, Locale.GERMAN))
            assertEquals("4.2 MB", UpdateFormat.size(4_200_000, Locale.ENGLISH))
            assertEquals("11,0 / 18,0 MB", UpdateFormat.progress(11_000_000, 18_000_000, Locale.GERMAN))
        }

        @Test
        fun snooze() {
            assertTrue(UpdateFormat.snoozed(1_000, 2_000, 14600, 14600))
            assertFalse(UpdateFormat.snoozed(2_000, 2_000, 14600, 14600))
            assertFalse(UpdateFormat.snoozed(1_000, 2_000, 14600, 14700)) // a newer update shows again
        }

        @Test
        fun ago() {
            assertNull(UpdateFormat.ago(30_000, 0))
            assertEquals(UpdateFormat.AgoUnit.Minutes to 2, UpdateFormat.ago(120_000, 0))
            assertEquals(UpdateFormat.AgoUnit.Hours to 3, UpdateFormat.ago(3 * 3_600_000L + 5, 0))
            assertEquals(UpdateFormat.AgoUnit.Days to 2, UpdateFormat.ago(49 * 3_600_000L, 0))
        }
    }
}
