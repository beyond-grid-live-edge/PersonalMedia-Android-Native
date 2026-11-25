package com.personalmedia.android

import android.net.Uri
import android.app.Activity
import android.content.res.Configuration
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private data class Media3TrackOption(
    val group: Tracks.Group, val index: Int, val language: String,
    val title: String, val selected: Boolean
)

private fun media3Time(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

private fun media3Options(player: ExoPlayer, type: Int): List<Media3TrackOption> = buildList {
    player.currentTracks.groups.filter { it.type == type }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            add(Media3TrackOption(group, index, format.language.orEmpty(),
                format.label.orEmpty(), group.isTrackSelected(index)))
        }
    }
}

/** Native Media3 player for local SAF files and direct URLs.
 * Unlike transient player-session track IDs, saved choices use semantic track metadata.
 */
@Composable
fun PlayerScreen(uri: String, subtitleTracks: List<StremioSubtitle> = emptyList(), initialMs: Long = 0,
    preferences: PlaybackPreferences = PlaybackPreferences(), mediaKey: String? = null,
    onProgress: (Long) -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val phoneUi = configuration.smallestScreenWidthDp < 600
    val phoneLandscape = phoneUi && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = context as? Activity
    val memoryStore = remember(context) { MediaTrackMemoryStore(context.applicationContext) }
    var rememberedTracks by remember(mediaKey) { mutableStateOf(memoryStore.read(mediaKey)) }
    val player = remember(uri, subtitleTracks, preferences.audioLanguage,
        preferences.subtitleLanguage, preferences.subtitlesEnabled) {
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(preferences.audioLanguage.takeUnless { it == "auto" })
                .setPreferredTextLanguage(preferences.subtitleLanguage.takeUnless { it == "auto" })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !preferences.subtitlesEnabled)
                .build()
            val builder = MediaItem.Builder().setUri(Uri.parse(uri))
            if (uri.contains("/live.ts") || uri.endsWith(".ts")) builder.setMimeType(MimeTypes.VIDEO_MP2T)
            if (subtitleTracks.isNotEmpty()) builder.setSubtitleConfigurations(
                subtitleTracks.filter { it.url.startsWith("https://") }.map {
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                        .setMimeType(MimeTypes.TEXT_VTT).setLanguage(it.language)
                        .setSelectionFlags(0).build()
                })
            setMediaItem(builder.build())
            prepare()
            if (initialMs > 0) seekTo(initialMs)
            playWhenReady = true
        }
    }
    var mediaPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var phoneFullscreen by remember { mutableStateOf(false) }
    var phoneControlsVisible by remember(uri) { mutableStateOf(true) }
    var controlsActivity by remember(uri) { mutableIntStateOf(0) }
    var phonePosition by remember(player) { mutableLongStateOf(initialMs) }
    var phoneDuration by remember(player) { mutableLongStateOf(0L) }
    var phoneSeek by remember(player) { mutableStateOf<Float?>(null) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var phoneVolume by remember(player) { mutableFloatStateOf(player.volume) }
    var audioMenuOpen by remember { mutableStateOf(false) }
    var subtitleMenuOpen by remember { mutableStateOf(false) }
    fun togglePhonePlayback() {
        if (player.playWhenReady) player.pause() else player.play()
        phoneControlsVisible = true
        controlsActivity++
    }
    var tracksRevision by remember(player) { mutableIntStateOf(0) }
    var audioApplied by remember(player, mediaKey) { mutableStateOf(false) }
    var subtitleApplied by remember(player, mediaKey) { mutableStateOf(false) }
    var initialSelectionReady by remember(player) { mutableStateOf(false) }
    // Suppress callbacks from our own settings changes; the built-in Media3 selector
    // remains usable and its user-initiated choices are also remembered.
    val ignoredParameters = remember(player) { mutableListOf<TrackSelectionParameters>() }
    var priorParameters by remember(player) { mutableStateOf(player.trackSelectionParameters) }

    fun setParameters(parameters: TrackSelectionParameters) {
        if (parameters != player.trackSelectionParameters) {
            ignoredParameters.add(parameters)
            if (ignoredParameters.size > 12) ignoredParameters.removeAt(0)
        }
        priorParameters = parameters
        player.trackSelectionParameters = parameters
    }
    fun applyChoice(type: Int, choice: RememberedTrack?, options: List<Media3TrackOption>): Boolean {
        if (choice == null) return true
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        when (choice.mode) {
            "off" -> if (type == C.TRACK_TYPE_TEXT) builder.setTrackTypeDisabled(type, true)
                else return true
            "auto" -> {
                builder.setTrackTypeDisabled(type, false)
                if (type == C.TRACK_TYPE_AUDIO) builder.setPreferredAudioLanguage(null)
                else builder.setPreferredTextLanguage(null)
            }
            "track" -> {
                val found = TrackMemoryPolicy.find(choice, options.map { AvailableTrack(it.language, it.title) })
                    ?: return false
                val chosen = options[found]
                builder.setTrackTypeDisabled(type, false)
                builder.setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, listOf(chosen.index)))
            }
        }
        setParameters(builder.build())
        return true
    }
    fun applyGlobal(type: Int) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (type == C.TRACK_TYPE_AUDIO) builder.setPreferredAudioLanguage(
            preferences.audioLanguage.takeUnless { it == "auto" })
        else {
            builder.setPreferredTextLanguage(preferences.subtitleLanguage.takeUnless { it == "auto" })
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !preferences.subtitlesEnabled)
        }
        setParameters(builder.build())
    }
    fun persistAudio(choice: RememberedTrack?) {
        memoryStore.saveAudio(mediaKey, choice)
        rememberedTracks = rememberedTracks.copy(audio = choice)
        audioApplied = false
    }
    fun persistSubtitle(choice: RememberedTrack?) {
        memoryStore.saveSubtitle(mediaKey, choice)
        rememberedTracks = rememberedTracks.copy(subtitle = choice)
        subtitleApplied = false
    }
    DisposableEffect(player, mediaKey) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { mediaPlaying = isPlaying }
            override fun onTracksChanged(tracks: Tracks) { tracksRevision++ }
            override fun onPlaybackStateChanged(playbackState: Int) { tracksRevision++ }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                val previous = priorParameters
                priorParameters = parameters
                if (ignoredParameters.remove(parameters)) return
                if (!initialSelectionReady || mediaKey == null) return
                fun overrideFor(type: Int, p: TrackSelectionParameters) =
                    p.overrides.values.filter { it.type == type }
                val audioChanged = overrideFor(C.TRACK_TYPE_AUDIO, previous) !=
                    overrideFor(C.TRACK_TYPE_AUDIO, parameters) ||
                    previous.preferredAudioLanguages != parameters.preferredAudioLanguages
                val subtitleChanged = overrideFor(C.TRACK_TYPE_TEXT, previous) !=
                    overrideFor(C.TRACK_TYPE_TEXT, parameters) ||
                    previous.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) !=
                        parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                    previous.preferredTextLanguages != parameters.preferredTextLanguages
                fun selectedChoice(type: Int): RememberedTrack {
                    if (type == C.TRACK_TYPE_TEXT && parameters.disabledTrackTypes.contains(type))
                        return RememberedTrack("off")
                    val options = media3Options(player, type)
                    val selected = options.indexOfFirst { option ->
                        parameters.overrides[option.group.mediaTrackGroup]?.trackIndices?.contains(option.index) == true
                    }
                    return TrackMemoryPolicy.makeChoice(selected,
                        options.map { AvailableTrack(it.language, it.title) }) ?: RememberedTrack("auto")
                }
                if (audioChanged) persistAudio(selectedChoice(C.TRACK_TYPE_AUDIO))
                if (subtitleChanged) persistSubtitle(selectedChoice(C.TRACK_TYPE_TEXT))
            }
        }
        player.addListener(listener)
        tracksRevision++ // Read already-prepared player tracks even if event happened before listener registration.
        onDispose { player.removeListener(listener) }
    }
    val audioOptions = remember(player, tracksRevision) { media3Options(player, C.TRACK_TYPE_AUDIO) }
    val subtitleOptions = remember(player, tracksRevision) { media3Options(player, C.TRACK_TYPE_TEXT) }
    // Initial restore is never persisted as a manual selection. Missing tracks keep
    // global defaults, rather than choosing an unrelated dub or commentary track.
    LaunchedEffect(player, tracksRevision, rememberedTracks, mediaKey) {
        if (player.playbackState == Player.STATE_IDLE) return@LaunchedEffect
        val metadataComplete = player.playbackState == Player.STATE_READY ||
            player.playbackState == Player.STATE_ENDED
        if (!audioApplied && (audioOptions.isNotEmpty() || metadataComplete ||
                rememberedTracks.audio?.mode != "track")) {
            val choice = rememberedTracks.audio
            if (choice != null && !applyChoice(C.TRACK_TYPE_AUDIO, choice, audioOptions)) applyGlobal(C.TRACK_TYPE_AUDIO)
            audioApplied = true
        }
        if (!subtitleApplied && (subtitleOptions.isNotEmpty() || metadataComplete ||
                rememberedTracks.subtitle?.mode != "track")) {
            val choice = rememberedTracks.subtitle
            if (choice != null && !applyChoice(C.TRACK_TYPE_TEXT, choice, subtitleOptions)) applyGlobal(C.TRACK_TYPE_TEXT)
            subtitleApplied = true
        }
        if (audioApplied && subtitleApplied) initialSelectionReady = true
    }
    DisposableEffect(activity, preferences.keepScreenAwake, mediaPlaying) {
        if (preferences.keepScreenAwake && mediaPlaying)
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(player) {
        onDispose { onProgress(player.currentPosition.coerceAtLeast(0)); player.release() }
    }
    LaunchedEffect(player) {
        while (true) { delay(5000); onProgress(player.currentPosition.coerceAtLeast(0)) }
    }
    LaunchedEffect(player, phoneUi) {
        if (phoneUi) while (true) {
            phonePosition = player.currentPosition.coerceAtLeast(0L)
            phoneDuration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            delay(400)
        }
    }
    LaunchedEffect(phoneLandscape, phoneFullscreen, phoneControlsVisible,
        controlsActivity, mediaPlaying, audioMenuOpen, subtitleMenuOpen, speedMenuOpen) {
        if (phoneLandscape && !phoneFullscreen && phoneControlsVisible && mediaPlaying &&
            !audioMenuOpen && !subtitleMenuOpen && !speedMenuOpen) {
            delay(5000)
            phoneControlsVisible = false
        }
    }
    // Reuse the same working Audio/CC track menus in portrait and landscape.
    // Video taps control their VISIBILITY; only the explicit transport button pauses.
    val headerControls: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose) { Text("← Back", color = Color.White) }
            Row {
                Box {
                    TextButton(onClick = { audioMenuOpen = true; subtitleMenuOpen = false }) {
                        Text("Audio ▾", color = Color.White)
                    }
                    DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Use global audio default") }, onClick = {
                            persistAudio(null); applyGlobal(C.TRACK_TYPE_AUDIO); audioApplied = true
                            audioMenuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Auto for this title") }, onClick = {
                            val choice = RememberedTrack("auto")
                            applyChoice(C.TRACK_TYPE_AUDIO, choice, audioOptions)
                            persistAudio(choice); audioApplied = true; audioMenuOpen = false
                        })
                        audioOptions.forEachIndexed { idx, option ->
                            DropdownMenuItem(text = { Text((if (option.selected) "✓ " else "") +
                                listOf(option.language, option.title).filter { it.isNotBlank() }
                                    .joinToString(" · ").ifBlank { "Audio ${idx + 1}" }) }, onClick = {
                                val choice = TrackMemoryPolicy.makeChoice(idx,
                                    audioOptions.map { AvailableTrack(it.language, it.title) })
                                if (choice != null && applyChoice(C.TRACK_TYPE_AUDIO, choice, audioOptions)) {
                                    persistAudio(choice); audioApplied = true
                                }
                                audioMenuOpen = false
                            })
                        }
                    }
                }
                Box {
                    TextButton(onClick = { subtitleMenuOpen = true; audioMenuOpen = false }) {
                        Text("CC ▾", color = Color.White)
                    }
                    DropdownMenu(expanded = subtitleMenuOpen, onDismissRequest = { subtitleMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Use global subtitle default") }, onClick = {
                            persistSubtitle(null); applyGlobal(C.TRACK_TYPE_TEXT); subtitleApplied = true
                            subtitleMenuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Off for this title") }, onClick = {
                            val choice = RememberedTrack("off")
                            applyChoice(C.TRACK_TYPE_TEXT, choice, subtitleOptions)
                            persistSubtitle(choice); subtitleApplied = true; subtitleMenuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Auto for this title") }, onClick = {
                            val choice = RememberedTrack("auto")
                            applyChoice(C.TRACK_TYPE_TEXT, choice, subtitleOptions)
                            persistSubtitle(choice); subtitleApplied = true; subtitleMenuOpen = false
                        })
                        subtitleOptions.forEachIndexed { idx, option ->
                            DropdownMenuItem(text = { Text((if (option.selected) "✓ " else "") +
                                listOf(option.language, option.title).filter { it.isNotBlank() }
                                    .joinToString(" · ").ifBlank { "Subtitle ${idx + 1}" }) }, onClick = {
                                val choice = TrackMemoryPolicy.makeChoice(idx,
                                    subtitleOptions.map { AvailableTrack(it.language, it.title) })
                                if (choice != null && applyChoice(C.TRACK_TYPE_TEXT, choice, subtitleOptions)) {
                                    persistSubtitle(choice); subtitleApplied = true
                                }
                                subtitleMenuOpen = false
                            })
                        }
                    }
                }
            }
        }
    }
    BackHandler { if (phoneUi && phoneFullscreen) phoneFullscreen = false else onClose() }
    if (phoneLandscape && !phoneFullscreen) {
        // Never put landscape transport buttons in a vertically scrolling Column.
        // Render the video full-bleed, then overlay a compact seek/transport panel.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = {
                PlayerView(it).apply { this.player = player; useController = false }
            }, modifier = Modifier.fillMaxSize())
            Box(Modifier.matchParentSize().clickable(onClickLabel = "Show or hide player controls") {
                phoneControlsVisible = !phoneControlsVisible
                controlsActivity++
                if (!phoneControlsVisible) {
                    audioMenuOpen = false; subtitleMenuOpen = false; speedMenuOpen = false
                }
            })
            if (phoneControlsVisible) {
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color(0xC9081428)).padding(horizontal = 8.dp)) {
                    headerControls()
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xD9091A34))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(media3Time(phoneSeek?.toLong() ?: phonePosition),
                            color = Color.White, fontSize = 11.sp)
                        Slider(value = (phoneSeek ?: phonePosition.toFloat()).coerceIn(0f,
                                phoneDuration.coerceAtLeast(1L).toFloat()),
                            onValueChange = { phoneSeek = it },
                            onValueChangeFinished = {
                                phoneSeek?.let { player.seekTo(it.toLong()) }
                                phoneSeek = null
                            }, valueRange = 0f..phoneDuration.coerceAtLeast(1L).toFloat(),
                            enabled = phoneDuration > 0 && player.isCurrentMediaItemSeekable,
                            modifier = Modifier.weight(1f).height(34.dp).padding(horizontal = 8.dp))
                        Text(media3Time(phoneDuration), color = Color.White, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = {
                            player.seekTo((phonePosition - 10000L).coerceAtLeast(0L))
                            controlsActivity++
                        }, enabled = player.isCurrentMediaItemSeekable) {
                            Text("↶ 10", color = Color.White, fontSize = 15.sp)
                        }
                        TextButton(onClick = ::togglePhonePlayback) {
                            Text(if (mediaPlaying) "Ⅱ Pause" else "▶ Play",
                                color = phoneBlue, fontSize = 18.sp)
                        }
                        TextButton(onClick = {
                            player.seekTo((phonePosition + 10000L).coerceAtMost(phoneDuration))
                            controlsActivity++
                        }, enabled = player.isCurrentMediaItemSeekable && phoneDuration > 0L) {
                            Text("10 ↷", color = Color.White, fontSize = 15.sp)
                        }
                        Box {
                            TextButton(onClick = { speedMenuOpen = true }) {
                                Text("Speed ▾", color = Color.White, fontSize = 12.sp)
                            }
                            DropdownMenu(expanded = speedMenuOpen,
                                onDismissRequest = { speedMenuOpen = false }) {
                                listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                                        player.setPlaybackSpeed(speed)
                                        speedMenuOpen = false
                                        controlsActivity++
                                    })
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("♪", color = Color.White, fontSize = 17.sp)
                        Slider(value = phoneVolume,
                            onValueChange = { phoneVolume = it; player.volume = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.weight(1f).height(34.dp)
                                .padding(start = 8.dp))
                    }
                }
            }
        }
    } else {
    Column(Modifier.fillMaxSize()
        .background(if (phoneUi) Brush.verticalGradient(listOf(Color(0xFF101D34), phoneNavy))
            else Brush.verticalGradient(listOf(Color.Black, Color.Black)))) {
        if (!phoneFullscreen) headerControls()
        if (phoneUi && !phoneFullscreen) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(factory = {
                    PlayerView(it).apply { this.player = player; useController = false }
                }, modifier = Modifier.fillMaxSize())
                Box(Modifier.matchParentSize().clickable(onClickLabel = "Show or hide player controls") {
                    phoneControlsVisible = !phoneControlsVisible
                    controlsActivity++
                    if (!phoneControlsVisible) {
                        audioMenuOpen = false; subtitleMenuOpen = false; speedMenuOpen = false
                    }
                })
                if (phoneControlsVisible) Box(Modifier.align(Alignment.Center)
                    .background(Color(0xBD0B203B), CircleShape),
                    contentAlignment = Alignment.Center) {
                    TextButton(onClick = ::togglePhonePlayback) {
                        Text(if (mediaPlaying) "Ⅱ" else "▶", color = Color.White,
                            fontSize = 30.sp)
                    }
                }
                if (phoneControlsVisible) TextButton(onClick = { phoneFullscreen = true },
                    modifier = Modifier.align(Alignment.BottomEnd)) {
                    Text("⛶ Fullscreen", color = Color.White, fontSize = 12.sp)
                }
            }
            if (phoneControlsVisible) Column(Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(media3Time((phoneSeek?.toLong() ?: phonePosition)), color = Color.White,
                        fontSize = 12.sp)
                    Slider(value = (phoneSeek ?: phonePosition.toFloat()).coerceIn(0f,
                            phoneDuration.coerceAtLeast(1).toFloat()),
                        onValueChange = { phoneSeek = it },
                        onValueChangeFinished = {
                            phoneSeek?.let { player.seekTo(it.toLong()) }
                            phoneSeek = null
                        }, valueRange = 0f..phoneDuration.coerceAtLeast(1L).toFloat(),
                        enabled = phoneDuration > 0 && player.isCurrentMediaItemSeekable,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(media3Time(phoneDuration), color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { player.seekTo((phonePosition - 10000L).coerceAtLeast(0L)) },
                        enabled = player.isCurrentMediaItemSeekable) {
                        Text("↶ 10", color = if (player.isCurrentMediaItemSeekable) Color.White else Color.Gray)
                    }
                    TextButton(onClick = ::togglePhonePlayback) {
                        Text(if (mediaPlaying) "Ⅱ" else "▶", fontSize = 30.sp,
                            color = phoneBlue,
                            modifier = Modifier.background(Color(0xFF162C4B),
                                androidx.compose.foundation.shape.RoundedCornerShape(42.dp))
                                .padding(horizontal = 18.dp, vertical = 5.dp))
                    }
                    TextButton(onClick = { player.seekTo((phonePosition + 10000L)
                            .coerceAtMost(phoneDuration)) },
                        enabled = player.isCurrentMediaItemSeekable && phoneDuration > 0L) {
                        Text("10 ↷", color = if (phoneDuration > 0L) Color.White else Color.Gray)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♪", color = Color.White, fontSize = 24.sp)
                    Slider(value = phoneVolume, onValueChange = { phoneVolume = it; player.volume = it },
                        valueRange = 0f..1f, modifier = Modifier.weight(1f).padding(start = 10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { audioMenuOpen = true; subtitleMenuOpen = false },
                            modifier = Modifier.fillMaxWidth()) { Text("Audio ▾", color = Color.White) }
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { subtitleMenuOpen = true; audioMenuOpen = false },
                            modifier = Modifier.fillMaxWidth()) { Text("Subtitles ▾", color = Color.White) }
                    }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { speedMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Speed ▾", color = Color.White)
                        }
                        DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                            listOf(.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                DropdownMenuItem(text = { Text("${speed}×") }, onClick = {
                                    player.setPlaybackSpeed(speed)
                                    speedMenuOpen = false
                                })
                            }
                        }
                    }
                }
            }
        } else {
        AndroidView(factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
    }
}
