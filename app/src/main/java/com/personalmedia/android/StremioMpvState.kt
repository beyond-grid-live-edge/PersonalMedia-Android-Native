package com.personalmedia.android

/** Non-sensitive libmpv playback-state interpretation.
 * No URL, token, header, filename or provider response is put in diagnostics.
 * MPV loadfile success is NOT evidence that a media file loaded. */
internal object StremioMpvState {
    fun notice(phase: String, code: Int, elapsedMs: Long,
        videoTracks: Int, videoWidth: Int, buffering: Boolean): String = when {
        phase == "failed" -> when (code) {
            -13 -> "The selected direct stream failed to open in MPV. Checking the provider response and available playback alternatives."
            -14 -> "MPV could not initialise the audio output."
            -15 -> "MPV could not initialise video output. The app will try a compatible decoder automatically."
            -16 -> "This source contained no playable audio or video."
            -17 -> "MPV could not recognise the stream's media format."
            -18 -> "This media format is not supported by the installed MPV build."
            else -> "MPV stopped loading this source (error $code). Try another direct source."
        }
        phase == "ended" -> "This source ended before playback could continue. Try another provider or stream."
        phase == "loading" && elapsedMs >= 15000L ->
            "Still opening this stream. Check internet connectivity or try another provider."
        phase == "loading" -> "Opening provider stream…"
        phase == "ready" && buffering -> "Buffering media from the provider…"
        phase == "ready" && elapsedMs >= 12000L && videoTracks == 0 ->
            "Stream loaded without a video track. It may be audio-only or unsupported."
        phase == "ready" && elapsedMs >= 12000L && videoTracks > 0 && videoWidth <= 0 ->
            "Video track detected, but no decoded frame yet. The app checks decoder compatibility automatically."
        else -> ""
    }
}
