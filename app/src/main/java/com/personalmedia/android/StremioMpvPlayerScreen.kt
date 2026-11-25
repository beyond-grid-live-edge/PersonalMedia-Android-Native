package com.personalmedia.android

import android.app.Activity
import android.content.res.Configuration
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private data class NativeTrack(val id: String, val label: String, val language: String = "", val title: String = "")
private fun parseTracks(array: JSONArray?): List<NativeTrack> = buildList {
    if (array != null) for (i in 0 until minOf(array.length(), 64)) {
        val row = array.optJSONObject(i) ?: continue
        val id = row.optInt("id", -1)
        if (id < 0) continue
        val language = row.optString("lang").trim()
        val label = listOf(language, row.optString("title"))
            .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Track $id" }
        add(NativeTrack(id.toString(), label.take(90), language, row.optString("title")))
    }
}
private fun timeText(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** Native libmpv Stremio playback. Direct HTTPS is the default, with an optional
 * Android HTTPS-to-loopback Range bridge when the provider works in Android but not libmpv.
 * Each Android Surface lifecycle is serialized on a worker, never the Compose main thread.
 * Source URLs and request headers exist only in memory for this playback session.
 */
@Composable
fun StremioMpvPlayerScreen(
    uri: String,
    requestHeaders: Map<String, String>,
    externalSubtitles: List<StremioSubtitle>,
    resumeMs: Long,
    preferences: PlaybackPreferences = PlaybackPreferences(),
    mediaKey: String? = null,
    onProgress: (Long) -> Unit,
    onClose: () -> Unit,
    torrentPlayback: Boolean = false,
    afterStop: () -> Unit = {}
) {
    val context = LocalContext.current
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    val phonePortrait = LocalConfiguration.current.let {
        it.smallestScreenWidthDp < 600 && it.orientation == Configuration.ORIENTATION_PORTRAIT
    }
    var phoneFullscreen by remember { mutableStateOf(false) }
    val phoneCompact = phonePortrait && !phoneFullscreen
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val memoryStore = remember(context) { MediaTrackMemoryStore(context.applicationContext) }
    var rememberedTracks by remember(mediaKey) { mutableStateOf(memoryStore.read(mediaKey)) }
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentAfterStop by rememberUpdatedState(afterStop)
    var surface by remember { mutableStateOf<Surface?>(null) }
    var error by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var mediaPhase by remember { mutableStateOf("loading") }
    var mediaFailureCode by remember { mutableIntStateOf(0) }
    var mediaVideoTracks by remember { mutableIntStateOf(0) }
    var mediaWidth by remember { mutableIntStateOf(0) }
    var mediaBuffering by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0) }
    var startedAt by remember { mutableLongStateOf(0) }
    var softwareDecoder by remember { mutableStateOf(false) }
    var compatibilityAgent by remember { mutableStateOf(false) }
    var sourceDiagnosis by remember { mutableStateOf("") }
    var sourceChecking by remember { mutableStateOf(false) }
    // The resolved HTTPS URL is ephemeral. It is never persisted or shown to the user.
    var verifiedRedirectUrl by remember(uri) { mutableStateOf<String?>(null) }
    var useVerifiedRedirect by remember(uri) { mutableStateOf(false) }
    var useAndroidBridge by remember(uri) { mutableStateOf(false) }
    // Prevent a failing upstream from causing an endless direct/bridge retry loop.
    var automaticBridgeAttempted by remember(uri) { mutableStateOf(false) }
    var automaticallyBridged by remember(uri) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(resumeMs) }
    var lastKnownMs by remember { mutableLongStateOf(resumeMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragMs by remember { mutableStateOf<Long?>(null) }
    var seekable by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf(emptyList<NativeTrack>()) }
    var subtitleTracks by remember { mutableStateOf(emptyList<NativeTrack>()) }
    var selectedAudio by remember { mutableStateOf("auto") }
    var selectedSubtitle by remember { mutableStateOf("auto") }
    var generation by remember { mutableIntStateOf(0) }
    var preferredAudioApplied by remember(uri, generation) { mutableStateOf(false) }
    var preferredSubtitleApplied by remember(uri, generation) { mutableStateOf(false) }
    val epoch = remember { AtomicLong(0) }
    val bridgeRef = remember { AtomicReference<StremioHttpsRangeBridge?>(null) }
    val serial = remember { Executors.newSingleThreadExecutor { job ->
        Thread(job, "Stremio-MPV").apply { isDaemon = true }
    } }
    // Providers may reject libmpv's default HTTP User-Agent. Offer a retry with
    // Stremio's User-Agent only when the add-on did not specify its own.
    val hasProviderAgent = requestHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }
    val playbackUrl = if (useVerifiedRedirect) verifiedRedirectUrl ?: uri else uri
    val effectiveHeaders = remember(uri, requestHeaders, compatibilityAgent, useVerifiedRedirect) {
        if (useVerifiedRedirect) emptyMap() // Never replay source-origin headers to a redirected host.
        else if (compatibilityAgent && !hasProviderAgent && uri.startsWith("https://", ignoreCase = true))
            requestHeaders + ("User-Agent" to "Stremio") else requestHeaders
    }
    val headerText = remember(playbackUrl, effectiveHeaders) {
        runCatching { StremioPlaybackPolicy.serializeHeaders(effectiveHeaders) }.getOrNull()
    }

    fun close() {
        currentOnProgress(lastKnownMs)
        onClose()
    }
    BackHandler { if (phoneFullscreen) phoneFullscreen = false else close() }
    LaunchedEffect(controlsVisible, started, error, audioMenuOpen, subtitleMenuOpen, phoneCompact) {
        if (!phoneCompact && controlsVisible && started && error.isEmpty() &&
            !audioMenuOpen && !subtitleMenuOpen) {
            delay(4500)
            controlsVisible = false
        }
    }

    DisposableEffect(activity, preferences.keepScreenAwake, paused, phoneCompact) {
        val window = activity?.window
        val originalUi = window?.decorView?.systemUiVisibility
        if (preferences.keepScreenAwake && !paused)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window?.let { w ->
            if (!phoneCompact) w.decorView.systemUiVisibility = (originalUi ?: 0) or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            if (window != null && originalUi != null) window.decorView.systemUiVisibility = originalUi
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && NativeBridge.loaded) {
                serial.execute { NativeBridge.mpvPauseStremio(true) }
            } else if (event == Lifecycle.Event.ON_START && !paused && NativeBridge.loaded) {
                serial.execute { NativeBridge.mpvPauseStremio(false) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(surface, playbackUrl, generation, softwareDecoder, compatibilityAgent, useAndroidBridge) {
        val token = epoch.incrementAndGet()
        val current = surface
        if (current == null || !current.isValid) {
            serial.execute {
                if (NativeBridge.loaded) NativeBridge.mpvStopStremio()
                bridgeRef.getAndSet(null)?.close()
            }
            return@LaunchedEffect
        }
        if (headerText == null || !StremioPlaybackPolicy.safeTransport(playbackUrl, effectiveHeaders)) {
            error = "The stream URL or source headers are not supported"
            return@LaunchedEffect
        }
        if (!NativeBridge.loaded || !NativeBridge.mpvAvailable()) {
            error = "Native MPV libraries are unavailable for this Android architecture"
            return@LaunchedEffect
        }
        error = ""; started = false
        sourceDiagnosis = ""; sourceChecking = false
        mediaPhase = "loading"; mediaFailureCode = 0; mediaVideoTracks = 0
        mediaWidth = 0; mediaBuffering = false; elapsedMs = 0
        startedAt = android.os.SystemClock.elapsedRealtime()
        serial.execute {
            NativeBridge.mpvStopStremio()
            // Release any previous bridge only AFTER libmpv stops reading its endpoint.
            bridgeRef.getAndSet(null)?.close()
            if (epoch.get() == token && current.isValid) {
                var address = playbackUrl
                var fields = headerText
                if (useAndroidBridge) {
                    // Only a header-free HTTPS media URL may use this bridge. In
                    // particular, never proxy a configured private provider header.
                    val bridge = if (effectiveHeaders.isEmpty() && playbackUrl.startsWith("https://")) {
                        val candidate = StremioHttpsRangeBridge(playbackUrl)
                        val opened = runCatching { address = candidate.start(); candidate }.getOrNull()
                        if (opened == null) candidate.close() // Release failed starts, too.
                        opened
                    } else null
                    if (bridge == null) {
                        if (epoch.get() == token) scope.launch {
                            error = "Android HTTPS bridge could not start for this source"
                        }
                        return@execute
                    }
                    bridgeRef.set(bridge)
                    fields = ""
                }
                val ok = runCatching {
                    NativeBridge.mpvStartStremio(current, address, fields,
                        StremioPlaybackPolicy.resumePosition(lastKnownMs), softwareDecoder)
                }.getOrDefault(false)
                if (epoch.get() == token) scope.launch {
                    started = ok
                    if (!ok) error = NativeBridge.mpvLastError().ifBlank { "Unable to start native MPV" }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            epoch.incrementAndGet()
            currentOnProgress(lastKnownMs)
            serial.execute {
                if (NativeBridge.loaded) NativeBridge.mpvStopStremio()
                bridgeRef.getAndSet(null)?.close()
                runCatching { currentAfterStop() }
            }
            serial.shutdown()
        }
    }
    LaunchedEffect(uri) {
        while (true) {
            delay(1250)
            if (!started || !NativeBridge.loaded) continue
            elapsedMs = (android.os.SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
            val status = withContext(Dispatchers.IO) {
                runCatching { JSONObject(NativeBridge.mpvStatusStremio()) }.getOrNull()
            } ?: continue
            if (!status.optBoolean("active")) {
                mediaPhase = "failed"
                mediaFailureCode = -15
                error = "The native video surface stopped. Try Retry or software decoding."
                continue
            }
            mediaPhase = status.optString("phase", "loading")
            mediaFailureCode = status.optInt("failureCode", 0)
            mediaVideoTracks = status.optInt("videoTracks", 0)
            mediaWidth = status.optInt("videoWidth", 0)
            mediaBuffering = status.optBoolean("buffering", false)
            val currentPosition = status.optLong("positionMs", 0).coerceAtLeast(0)
            if (currentPosition > 0) {
                positionMs = currentPosition
                lastKnownMs = currentPosition
                currentOnProgress(currentPosition)
            }
            durationMs = status.optLong("durationMs", 0).coerceAtLeast(0)
            paused = status.optBoolean("paused")
            seekable = status.optBoolean("seekable")
            audioTracks = parseTracks(status.optJSONArray("audio"))
            subtitleTracks = parseTracks(status.optJSONArray("subtitles"))
            selectedAudio = status.optString("aid", "auto")
            selectedSubtitle = status.optString("sid", "auto")
        }
    }

    val safeSubtitles = remember(uri, externalSubtitles, requestHeaders) {
        externalSubtitles.filter {
            StremioPlaybackPolicy.externalSubtitleAllowed(uri, it.url, requestHeaders)
        }
    }

    // One title/episode can override global language defaults without storing MPV track IDs.
    // mpv may renumber tracks when switching torrent/HTTPS sources: use language + actual
    // track title + duplicate occurrence, and only choose unambiguous fallback matches.
    LaunchedEffect(started, mediaPhase, generation, audioTracks, subtitleTracks,
        preferences, rememberedTracks) {
        if (!started || mediaPhase != "ready") return@LaunchedEffect
        if (!preferredAudioApplied) {
            val saved = rememberedTracks.audio
            val candidate = TrackMemoryPolicy.find(saved,
                audioTracks.map { AvailableTrack(it.language, it.title) })
            when {
                saved?.mode == "auto" -> {
                    serial.execute { NativeBridge.mpvSetTrackStremio("aid", "auto") }
                    preferredAudioApplied = true
                }
                candidate != null -> {
                    serial.execute { NativeBridge.mpvSetTrackStremio("aid", audioTracks[candidate].id) }
                    preferredAudioApplied = true
                }
                audioTracks.isNotEmpty() || saved == null -> {
                    if (preferences.audioLanguage != "auto") {
                        PlaybackPreferencesPolicy.trackForLanguage(preferences.audioLanguage,
                            audioTracks.map { it.id to it.language })?.let { id ->
                            serial.execute { NativeBridge.mpvSetTrackStremio("aid", id) }
                        }
                    }
                    preferredAudioApplied = true
                }
            }
        }
        if (!preferredSubtitleApplied) {
            val saved = rememberedTracks.subtitle
            val candidate = TrackMemoryPolicy.find(saved,
                subtitleTracks.map { AvailableTrack(it.language, it.title) })
            val externalIndex = TrackMemoryPolicy.externalSubtitleIndex(saved,
                safeSubtitles.map { it.language })
            when {
                saved?.mode == "off" -> {
                    serial.execute { NativeBridge.mpvSetTrackStremio("sid", "no") }
                    preferredSubtitleApplied = true
                }
                saved?.mode == "auto" -> {
                    serial.execute { NativeBridge.mpvSetTrackStremio("sid", "auto") }
                    preferredSubtitleApplied = true
                }
                candidate != null -> {
                    serial.execute { NativeBridge.mpvSetTrackStremio("sid", subtitleTracks[candidate].id) }
                    preferredSubtitleApplied = true
                }
                externalIndex != null -> {
                    serial.execute { NativeBridge.mpvAddSubtitleStremio(safeSubtitles[externalIndex].url) }
                    preferredSubtitleApplied = true
                }
                subtitleTracks.isNotEmpty() || saved == null || saved.mode == "external" -> {
                    if (!preferences.subtitlesEnabled) {
                        serial.execute { NativeBridge.mpvSetTrackStremio("sid", "no") }
                    } else if (preferences.subtitleLanguage == "auto") {
                        serial.execute { NativeBridge.mpvSetTrackStremio("sid", "auto") }
                    } else {
                        PlaybackPreferencesPolicy.trackForLanguage(preferences.subtitleLanguage,
                            subtitleTracks.map { it.id to it.language })?.let { id ->
                            serial.execute { NativeBridge.mpvSetTrackStremio("sid", id) }
                        }
                    }
                    preferredSubtitleApplied = true
                }
            }
        }
    }

    // Run a safe, header-only HTTP GET diagnostic only after an MPV loading error.
    // Never send provider URLs or headers to the UI, logs, or watch history.
    LaunchedEffect(mediaPhase, mediaFailureCode, generation, compatibilityAgent, useVerifiedRedirect, useAndroidBridge) {
        if (mediaPhase == "failed" && mediaFailureCode == -13 && started) {
            if (useAndroidBridge) {
                sourceChecking = false
                sourceDiagnosis = bridgeRef.get()?.lastFailure?.ifBlank {
                    "The Android HTTPS bridge responded, but MPV still could not open playable media. Check media format support."
                } ?: "The Android HTTPS bridge was unavailable."
                return@LaunchedEffect
            }
            sourceDiagnosis = ""
            sourceChecking = true
            if (torrentPlayback) {
                sourceDiagnosis = "Verified torrent stream needs more playable pieces or an MPV-compatible format."
                return@LaunchedEffect
            }
            val outcome = withContext(Dispatchers.IO) {
                StremioSourceDiagnostic.inspectDetailed(playbackUrl, effectiveHeaders)
            }
            // The inspection ran off the UI thread. The source URL remains
            // transient and is never displayed or written to watch history.
            if (StremioBridgeFallbackPolicy.eligible(
                    mediaPhase, mediaFailureCode, automaticBridgeAttempted, outcome)) {
                val candidate = outcome.bridgeUrl!!
                automaticBridgeAttempted = true // Set before restarting libmpv.
                automaticallyBridged = true
                verifiedRedirectUrl = candidate
                useVerifiedRedirect = true // Forces empty headers at the bridge.
                useAndroidBridge = true
                compatibilityAgent = false
                sourceChecking = false
                sourceDiagnosis = "Direct playback failed. Retrying securely through Android HTTPS…"
                generation++
            } else {
                sourceDiagnosis = outcome.message
                if (!useVerifiedRedirect) verifiedRedirectUrl = outcome.retryUrl
                sourceChecking = false
            }
        }
    }

    val playbackNotice = if (useAndroidBridge && mediaPhase == "failed" && mediaFailureCode == -13)
        "MPV could not open the stream through Android HTTPS either. Try another source or inspect the bridge error."
        else StremioMpvState.notice(mediaPhase, mediaFailureCode,
            elapsedMs, mediaVideoTracks, mediaWidth, mediaBuffering)
    val canRetry = error.isNotBlank() || mediaPhase in listOf("failed", "ended") ||
        (started && elapsedMs >= 15000 && (mediaPhase == "loading" ||
            (mediaPhase == "ready" && (mediaWidth <= 0 || positionMs <= 0))))
    // Full-screen video surface. Unlike the old Column+weighted SurfaceView,
    // controls OVERLAY the video without shrinking it to a narrow strip.
    // Audio/CC tracks use pop-up menus, not always-visible rows.
    BoxWithConstraints(Modifier.fillMaxSize().background(if (phoneCompact)
            Brush.verticalGradient(listOf(Color(0xFF0F1E35), phoneNavy))
        else Brush.verticalGradient(listOf(Color.Black, Color.Black)))) {
        val phoneVideoHeight = maxWidth * 9f / 16f
        AndroidView(factory = { ctx ->
            SurfaceView(ctx).apply {
                setOnClickListener {
                    // A tap reveals/hides the controls. Playback changes ONLY
                    // through the explicit Play/Pause button (not the video).
                    controlsVisible = !controlsVisible
                    if (!controlsVisible) { audioMenuOpen = false; subtitleMenuOpen = false }
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
                })
            }
        }, modifier = if (phoneCompact) Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .padding(top = 56.dp).height(phoneVideoHeight)
            else Modifier.fillMaxSize())

        if (controlsVisible || canRetry) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0x9C06101D)).padding(horizontal = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::close) { Text("← Back", color = Color.White, fontSize = 12.sp) }
                Text(if (torrentPlayback) "Verified torrent · Native MPV" else "Stremio · Native MPV",
                    color = Color.White, fontSize = 12.sp, maxLines = 1)
                TextButton(onClick = { generation++ }, enabled = canRetry) {
                    Text("Retry", fontSize = 12.sp)
                }
            }
        }

        // Status and recovery occupy a modest, translucent panel ONLY on error.
        // Normal playback receives no permanent status banners over the film.
        if (canRetry || error.isNotBlank() ||
            (playbackNotice.isNotBlank() && mediaPhase == "failed")) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth(.88f)
                .background(Color(0xDC0A1727), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (error.isNotBlank()) Text(error, color = Color(0xFFFFAAA8), fontSize = 12.sp)
                else if (playbackNotice.isNotBlank()) Text(playbackNotice, color = Color(0xFFFFD18D), fontSize = 12.sp)
                if (sourceChecking) Text("Checking provider response…", color = Color.LightGray, fontSize = 11.sp)
                if (sourceDiagnosis.isNotBlank()) Text(sourceDiagnosis, color = Color(0xFF9DC8FF), fontSize = 11.sp)
                if (started && !softwareDecoder && elapsedMs >= 12000 &&
                    mediaPhase != "failed" && (mediaWidth <= 0 || positionMs <= 0)) {
                    TextButton(onClick = { softwareDecoder = true }) {
                        Text("Try software video decoding", fontSize = 12.sp)
                    }
                }
                if (mediaPhase == "failed" && mediaFailureCode == -13 && !useVerifiedRedirect &&
                    !hasProviderAgent && !compatibilityAgent && uri.startsWith("https://", true)) {
                    TextButton(onClick = { compatibilityAgent = true; generation++ }) {
                        Text("Retry with Stremio User-Agent", fontSize = 12.sp)
                    }
                }
                if (mediaPhase == "failed" && verifiedRedirectUrl != null && !useVerifiedRedirect) {
                    TextButton(onClick = {
                        useVerifiedRedirect = true
                        useAndroidBridge = false
                        compatibilityAgent = false
                        sourceDiagnosis = ""
                        generation++
                    }) { Text("Try verified HTTPS redirect", fontSize = 12.sp) }
                }
                if (mediaPhase == "failed" && mediaFailureCode == -13 && !useAndroidBridge &&
                    effectiveHeaders.isEmpty() && playbackUrl.startsWith("https://", true) &&
                    sourceDiagnosis.contains("HTTP 206")) {
                    TextButton(onClick = { useAndroidBridge = true; sourceDiagnosis = ""; generation++ }) {
                        Text("Try Android HTTPS bridge", fontSize = 12.sp)
                    }
                }
                if (useAndroidBridge && mediaPhase == "failed") {
                    TextButton(onClick = {
                        useAndroidBridge = false
                        automaticallyBridged = false
                        sourceDiagnosis = ""
                        generation++
                    }) { Text("Return to direct stream", fontSize = 12.sp) }
                }
                if (useVerifiedRedirect && mediaPhase == "failed") {
                    TextButton(onClick = {
                        useVerifiedRedirect = false
                        useAndroidBridge = false
                        automaticallyBridged = false
                        sourceDiagnosis = ""
                        generation++
                    }) { Text("Return to original source", fontSize = 12.sp) }
                }
            }
        }

        if (controlsVisible) {
            Column(Modifier.then(if (phoneCompact) Modifier.align(Alignment.TopCenter)
                    .padding(top = 56.dp + phoneVideoHeight + 8.dp)
                else Modifier.align(Alignment.BottomCenter)).fillMaxWidth()
                .background(if (phoneCompact) Color(0xFF11243E) else Color(0xCB081423))
                .padding(horizontal = 10.dp, vertical = if (phoneCompact) 12.dp else 4.dp),
                verticalArrangement = Arrangement.spacedBy(if (phoneCompact) 5.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(timeText(dragMs ?: positionMs), color = Color.White, fontSize = 11.sp)
                    Slider(value = ((dragMs ?: positionMs).coerceAtMost(durationMs)).toFloat(),
                        onValueChange = { dragMs = it.toLong() },
                        onValueChangeFinished = {
                            dragMs?.let { destination -> serial.execute { NativeBridge.mpvSeekStremio(destination) } }
                            dragMs = null
                        }, valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
                        enabled = started && seekable && durationMs > 0,
                        modifier = Modifier.weight(1f).height(30.dp))
                    Text(timeText(durationMs), color = Color.LightGray, fontSize = 11.sp)
                }
                if (phoneCompact) Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvSeekStremio((positionMs - 10000).coerceAtLeast(0)) }
                    }, enabled = started && seekable) { Text("↶ 10", color = Color.White, fontSize = 16.sp) }
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvPauseStremio(!paused) }
                        paused = !paused
                    }, enabled = started) {
                        Text(if (paused) "▶" else "Ⅱ", color = phoneBlue, fontSize = 28.sp)
                    }
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvSeekStremio((positionMs + 10000).coerceAtMost(durationMs)) }
                    }, enabled = started && seekable) { Text("10 ↷", color = Color.White, fontSize = 16.sp) }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!phoneCompact) {
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvPauseStremio(!paused) }
                        paused = !paused
                    }, enabled = started) { Text(if (paused) "▶ Play" else "Ⅱ Pause", fontSize = 12.sp) }
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvSeekStremio((positionMs - 10000).coerceAtLeast(0)) }
                    }, enabled = started && seekable) { Text("−10s", fontSize = 12.sp) }
                    TextButton(onClick = {
                        serial.execute { NativeBridge.mpvSeekStremio((positionMs + 10000).coerceAtMost(durationMs)) }
                    }, enabled = started && seekable) { Text("+10s", fontSize = 12.sp) }
                    }
                    Box {
                        TextButton(onClick = { audioMenuOpen = true; subtitleMenuOpen = false }) {
                            Text("Audio ▾", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Use global audio default") }, onClick = {
                                memoryStore.saveAudio(mediaKey, null)
                                rememberedTracks = rememberedTracks.copy(audio = null)
                                preferredAudioApplied = false
                                audioMenuOpen = false
                            })
                            (listOf(NativeTrack("auto", "Auto")) + audioTracks).forEach { track ->
                                DropdownMenuItem(text = { Text((if (selectedAudio == track.id) "✓ " else "") + track.label) },
                                    onClick = {
                                        serial.execute { NativeBridge.mpvSetTrackStremio("aid", track.id) }
                                        selectedAudio = track.id
                                        val choice = if (track.id == "auto") RememberedTrack("auto")
                                            else TrackMemoryPolicy.makeChoice(
                                                audioTracks.indexOfFirst { it.id == track.id },
                                                audioTracks.map { AvailableTrack(it.language, it.title) })
                                        if (choice != null) {
                                            memoryStore.saveAudio(mediaKey, choice)
                                            rememberedTracks = rememberedTracks.copy(audio = choice)
                                        }
                                        preferredAudioApplied = true
                                        audioMenuOpen = false
                                    })
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { subtitleMenuOpen = true; audioMenuOpen = false }) {
                            Text("CC ▾", fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = subtitleMenuOpen, onDismissRequest = { subtitleMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Use global subtitle default") }, onClick = {
                                memoryStore.saveSubtitle(mediaKey, null)
                                rememberedTracks = rememberedTracks.copy(subtitle = null)
                                preferredSubtitleApplied = false
                                subtitleMenuOpen = false
                            })
                            (listOf(NativeTrack("auto", "Auto"), NativeTrack("no", "Off")) + subtitleTracks)
                                .forEach { track ->
                                    DropdownMenuItem(text = { Text((if (selectedSubtitle == track.id) "✓ " else "") + track.label) },
                                        onClick = {
                                            serial.execute { NativeBridge.mpvSetTrackStremio("sid", track.id) }
                                            selectedSubtitle = track.id
                                            val choice = when (track.id) {
                                                "auto" -> RememberedTrack("auto")
                                                "no" -> RememberedTrack("off")
                                                else -> TrackMemoryPolicy.makeChoice(
                                                    subtitleTracks.indexOfFirst { it.id == track.id },
                                                    subtitleTracks.map { AvailableTrack(it.language, it.title) })
                                            }
                                            if (choice != null) {
                                                memoryStore.saveSubtitle(mediaKey, choice)
                                                rememberedTracks = rememberedTracks.copy(subtitle = choice)
                                            }
                                            preferredSubtitleApplied = true
                                            subtitleMenuOpen = false
                                        })
                                }
                            safeSubtitles.take(15).forEachIndexed { index, sub ->
                                DropdownMenuItem(text = { Text("External · " + sub.language.ifBlank { "Subtitle" }) },
                                    onClick = {
                                        // Never persist external subtitle URLs, even when a title is remembered.
                                        if (sub.url.startsWith("https://")) {
                                            serial.execute { NativeBridge.mpvAddSubtitleStremio(sub.url) }
                                            val choice = RememberedTrack("external", sub.language.take(24), "", index)
                                            if (TrackMemoryPolicy.valid(choice, subtitle = true)) {
                                                memoryStore.saveSubtitle(mediaKey, choice)
                                                rememberedTracks = rememberedTracks.copy(subtitle = choice)
                                                preferredSubtitleApplied = true
                                            }
                                        }
                                        subtitleMenuOpen = false
                                    })
                            }
                        }
                    }
                    if (phonePortrait) TextButton(onClick = {
                        phoneFullscreen = !phoneFullscreen
                        controlsVisible = true
                    }) { Text(if (phoneFullscreen) "Exit fullscreen" else "⛶ Fullscreen", fontSize = 12.sp) }
                    TextButton(onClick = ::close) { Text("Stop", fontSize = 12.sp) }
                }
            }
        }
    }
}
