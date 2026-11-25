package com.personalmedia.android

import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/* Single Sky-style guide; no duplicate classic channel-list guide.
 * Persistent inline native Android player; source and TS transport are the original native C++ T405.
 * Fullscreen only resizes the same Android SurfaceView: no extra native MPV session. */
private val tvBg = Color(0xFF090E17)
private val tvPanel = Color(0xFF111925)
private val tvRow = Color(0xFF192330)
private val tvText = Color(0xFFF0F5FF)
private val tvMuted = Color(0xFF9CAEC5)
private val tvBlue = Color(0xFF459AFF)
private val tvGreen = Color(0xFF56DEA4)

@Composable
private fun TvChip(label: String, active: Boolean = false) {
    Text(label, color = if (active) Color(0xFFD9FCE9) else Color(0xFFBED0E4), fontSize = 11.sp,
        modifier = Modifier.background(if (active) Color(0xFF173C34) else Color(0xFF202B39),
            RoundedCornerShape(11.dp)).padding(horizontal = 10.dp, vertical = 7.dp), maxLines = 1)
}

@Composable
private fun TvButton(label: String, enabled: Boolean = true, click: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF263548))
        .clickable(enabled = enabled, onClick = click).height(36.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) tvText else tvMuted, fontSize = 12.sp, maxLines = 1)
    }
}

private fun Context.hostActivity(): Activity? {
    var next: Context = this
    while (next is ContextWrapper) {
        if (next is Activity) return next
        val base = next.baseContext
        if (base === next) break
        next = base
    }
    return next as? Activity
}

private data class T405Track(val id: String, val label: String)

private fun mpvOptions(state: JSONObject, name: String): List<T405Track> {
    val array = state.optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optInt("id", -1)
        if (id < 0) return@mapNotNull null
        val language = item.optString("lang").trim()
        val title = item.optString("title").trim()
        T405Track(id.toString(), listOfNotNull(language.takeIf { it.isNotEmpty() },
            title.takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { "Track $id" })
    }
}

@Composable
private fun T405TrackMenu(title: String, isSubtitle: Boolean, tracks: List<T405Track>,
    selection: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TvButton(title) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (isSubtitle) DropdownMenuItem(text = { Text("Off" + if (selection == "no") " ✓" else "") },
                onClick = { expanded = false; onSelect("no") })
            DropdownMenuItem(text = { Text("Auto" + if (selection == "auto") " ✓" else "") },
                onClick = { expanded = false; onSelect("auto") })
            tracks.forEach { track ->
                DropdownMenuItem(text = { Text(track.label + if (selection == track.id) " ✓" else "") },
                    onClick = { expanded = false; onSelect(track.id) })
            }
            if (tracks.isEmpty()) DropdownMenuItem(
                text = { Text(if (isSubtitle) "No subtitle tracks detected" else "No audio tracks detected") },
                enabled = false, onClick = {})
        }
    }
}

/** Human-readable tuning diagnosis from actual PAT/PMT packet counters. */
private fun tuningStage(status: JSONObject, channel: JSONObject?): String {
    val lastTsAge = status.optLong("lastTsAgeMs", -1L)
    val pat = status.optInt("patSections", 0)
    val pmts = status.optInt("pmtSections", 0)
    val pendingPmt = status.optInt("pendingPmtPid", 0)
    val tracks = channel?.optInt("streamCount", 0) ?: 0
    return when {
        !status.optBoolean("connected") -> "Tuner disconnected — reconnect before tuning."
        lastTsAge < 0 || lastTsAge > 5_000L -> "Waiting for fresh transport-stream packets from the tuner."
        pat == 0 -> "Transport packets received · waiting for PAT (PID 0)."
        pendingPmt == 0 -> "PAT received · waiting for this service's PMT PID."
        pmts == 0 -> "PMT PID $pendingPmt requested · ${status.optInt("pmtRequests")} requests · ${status.optInt("pmtRetries")} retries."
        tracks == 0 -> "PMT received · looking for audio/video elementary PIDs."
        else -> "PMT decoded · $tracks stream PIDs found · starting the player."
    }
}

