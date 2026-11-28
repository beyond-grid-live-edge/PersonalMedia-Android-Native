package com.personalmedia.android

/** User-facing startup feedback. Never expose stream URLs, credentials or peer addresses. */
internal object StremioStartupPolicy {
    fun torrentProgress(metadata: Boolean, verifiedAhead: Long,
        trackerReplies: Int, dhtNodes: Int): String {
        if (metadata) {
            val kib = verifiedAhead.coerceAtLeast(0L) / 1024
            return if (kib > 0) "Metadata verified · $kib KiB of video ready"
                else "Metadata verified · waiting for first video pieces"
        }
        val discovery = if (trackerReplies > 0 || dhtNodes > 0)
            " · Tracker replies ${trackerReplies.coerceAtLeast(0)} · DHT ${dhtNodes.coerceAtLeast(0)}"
            else ""
        return "Connecting to peers · retrieving torrent metadata$discovery"
    }

    fun playerMessage(phase: String, buffering: Boolean, torrent: Boolean,
        videoTracks: Int, videoWidth: Int, paused: Boolean, softwareDecoder: Boolean,
        elapsedMs: Long): String? = when {
        paused || phase == "failed" || phase == "ended" -> null
        buffering -> if (torrent) "Buffering verified video…" else "Buffering video…"
        phase == "loading" && softwareDecoder -> "Starting compatible video decoder…"
        phase == "loading" && torrent && elapsedMs >= 10_000L ->
            "Waiting for verified video pieces…"
        phase == "loading" -> if (torrent) "Preparing verified video…" else "Preparing video…"
        phase == "ready" && videoTracks > 0 && videoWidth <= 0 -> "Preparing first frame…"
        else -> null
    }

    /** MPV's auto-safe decoder is the first choice. Retry once in software only
     * for a real video-output failure or a decoded-video stall. A slow torrent
     * connection, an audio-only title and normal cache buffering are NOT codec errors. */
    fun needsSoftwareRetry(started: Boolean, surfaceValid: Boolean,
        softwareDecoder: Boolean, paused: Boolean, phase: String, failureCode: Int,
        noFrameMs: Long, buffering: Boolean, videoTracks: Int, videoWidth: Int): Boolean {
        if (!started || !surfaceValid || softwareDecoder || paused) return false
        if (phase == "failed") return failureCode == -15
        return phase == "ready" && !buffering && videoTracks > 0 && videoWidth <= 0 &&
            noFrameMs >= 12_000L
    }

    fun elapsedLabel(elapsedMs: Long): String {
        val seconds = elapsedMs.coerceAtLeast(0L) / 1000
        return if (seconds < 3600) "%02d:%02d".format(seconds / 60, seconds % 60)
            else "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
    }
}
