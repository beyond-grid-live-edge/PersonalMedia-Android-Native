package com.personalmedia.android

import org.junit.Assert.*
import org.junit.Test

class StremioStartupPolicyTest {
    @Test fun torrentPreparationDoesNotInventPeerOrRateMetrics() {
        val waiting = StremioStartupPolicy.torrentProgress(false, 0, 0, 0)
        assertTrue(waiting.contains("Connecting to peers"))
        assertFalse(waiting.contains("Peers 0"))
        assertFalse(waiting.contains("KiB/s"))
        assertTrue(StremioStartupPolicy.torrentProgress(true, 4096, 0, 0).contains("4 KiB"))
    }

    @Test fun videoMessagesDistinguishLoadingAndVerifiedBuffering() {
        assertEquals("Preparing verified video…",
            StremioStartupPolicy.playerMessage("loading", false, true, 0, 0, false, false, 0))
        assertEquals("Waiting for verified video pieces…",
            StremioStartupPolicy.playerMessage("loading", false, true, 0, 0, false, false, 15_000))
        assertEquals("Buffering verified video…",
            StremioStartupPolicy.playerMessage("ready", true, true, 1, 1920, false, false, 1000))
        assertEquals("Starting compatible video decoder…",
            StremioStartupPolicy.playerMessage("loading", false, true, 0, 0, false, true, 1000))
        assertNull(StremioStartupPolicy.playerMessage("ready", false, true, 1, 1920, false, false, 1000))
        assertNull(StremioStartupPolicy.playerMessage("ready", false, true, 1, 0, true, false, 1000))
    }

    @Test fun fallbackIsAutomaticButOnlyWhenDecoderFailureIsCredible() {
        fun retry(phase: String, code: Int = 0, noFrame: Long = 20_000,
            buffering: Boolean = false, tracks: Int = 1, width: Int = 0,
            software: Boolean = false, surface: Boolean = true) =
            StremioStartupPolicy.needsSoftwareRetry(true, surface, software, false,
                phase, code, noFrame, buffering, tracks, width)
        assertTrue(retry("failed", -15, 1000))
        assertTrue(retry("ready"))
        assertFalse(retry("loading"))
        assertFalse(retry("failed", -13))
        assertFalse(retry("ready", buffering = true))
        assertFalse(retry("ready", noFrame = 11_999))
        assertFalse(retry("ready", tracks = 0))
        assertFalse(retry("ready", width = 1920))
        assertFalse(retry("ready", software = true))
        assertFalse(retry("failed", -15, surface = false))
    }

    @Test fun elapsedTimesAreCompactAndNeverRevealSources() {
        assertEquals("00:00", StremioStartupPolicy.elapsedLabel(-100))
        assertEquals("00:45", StremioStartupPolicy.elapsedLabel(45_999))
        assertEquals("01:02", StremioStartupPolicy.elapsedLabel(62_000))
    }
}