@Composable
private fun TunerPane(host: String, onHost: (String) -> Unit, control: String, onControl: (String) -> Unit,
    receive: String, onReceive: (String) -> Unit, frequency: String, onFrequency: (String) -> Unit,
    state: JSONObject, onMessage: (String) -> Unit, refresh: () -> Unit, onScan: (String) -> Boolean) {
    val context = LocalContext.current
    var routeDetails by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("T405 WI-FI TUNER", fontSize = 13.sp, color = tvText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TvButton("Probe") {
                onMessage(if (NativeBridge.loaded && NativeBridge.tvProbe()) "Siano version probe sent." else "Connect the tuner first.")
                refresh()
            }
        }
        Text("The device must be on the tuner Wi-Fi, or able to reach its IP address.", color = tvMuted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("Check Android Wi-Fi route") { routeDetails = T405NetworkRoute.inspect(context, host) }
        }
        if (routeDetails.isNotEmpty()) Text(routeDetails, color = tvMuted, fontSize = 11.sp)
        Text(T405NetworkRoute.lastReport, color = tvMuted, fontSize = 10.sp)
        TvButton("Copy T405 diagnostics") {
            val payload = "PersonalMedia T405\n" + T405NetworkRoute.inspect(context, host) + "\n" +
                T405NetworkRoute.lastReport + "\nStatus: " +
                (if (NativeBridge.loaded) NativeBridge.tvStatus() else "native library unavailable") +
                "\nPlayback: " + (if (NativeBridge.loaded && NativeBridge.mpvAvailable())
                    "libmpv available for current ABI" else "Media3 fallback: libmpv unavailable for current ABI") +
                "\nMPV diagnostics: " + (if (NativeBridge.loaded)
                    runCatching { NativeBridge.mpvDiagnostics() }.getOrDefault("MPV diagnostics unavailable")
                    else "PersonalMedia native code unavailable")
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("PersonalMedia T405 diagnostics", payload))
                onMessage("T405 diagnostics copied. Paste the text here after testing with your receiver.")
            } else onMessage("This Android runtime does not provide clipboard access.")
        }
        Text("Receiver: ${if (state.optBoolean("connected")) "UDP socket listening" else "closed"} · Control sends: ${state.optInt("controlSends")} · Siano replies: ${state.optInt("sianoMessages")}", color = tvMuted, fontSize = 11.sp)
        Text("Last Siano reply: ${state.optLong("lastSianoReplyAgeMs", -1).let { if (it < 0) "never" else "${it / 1000}s ago" }} · Last TS packet: ${state.optLong("lastTsAgeMs", -1).let { if (it < 0) "never" else "${it / 1000}s ago" }}",
            color = tvMuted, fontSize = 11.sp)
        if (state.optString("error").isNotEmpty()) Text("Receiver error: ${state.optString("error")}", color = Color(0xFFFFBBC5), fontSize = 11.sp)
        OutlinedTextField(host, onHost, label = { Text("Tuner IPv4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(control, onControl, label = { Text("Control UDP") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(receive, onReceive, label = { Text("Receive UDP") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TvButton("Connect") {
                val cp = control.toIntOrNull(); val rp = receive.toIntOrNull()
                onMessage(if (cp != null && rp != null) T405SessionController.connect(context, host, cp, rp)
                    else "Check control and receive UDP port numbers.")
                refresh()
            }
            TvButton("Disconnect") {
                T405SessionController.disconnect(context)
                refresh(); onMessage("Receiver disconnected; Guardian stopped")
            }
        }
        OutlinedTextField(frequency, onFrequency, label = { Text("Frequency MHz") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TvButton("Tune") {
                val value = frequency.toIntOrNull()
                onMessage(if (value != null && NativeBridge.loaded && NativeBridge.tvTune(value * 1_000_000, 8))
                    "Tuning $value MHz" else "Tune failed")
            }
            TvButton("Scan UK Freeview") {
                onMessage(if (onScan("uk_freeview")) "Scanning UK frequencies" else "Scan unavailable")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TvButton("Scan EU UHF") {
                onMessage(if (onScan("eu_uhf")) "Scanning EU UHF" else "Scan unavailable")
            }
            TvButton("Cancel scan") { if (NativeBridge.loaded) NativeBridge.tvCancelScan() }
        }
    }
}

private data class BroadcastEvent(val name: String, val start: Long, val end: Long, val description: String = "")
private fun broadcastTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(-1L)
private fun guideEvents(channel: JSONObject, now: Long): List<BroadcastEvent> {
    val result = linkedMapOf<Long, BroadcastEvent>()
    fun add(event: JSONObject?) {
        if (event == null) return
        val start = broadcastTime(event.optString("start"))
        val end = broadcastTime(event.optString("end"))
        val title = event.optString("title")
        if (start < 0 || end <= start || title.isBlank() || end < now - 3_600_000L) return
        val nextEvent = BroadcastEvent(title, start, end, event.optString("description"))
        // Prefer an entry with a description when EIT Now/Next and schedule overlap.
        val existing = result[start]
        if (existing == null || (existing.description.isBlank() && nextEvent.description.isNotBlank()))
            result[start] = nextEvent
    }
    add(channel.optJSONObject("now")); add(channel.optJSONObject("next"))
    val events = channel.optJSONArray("events") ?: JSONArray()
    for (i in 0 until events.length()) add(events.optJSONObject(i))
    return result.values.sortedBy { it.start }
}

/** Duplicates are hidden only in the Sky guide; the complete DVB lineup is never erased. */
private fun skyLineup(channels: List<JSONObject>, selected: String, now: Long): List<JSONObject> {
    val candidates = channels.map { row ->
        val events = guideEvents(row, now)
        T405GuideCandidate(
            key = row.optString("key"), number = row.optInt("number"),
            name = row.optString("name"), encrypted = row.optBoolean("encrypted"),
            active = row.optString("key") == selected,
            currentProgramme = events.any { it.start <= now && now < it.end },
            futureProgrammeCount = events.count { it.start > now },
            mediaPidCount = row.optInt("streamCount"),
            frequencyKnown = row.optLong("frequencyHz") > 0L)
    }
    val byKey = channels.associateBy { it.optString("key") }
    return T405GuidePolicy.visible(candidates).mapNotNull { byKey[it.key] }
}

/** Sky-style EIT timeline. All displayed programme times come from real DVB EIT. */
@Composable
private fun T405SkyGuide(channels: List<JSONObject>, selected: String,
    onTune: (String) -> Unit, now: Long, savedCount: Int, uniqueCount: Int, pfSections: Long,
    scheduleSections: Long) {
    val stepMs = 30 * 60 * 1000L
    val base = now / stepMs * stepMs
    val visible = 8 // 4-hour window, page forward through actual EIT schedule.
    val cell = 128.dp
    val totalWidth = cell * visible
    var offsetSlots by remember { mutableIntStateOf(0) }
    var focusedEvent by remember { mutableStateOf<BroadcastEvent?>(null) }
    var focusedChannel by remember { mutableStateOf("") }
    val left = base + offsetSlots * stepMs
    val localTime = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    val dayLabel = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
    val observedEvents = remember(channels, now / 60_000L) { channels.sumOf { guideEvents(it, now).count { e -> e.end > now } } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .border(1.dp, Color(0xFF27394E), RoundedCornerShape(18.dp))
        .background(tvPanel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FREEVIEW GUIDE", color = tvBlue, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("EIT · ${dayLabel.format(Instant.ofEpochMilli(left))}", color = tvMuted, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            TvButton("Now", enabled = offsetSlots != 0) { offsetSlots = 0 }
            TvButton("← Earlier", enabled = offsetSlots > 0) { offsetSlots = (offsetSlots - 8).coerceAtLeast(0) }
            TvButton("Later +4h", enabled = offsetSlots < 328) { offsetSlots = (offsetSlots + 8).coerceAtMost(328) }
            TvButton("Tomorrow", enabled = offsetSlots < 288) { offsetSlots = (offsetSlots + 48).coerceAtMost(328) }
        }
        Text("${channels.size} shown · $uniqueCount unique guide channels · ${(savedCount - uniqueCount).coerceAtLeast(0)} secondary/duplicate services hidden · $observedEvents current/upcoming listings",
            color = tvMuted, fontSize = 10.sp)
        if (channels.isEmpty()) {
            Text("No saved Freeview services yet. Scan while connected to your T405.",
                color = tvMuted, modifier = Modifier.padding(12.dp))
        } else {
            // Retain one lazily rendered vertical list. The entire header and rows
            // scroll horizontally as a unit, avoiding hundreds of per-row scroll states.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column {
                    Row(Modifier.height(38.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Channel", color = tvMuted, fontSize = 11.sp, modifier = Modifier.width(142.dp))
                        Row(Modifier.width(totalWidth)) {
                            repeat(visible) { index ->
                                Text(localTime.format(Instant.ofEpochMilli(left + index * stepMs)),
                                    color = tvMuted, fontSize = 11.sp,
                                    modifier = Modifier.width(cell).padding(start = 8.dp))
                            }
                        }
                    }
                    HorizontalDivider(color = Color(0xFF29394B))
                    LazyColumn(Modifier.height(320.dp)) {
                        items(channels, key = { it.optString("key") }) { channel ->
                            val key = channel.optString("key")
                            val active = key == selected
                            val events = remember(channel, now / 60_000L) { guideEvents(channel, now) }
                            val current = events.firstOrNull { it.start <= now && now < it.end }
                            val next = events.firstOrNull { it.start >= (current?.end ?: now) }
                            Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.width(142.dp).fillMaxHeight()
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (active) Color(0xFF1E426B) else Color(0xFF182433))
                                    .clickable { onTune(key) }.padding(8.dp),
                                    verticalArrangement = Arrangement.Center) {
                                    Text("${channel.optInt("number").takeIf { it > 0 } ?: "—"}  ${channel.optString("name")}",
                                        color = tvText, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                                Box(Modifier.width(totalWidth).height(48.dp).background(Color(0xFF111A29))) {
                                    Row {
                                        repeat(visible) { Box(Modifier.width(cell).fillMaxHeight().border(.4.dp, Color(0xFF253244))) }
                                    }
                                    val right = left + visible * stepMs
                                    for (event in events) {
                                        val start = maxOf(event.start, left)
                                        val end = minOf(event.end, right)
                                        if (end <= start) continue
                                        val x = ((start - left).toDouble() / stepMs * 128.0).toFloat().dp
                                        val w = maxOf(4f, ((end - start).toDouble() / stepMs * 128.0).toFloat()).dp
                                        Box(Modifier.offset(x = x).width(w).height(44.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(if (active) Color(0xFF245998) else if (event == current)
                                                Color(0xFF24516C) else Color(0xFF24384F))
                                            .clickable {
                                                focusedEvent = event
                                                focusedChannel = channel.optString("name")
                                                if (key != selected) onTune(key)
                                            }.padding(6.dp), contentAlignment = Alignment.CenterStart) {
                                            val label = when (event) {
                                                current -> "NOW · ${event.name}"
                                                next -> "NEXT · ${event.name}"
                                                else -> event.name
                                            }
                                            Text(label, color = tvText, fontSize = 10.sp,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF202E40))
                        }
                    }
                }
            }
            focusedEvent?.let { item ->
                Column(Modifier.fillMaxWidth().background(Color(0xFF19283B), RoundedCornerShape(8.dp)).padding(10.dp)) {
                    Text("$focusedChannel · ${item.name}", color = tvText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("${dayLabel.format(Instant.ofEpochMilli(item.start))} ${localTime.format(Instant.ofEpochMilli(item.start))}–${localTime.format(Instant.ofEpochMilli(item.end))}",
                        color = tvMuted, fontSize = 11.sp)
                    if (item.description.isNotBlank()) Text(item.description, color = tvText, fontSize = 11.sp)
                }
            }
            Text("DVB EIT: Now/Next $pfSections sections · schedule $scheduleSections sections. Later programmes appear only when the broadcaster transmits their listings; other multiplexes may need to be tuned to collect more.",
                color = tvMuted, fontSize = 10.sp)
        }
    }
}
@Composable
fun LiveTvScreen(onPlay: (String) -> Unit, onFullscreenChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    // Live T405 uses the native Siano C++ TS endpoint with libmpv, matching the
    // working native receiver/player split. Media3 is a clearly labelled fallback
    // only for Android ABIs where an operational libmpv.so is not bundled.
    val player = remember { ExoPlayer.Builder(context).build() }
    val mpvPresent = remember { NativeBridge.loaded && NativeBridge.mpvAvailable() }
    var mpvFailed by remember { mutableStateOf(false) }
    val useMpv = mpvPresent && !mpvFailed
    val tvPreferences = remember(context) { context.getSharedPreferences("t405_receiver", 0) }
    var host by remember { mutableStateOf(tvPreferences.getString("host", "192.168.1.1") ?: "192.168.1.1") }
    var control by remember { mutableStateOf(tvPreferences.getString("control", "5002") ?: "5002") }
    var receive by remember { mutableStateOf(tvPreferences.getString("receive", "5001") ?: "5001") }
    var frequency by remember { mutableStateOf(tvPreferences.getString("frequency", "474") ?: "474") }
    LaunchedEffect(host, control, receive, frequency) {
        tvPreferences.edit().putString("host", host).putString("control", control)
            .putString("receive", receive).putString("frequency", frequency).apply()
    }
    var query by remember { mutableStateOf("") }
    var tunerSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(JSONObject()) }
    val savedChannels = remember(context) { T405ChannelStore.load(context) }
    var channels by remember { mutableStateOf(savedChannels) }
    var streamUrl by remember { mutableStateOf("") }
    var activeKey by remember { mutableStateOf("") }
    var pendingKey by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var rootPixelPosition by remember { mutableStateOf(IntOffset.Zero) }
    var videoSlotPosition by remember { mutableStateOf(IntOffset.Zero) }
    var videoSlotSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val activity = remember(context) { context.hostActivity() }
    val ioScope = rememberCoroutineScope()
    var trackState by remember { mutableStateOf(JSONObject()) }
    val preferenceSubtitleMode = remember { tvPreferences.getString("subtitleMode", "auto") ?: "auto" }
    var subtitleChoice by remember { mutableStateOf(preferenceSubtitleMode) }
    var audioChoice by remember { mutableStateOf("auto") }
    val audioOptions = remember(trackState) { mpvOptions(trackState, "audio") }
    val subtitleOptions = remember(trackState) { mpvOptions(trackState, "subtitles") }
    fun setTrack(type: String, value: String) {
        if (!useMpv || streamUrl.isBlank()) return
        ioScope.launch {
            val applied = withContext(Dispatchers.IO) {
                NativeBridge.loaded && NativeBridge.mpvSetTrackForT405(type, value)
            }
            if (applied) {
                if (type == "sid") {
                    subtitleChoice = value
                    // A specific ID may not exist on the next channel, so persist only Auto/Off.
                    if (value == "auto" || value == "no") tvPreferences.edit().putString("subtitleMode", value).apply()
                } else audioChoice = value
            } else message = "MPV could not select this broadcast track."
        }
    }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    DisposableEffect(fullscreen, activity) {
        onFullscreenChanged(fullscreen)
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val keepAwake = remember { PlaybackPreferencesStore(context).read().keepScreenAwake }
    DisposableEffect(isPlaying, streamUrl, activity, keepAwake) {
        if (keepAwake && isPlaying && streamUrl.isNotBlank())
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (keepAwake) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(streamUrl, useMpv) {
        trackState = JSONObject()
        audioChoice = "auto"
        subtitleChoice = tvPreferences.getString("subtitleMode", "auto") ?: "auto"
        if (streamUrl.isNotBlank() && useMpv) {
            var previous = ""
            while (isActive) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { if (NativeBridge.loaded) NativeBridge.mpvTracksForT405() else "" }.getOrDefault("")
                }
                if (result.isNotBlank() && result != previous) {
                    trackState = runCatching { JSONObject(result) }.getOrDefault(JSONObject())
                    previous = result
                }
                delay(2_500L)
            }
        }
    }
    var clock by remember { mutableStateOf("--:--") }
    var tsRate by remember { mutableDoubleStateOf(-1.0) }
    var previousPackets by remember { mutableLongStateOf(-1L) }
    var previousAt by remember { mutableLongStateOf(0L) }
    val lastChannelRefresh = remember { AtomicLong(0L) }
    val refreshBusy = remember { AtomicBoolean(false) }
    var displayedChannelsJson by remember { mutableStateOf(JSONArray(savedChannels).toString()) }
    // Cached calculations must not rerun for every transport-rate/status update.
    val selected = remember(channels, activeKey) { channels.firstOrNull { it.optString("key") == activeKey } }
    val now = selected?.optJSONObject("now")?.optString("title").orEmpty()
    val next = selected?.optJSONObject("next")?.optString("title").orEmpty()
    val muxCount = remember(channels) { channels.map { it.optLong("frequencyHz") }.filter { it > 0 }.distinct().size }
    val guideChannels = remember(channels, activeKey) { skyLineup(channels, activeKey, System.currentTimeMillis()) }
    val filtered = remember(guideChannels, query) { guideChannels.filter { row ->
        row.optString("name").contains(query, true) || row.optString("number").contains(query, true) ||
        row.optJSONObject("now")?.optString("title").orEmpty().contains(query, true) ||
        row.optJSONObject("next")?.optString("title").orEmpty().contains(query, true) ||
        (row.optJSONArray("events")?.let { ev -> (0 until ev.length()).any { i ->
            ev.optJSONObject(i)?.optString("title").orEmpty().contains(query, true)
        } } == true)
    } }
    fun tuneChannel(key: String) {
        if (!status.optBoolean("connected")) {
            message = "Connect to the T405 to tune this saved Freeview channel."
            return
        }
        if (status.optBoolean("scanning")) {
            message = "Cancel or finish the current scan before tuning a channel."
            return
        }
        if (NativeBridge.loaded) {
            NativeBridge.tvStop()
            pendingKey = key; activeKey = ""; streamUrl = ""; isPlaying = false
        }
    }
    fun startScan(profile: String): Boolean {
        if (!NativeBridge.loaded) return false
        if (streamUrl.isNotBlank() || pendingKey.isNotBlank() || activeKey.isNotBlank()) {
            pendingKey = ""; activeKey = ""; streamUrl = ""
            NativeBridge.tvStop()
        }
        return NativeBridge.tvScan(profile)
    }
    // A status JNI call and especially a 212-service EPG merge must never block
    // Compose's scroll/input thread. Keep only a single in-flight snapshot.
    fun refresh() {
        if (!NativeBridge.loaded || !refreshBusy.compareAndSet(false, true)) return
        val oldChannels = channels
        val previousStamp = displayedChannelsJson
        val lastListAt = lastChannelRefresh.get()
        val guideVisible = true
        val nowMs = System.currentTimeMillis()
        ioScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { runCatching {
                    val currentStatus = runCatching { JSONObject(NativeBridge.tvStatus()) }.getOrDefault(JSONObject())
                    val scanning = currentStatus.optBoolean("scanning")
                    val refreshInterval = when {
                        scanning -> 2_500L
                        guideVisible -> 8_000L
                        else -> 10_000L
                    }
                    val fetchList = oldChannels.isEmpty() || nowMs - lastListAt >= refreshInterval
                    val updatedList = if (fetchList) {
                        val nativeRows = runCatching { JSONArray(NativeBridge.tvChannels()).objects() }
                            .getOrDefault(emptyList())
                        val merged = T405ChannelStore.merge(oldChannels, nativeRows)
                        if (!scanning && nativeRows.isNotEmpty()) T405ChannelStore.save(context, merged)
                        merged
                    } else null
                    val text = updatedList?.let { JSONArray(it).toString() }
                    Triple(currentStatus, updatedList, text)
                }.getOrNull() }
                if (snapshot == null) return@launch
                // Compose state is only updated on the UI dispatcher, and only
                // when the values actually changed.
                status = snapshot.first
                val refreshedRows = snapshot.second
                val refreshedText = snapshot.third
                if (refreshedRows != null && refreshedText != null) {
                    lastChannelRefresh.set(System.currentTimeMillis())
                    if (refreshedText != previousStamp) {
                        channels = refreshedRows
                        displayedChannelsJson = refreshedText
                    }
                }
                val displayedTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (clock != displayedTime) clock = displayedTime
                val time = System.currentTimeMillis()
                val count = snapshot.first.optLong("tsPackets")
                val bitrate = if (previousPackets >= 0 && count >= previousPackets && time > previousAt)
                    (count - previousPackets) * 188.0 * 8.0 / ((time - previousAt) * 1000.0) else -1.0
                if (kotlin.math.abs(bitrate - tsRate) >= 0.15 || (bitrate < 0) != (tsRate < 0)) tsRate = bitrate
                previousPackets = count
                previousAt = time
            } finally {
                refreshBusy.set(false)
            }
        }
    }
    LaunchedEffect(Unit) {
        // 1.5-second lightweight telemetry; heavy programme/lineup updates use
        // the slower schedule above, so finger scrolling wins over background work.
        while (isActive) { refresh(); delay(1_500L) }
    }
    LaunchedEffect(pendingKey) {
        if (pendingKey.isBlank() || !NativeBridge.loaded) return@LaunchedEffect
        for (attempt in 0..24) {
            val url = NativeBridge.tvPlay(pendingKey)
            if (url.startsWith("http://127.0.0.1:")) {
                streamUrl = url; activeKey = pendingKey; pendingKey = ""; break
            }
            delay(1100L)
        }
        if (pendingKey.isNotBlank()) message = "T405 tune incomplete: " + tuningStage(status, channels.firstOrNull { it.optString("key") == pendingKey })
    }
    LaunchedEffect(status.optInt("httpPort"), streamUrl) {
        // A *socket-level* Guardian reconnect replaces the loopback listener.
        // Retune the same saved channel through the new port; routine PID
        // recovery never changes the URL or restarts MPV.
        val newPort = status.optInt("httpPort")
        val oldPort = runCatching { Uri.parse(streamUrl).port }.getOrDefault(-1)
        if (newPort > 0 && oldPort > 0 && newPort != oldPort && activeKey.isNotBlank() &&
            status.optBoolean("connected") && !status.optBoolean("scanning")) {
            val key = activeKey
            streamUrl = ""; activeKey = ""; isPlaying = false
            pendingKey = key
            message = "Receiver socket restored; retuning saved channel"
        }
    }
    LaunchedEffect(streamUrl, useMpv) {
        player.stop()
        if (streamUrl.isNotEmpty() && !useMpv) {
            player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(streamUrl)).setMimeType(MimeTypes.VIDEO_MP2T).build())
            player.prepare(); player.playWhenReady = true
            isPlaying = true
        } else if (streamUrl.isBlank()) isPlaying = false
    }
    val latestMpvState by rememberUpdatedState(useMpv)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (!latestMpvState) {
                    message = "Live TV Media3 fallback: ${error.getErrorCodeName()}. ${error.message.orEmpty()}"
                    isPlaying = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            // The parent fullscreen player still consumes the receiver's /live.ts stream.
            // Stop only when the viewer closes (MainActivity) or the user taps Stop.
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().background(tvBg).onGloballyPositioned { coordinates ->
        val p = coordinates.positionInRoot()
        val next = IntOffset(p.x.toInt(), p.y.toInt())
        if (rootPixelPosition != next) rootPixelPosition = next
    }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = if (phoneUi) 14.dp else 22.dp, end = if (phoneUi) 14.dp else 22.dp,
            top = if (phoneUi) 12.dp else 18.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val receiving = status.optLong("lastTsAgeMs", -1L) in 0L..4000L
        val connectionCaption = when {
            streamUrl.isNotBlank() && receiving -> "● Live transport · ${if (useMpv) "MPV" else "Media3"}"
            receiving -> "● Freeview transport receiving"
            status.optBoolean("deviceReplied") -> "● Tuner replied"
            status.optBoolean("connected") -> "● Socket ready · no control reply"
            else -> "○ Tuner disconnected"
        }
        val connectionTint = if (receiving || status.optBoolean("deviceReplied")) tvGreen else tvMuted
        if (phoneUi) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LIVE TV  /  AUGUST T405", color = tvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Freeview", color = tvText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(connectionCaption, color = connectionTint, fontSize = 12.sp, maxLines = 2)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("LIVE TV  /  AUGUST T405", color = tvBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Freeview", color = tvText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Text(connectionCaption, color = connectionTint, fontSize = 12.sp, maxLines = 2)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 1000.dp
            @Composable fun playerPane(modifier: Modifier) {
                Column(modifier.clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF253344), RoundedCornerShape(20.dp)).background(tvPanel)) {
                    Row(Modifier.fillMaxWidth().height(if (phoneUi) 52.dp else 64.dp)
                        .padding(horizontal = if (phoneUi) 12.dp else 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(selected?.optString("number")?.takeIf { it != "0" }.orEmpty().ifEmpty { "TV" },
                            color = tvBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(selected?.optString("name")?.ifEmpty { "Live television" } ?: "Live television",
                            color = tvText, fontSize = if (phoneUi) 15.sp else 19.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        if (streamUrl.isNotEmpty()) Text("● LIVE", color = tvBlue, fontSize = 11.sp)
                        if (!phoneUi) TvChip("● Freeview", status.optBoolean("deviceReplied"))
                    }
                    Box(Modifier.fillMaxWidth().then(if (phoneUi) Modifier.aspectRatio(16f / 9f)
                            else Modifier.height(if (wide) 376.dp else 320.dp))
                        .background(Color.Black).onGloballyPositioned { coordinates ->
                            val p = coordinates.positionInRoot()
                            val nextPosition = IntOffset(p.x.toInt(), p.y.toInt())
                            if (videoSlotPosition != nextPosition) videoSlotPosition = nextPosition
                            if (videoSlotSize != coordinates.size) videoSlotSize = coordinates.size
                        }) {
                        // The one actual SurfaceView is a stable sibling of the scrolling content.
                        // Fullscreen resizes it instead of destroying/recreating the MPV session.
                        if (streamUrl.isEmpty()) {
                            Column(Modifier.align(Alignment.Center).fillMaxWidth(if (phoneUi) .94f else .80f)
                                .background(Color(0xDC0C1725), RoundedCornerShape(18.dp))
                                .padding(if (phoneUi) 10.dp else 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(if (phoneUi) 5.dp else 10.dp)) {
                                Text(if (pendingKey.isNotEmpty()) "Tuning broadcast channel…" else "LIVE TV",
                                    color = tvText, fontSize = if (phoneUi) 18.sp else 23.sp, fontWeight = FontWeight.Bold)
                                Text(if (pendingKey.isNotEmpty()) tuningStage(status, selected) else
                                    "Choose a saved channel in the Sky guide below to watch live Freeview television",
                                    color = Color(0xFFB5C7E0), fontSize = if (phoneUi) 11.sp else 13.sp,
                                    lineHeight = if (phoneUi) 14.sp else 17.sp)
                                if (!status.optBoolean("connected")) TvButton("Connect August T405") {
                                    val cp = control.toIntOrNull() ?: 5002; val rp = receive.toIntOrNull() ?: 5001
                                    message = T405SessionController.connect(context, host, cp, rp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().then(if (phoneUi) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = if (phoneUi) 10.dp else 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Column(if (phoneUi) Modifier.width(140.dp) else Modifier.weight(1f)) {
                            Text(if (streamUrl.isNotEmpty()) (if (isPlaying) "ON AIR · ${if (useMpv) "MPV" else "MEDIA3 FALLBACK"}" else "ON AIR · PAUSED")
                                else if (pendingKey.isNotEmpty()) "TUNING" else "READY WHEN YOU ARE",
                                color = if (streamUrl.isNotEmpty()) tvBlue else tvMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(now.ifEmpty { "Programme information not yet broadcast" }, color = tvText,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TvButton(if (isPlaying) "Ⅱ Pause" else "▶ Play", streamUrl.isNotEmpty()) {
                            if (useMpv) {
                                if (NativeBridge.mpvPauseForT405(isPlaying)) isPlaying = !isPlaying
                                else message = "MPV has not finished initializing."
                            } else if (player.isPlaying) { player.pause(); isPlaying = false }
                            else { player.play(); isPlaying = true }
                        }
                        TvButton("■ Stop", streamUrl.isNotEmpty() || pendingKey.isNotEmpty()) {
                            pendingKey = ""; streamUrl = ""; activeKey = ""; if (NativeBridge.loaded) NativeBridge.tvStop()
                        }
                        if (streamUrl.isNotEmpty()) TvButton("⛶") { fullscreen = true }
                    }
                    if (streamUrl.isNotBlank() && useMpv) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            T405TrackMenu("CC · " + when (subtitleChoice) {
                                "no" -> "Off"; "auto" -> "Auto"; else -> "Track $subtitleChoice"
                            }, true, subtitleOptions, subtitleChoice) { setTrack("sid", it) }
                            T405TrackMenu("Audio · " + if (audioChoice == "auto") "Auto" else "Track $audioChoice",
                                false, audioOptions, audioChoice) { setTrack("aid", it) }
                            Text(if (subtitleOptions.isEmpty()) "No broadcast subtitles detected" else
                                "${subtitleOptions.size} broadcast subtitle track(s)", color = tvMuted, fontSize = 10.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().then(if (phoneUi) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(horizontal = if (phoneUi) 10.dp else 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(clock, color = tvText, fontSize = 12.sp)
                        Text("Now  ${now.ifEmpty { "—" }}   |   Next  ${next.ifEmpty { "—" }}", fontSize = 11.sp,
                            color = tvMuted, modifier = if (phoneUi) Modifier.width(180.dp) else Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Column {
                            Text("Signal", color = tvMuted, fontSize = 10.sp)
                            Text(if (!status.optBoolean("signalKnown")) "Not reported" else if (status.optBoolean("signalLocked"))
                                "Locked" else "No lock", color = if (status.optBoolean("signalLocked")) tvGreen else tvMuted, fontSize = 11.sp)
                        }
                        Column {
                            Text("TS rate", color = tvMuted, fontSize = 10.sp)
                            Text(if (tsRate >= 0) String.format(Locale.US, "%.1f Mbps", tsRate) else "—", color = tvText, fontSize = 11.sp)
                        }
                    }
                }
            }
            // The classic vertical channel list is intentionally removed.
            // The single Sky timeline below is the only guide and tune surface.
            playerPane(Modifier.fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            TvChip(if (!status.optBoolean("connected")) "○ Disconnected" else if (
                status.optLong("lastTsAgeMs", -1L) in 0L..4_000L) "● Transport receiving" else
                "● Socket ready", status.optLong("lastTsAgeMs", -1L) in 0L..4_000L)
            TvChip("$muxCount mux")
            TvChip("${channels.size} saved services")
            TvChip(if (T405GuardianService.active) "● Guardian" else "○ Guardian unavailable",
                T405GuardianService.active)
            TvButton(if (tunerSettings) "✕ Close settings" else "⚙ Tuner settings") {
                tunerSettings = !tunerSettings
            }
            TvButton(if (status.optBoolean("scanning")) "✕ Cancel scan" else "↻ Rescan") {
                if (NativeBridge.loaded) {
                    if (status.optBoolean("scanning")) NativeBridge.tvCancelScan()
                    else if (!startScan("uk_freeview")) message =
                        "Scan unavailable. Check tuner connection and stop playback first."
                }
            }
        }
        if (tunerSettings) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .border(1.dp, Color(0xFF27394E), RoundedCornerShape(18.dp))
                .background(tvPanel).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("T405 TUNER SETTINGS", color = tvBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                TunerPane(host, { host = it }, control, { control = it }, receive, { receive = it },
                    frequency, { frequency = it }, status, { message = it }, ::refresh, ::startScan)
                Text("Guardian: ${T405GuardianService.networkStatus}", color = tvMuted, fontSize = 11.sp)
                Text("Recovery: ${status.optString("recoveryAction", "Idle")} · attempts ${status.optLong("recoveryAttempts")} · restored ${status.optLong("recoverySuccesses")}",
                    color = tvMuted, fontSize = 11.sp)
                Text("Selected media: ${status.optLong("selectedMediaPackets")} packets · last ${status.optLong("selectedMediaAgeMs", -1L).let { if (it < 0L) "never" else "${it / 1000}s ago" }}",
                    color = tvMuted, fontSize = 11.sp)
            }
        }
        if (status.optBoolean("scanning")) {
            LinearProgressIndicator(progress = {
                status.optInt("scanProgress").toFloat() / status.optInt("scanTotal", 1).coerceAtLeast(1)
            }, modifier = Modifier.fillMaxWidth())
            Text("Scanning ${status.optInt("scanProgress")}/${status.optInt("scanTotal")} frequencies · ${status.optInt("scanFound")} multiplexes",
                color = tvMuted, fontSize = 11.sp)
        }
        OutlinedTextField(query, { query = it },
            placeholder = { Text("⌕ Search Sky guide channels or programmes…") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        T405SkyGuide(filtered, activeKey, ::tuneChannel, System.currentTimeMillis(),
            channels.size, guideChannels.size, status.optLong("eitNowNextSections"), status.optLong("eitScheduleSections"))
        if (message.isNotEmpty() || status.optString("error").isNotEmpty()) {
            Text(status.optString("error").ifEmpty { message }, color = tvMuted,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF142035), RoundedCornerShape(10.dp)).padding(12.dp),
                fontSize = 11.sp)
        }
        Text("Native Siano C++ → ${if (useMpv) "MPV" else "Media3 fallback (MPV ${if (mpvPresent) "failed" else "not bundled"})"} · Signal shown only when reported · TS rate from received packets.",
            color = Color(0xFF73869C), fontSize = 10.sp)
    }
    // Opaque overlay covers the dashboard in immersive fullscreen, but does not
    // reparent or recompose the native SurfaceView into a different player.
    if (fullscreen) Box(Modifier.fillMaxSize().zIndex(1f).background(Color.Black))
    if (streamUrl.isNotBlank() && videoSlotSize.width > 0 && videoSlotSize.height > 0) {
        val sizeModifier = if (fullscreen) Modifier.fillMaxSize()
            else Modifier.offset {
                IntOffset(videoSlotPosition.x - rootPixelPosition.x,
                    videoSlotPosition.y - rootPixelPosition.y)
            }.size(with(density) { videoSlotSize.width.toDp() }, with(density) { videoSlotSize.height.toDp() })
        Box(sizeModifier.zIndex(2f).background(Color.Black)) {
            if (useMpv) {
                T405MpvView(streamUrl,
                    onStarted = {
                        isPlaying = true
                        val mode = tvPreferences.getString("subtitleMode", "auto") ?: "auto"
                        ioScope.launch(Dispatchers.IO) { NativeBridge.mpvSetTrackForT405("sid", mode) }
                    },
                    onFailure = { problem ->
                        mpvFailed = true
                        message = "T405 MPV unavailable: $problem. Using Media3 fallback on this ABI."
                    }, modifier = Modifier.fillMaxSize())
            } else {
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
    if (fullscreen) {
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().zIndex(3f)
            .background(Color(0xBD08111C)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("← Exit fullscreen") { fullscreen = false }
            Text("${selected?.optString("number").orEmpty()}  ${selected?.optString("name").orEmpty()}",
                color = tvText, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            Text("● LIVE", color = tvGreen, fontSize = 12.sp)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(3f)
            .background(Color(0xCF08111C)).horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton(if (isPlaying) "Ⅱ Pause" else "▶ Play") {
                if (useMpv) {
                    if (NativeBridge.mpvPauseForT405(isPlaying)) isPlaying = !isPlaying
                    else message = "MPV has not finished initializing."
                } else if (player.isPlaying) { player.pause(); isPlaying = false }
                else { player.play(); isPlaying = true }
            }
            TvButton("■ Stop") {
                fullscreen = false; pendingKey = ""; streamUrl = ""; activeKey = ""
                if (NativeBridge.loaded) NativeBridge.tvStop()
            }
            if (useMpv) {
                T405TrackMenu("CC · " + when (subtitleChoice) {
                    "no" -> "Off"; "auto" -> "Auto"; else -> "Track $subtitleChoice"
                }, true, subtitleOptions, subtitleChoice) { setTrack("sid", it) }
                T405TrackMenu("Audio · " + if (audioChoice == "auto") "Auto" else "Track $audioChoice",
                    false, audioOptions, audioChoice) { setTrack("aid", it) }
            }
            Text(now.ifEmpty { "Live TV" }, color = tvText, maxLines = 1)
        }
    }
    } // stable Live TV root Box
}
