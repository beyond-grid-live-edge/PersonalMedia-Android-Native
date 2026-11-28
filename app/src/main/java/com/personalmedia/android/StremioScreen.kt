package com.personalmedia.android

import androidx.compose.foundation.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import org.json.JSONObject

/* Port of Index.ets Stremio(), StremioCinemaHome(), StremioDetails(),
 * StremioCinemaSourcePanel() and StremioBrowser(). All titles come from real add-ons. */
private val stBlue = Color(0xFF79B9FF)
private val stPanel = Color(0xFF101C2C)
private val stMuted = Color(0xFFB6C9E7)

/** In-memory keys only; configured transport addresses are never persisted unencrypted. */
private fun StremioCatalog.memoryKey(genre: String = ""): String =
    "$addonId\u0000$type\u0000$id\u0000$genre"

@Composable
private fun StAction(label: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp))
        .background(if (primary) Color(0xFF277FF5) else Color(0xFF25354B))
        .clickable(enabled = enabled, onClick = action)
        .heightIn(min = 38.dp).padding(horizontal = 13.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) Color.White else stMuted, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun StBackground(image: String, modifier: Modifier = Modifier,
    shade: Color = Color(0xC208101D)) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF143B65), Color(0xFF080F1C))))) {
        if (image.startsWith("http://") || image.startsWith("https://")) {
            AsyncImage(image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(shade, Color(0xC008101D), Color(0x3708101D)))))
    }
}

@Composable
private fun StCinemaTile(item: StremioMeta, open: (StremioMeta) -> Unit) {
    Box(Modifier.width(215.dp).height(142.dp).clip(RoundedCornerShape(13.dp))
        .border(1.dp, Color(0xFF283C59), RoundedCornerShape(13.dp))
        .clickable { open(item) }) {
        StBackground(item.background.ifEmpty { item.poster }, Modifier.fillMaxSize(), Color(0x76040C18))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD040C18)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text((if (item.type == "series") "SERIES" else "MOVIE") +
                (if (item.year.isNotBlank()) "  ·  ${item.year}" else ""),
                color = Color(0xFFB7CDF0), fontSize = 10.sp)
        }
    }
}

@Composable
private fun StCinemaRail(title: String, titles: List<StremioMeta>, goTo: () -> Unit,
    open: (StremioMeta) -> Unit, showViewAll: Boolean = true) {
    if (titles.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF1F6FF), modifier = Modifier.weight(1f))
            if (showViewAll) Text("View all  ›", color = Color(0xFF6DB2FF), fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = goTo))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(titles.take(60), key = { "${it.type}:${it.id}" }) { item -> StCinemaTile(item, open) }
        }
    }
}

@Composable
private fun StCinemaHero(item: StremioMeta, open: (StremioMeta) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 520.dp
        Box(Modifier.fillMaxWidth().height(if (narrow) 265.dp else 305.dp)
            .clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0xFF253E5F), RoundedCornerShape(16.dp))) {
            StBackground(item.background.ifEmpty { item.poster }, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(if (narrow) .95f else .57f)
                .padding(23.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("FEATURED FROM YOUR ADD-ONS", color = stBlue, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(item.name, fontSize = if (narrow) 26.sp else 37.sp,
                    lineHeight = if (narrow) 28.sp else 40.sp, color = Color.White,
                    fontWeight = FontWeight.Bold, maxLines = 2)
                Text((if (item.type == "series") "TV Series" else "Movie") +
                    (if (item.year.isNotBlank()) "  ·  ${item.year}" else ""), color = Color(0xFFC2D5EF), fontSize = 13.sp)
                Text(item.description.ifBlank { "Explore details and sources from your installed Stremio add-ons." },
                    color = Color(0xFFD7E5F7), fontSize = 12.sp, maxLines = 3)
                StAction("▶  View title & streams", true) { open(item) }
            }
        }
    }
}

@Composable
private fun StContinueCard(entry: StremioWatchEntry, open: () -> Unit) {
    Column(Modifier.width(225.dp).clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF111C2B)).border(1.dp, Color(0xFF283B52), RoundedCornerShape(12.dp))
        .clickable(onClick = open)) {
        Box(Modifier.fillMaxWidth().height(110.dp)) {
            StBackground(entry.background.ifBlank { entry.poster }, Modifier.fillMaxSize())
            Text("▶ RESUME", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color(0xC120355B)).padding(9.dp))
        }
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("Resume at ${entry.positionMs / 60000}:${(entry.positionMs / 1000 % 60).toString().padStart(2, '0')}",
                color = Color(0xFF93BDFC), fontSize = 11.sp)
        }
    }
}

/** Posters in Search/Discover/Library; no fake artwork when an add-on omits it. */
@Composable
private fun StBrowsePoster(item: StremioMeta, modifier: Modifier = Modifier,
    open: (StremioMeta) -> Unit) {
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    Column(modifier.clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF111E30)).border(1.dp, Color(0xFF283C59), RoundedCornerShape(12.dp))
        .clickable { open(item) }) {
        Box(Modifier.fillMaxWidth().height(if (phoneUi) 221.dp else 190.dp)) {
            StBackground(item.poster.ifBlank { item.background }, Modifier.fillMaxSize(), Color(0x4807101D))
            if (item.poster.isBlank() && item.background.isBlank())
                Text("Artwork unavailable", color = stMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                minLines = 2)
            Text((if (item.type == "series") "SERIES" else "MOVIE") +
                (if (item.year.isNotBlank()) " · ${item.year}" else ""),
                color = stMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun StBrowseGrid(titles: List<StremioMeta>, open: (StremioMeta) -> Unit) {
    val unique = remember(titles) { StremioBrowsePolicy.uniqueTitles(titles).take(160) }
    var visible by remember(titles) { mutableIntStateOf(24) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when { maxWidth < 500.dp -> 2; maxWidth < 900.dp -> 3; else -> 5 }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            unique.take(visible).chunked(columns).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    line.forEach { item -> StBrowsePoster(item, Modifier.weight(1f), open) }
                    repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (unique.size > visible)
                StAction("Show more titles") { visible = (visible + 24).coerceAtMost(unique.size) }
        }
    }
}

@Composable
private fun StEpisodeRow(video: StremioVideo, selected: Boolean, watched: Boolean?,
    select: () -> Unit, markWatched: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
        .background(if (selected) Color(0xFF183A62) else stPanel)
        .border(1.dp, if (selected) Color(0xFF3774B5) else Color(0xFF243752), RoundedCornerShape(11.dp))
        .clickable(onClick = select).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(106.dp).height(67.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B3048))) {
            if (video.thumbnail.startsWith("https://") || video.thumbnail.startsWith("http://")) {
                AsyncImage(video.thumbnail, contentDescription = video.title,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else Text("S${video.season} E${video.episode}", color = stMuted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${video.episode}. ${video.title}", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            if (video.released.isNotBlank())
                Text(video.released.take(10), color = stBlue, fontSize = 10.sp)
            if (video.overview.isNotBlank())
                Text(video.overview, color = stMuted, fontSize = 11.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (watched == true) "✓ Watched" else if (watched == false) "Unwatched" else "Unknown",
                color = if (watched == true) Color(0xFF81E6BD) else stMuted, fontSize = 10.sp)
            StAction(if (watched == true) "Mark unwatched" else "Mark watched") {
                markWatched(watched != true)
            }
        }
    }
}

@Composable
fun StremioScreen(client: StremioClient, revision: Int, onRevision: () -> Unit,
    onMessage: (String) -> Unit, onPlay: (String, List<StremioSubtitle>) -> Unit,
    onTorrentPlay: (String, List<StremioSubtitle>, StremioMeta, String) -> Unit,
    onDirectPlay: (StremioSource, StremioMeta, String) -> Unit,
    preferredQuality: String = "auto",
    startOnDiscover: Boolean = false,
    discoverSignal: Int = 0,
    onWatch: (StremioMeta, String) -> Unit = { _, _ -> }) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    val cachePath = appContext.cacheDir.absolutePath
    val watchStore = remember(client.email) { StremioWatchStore(appContext, client.email) }
    val watchedStore = remember(client.email) { StremioWatchedStore(appContext, client.email) }
    var watchedRevision by remember(client.email) { mutableIntStateOf(0) }
    var watchedFilter by remember { mutableStateOf("All") }
    var syncBusy by remember { mutableStateOf(false) }
    var historyStatus by remember { mutableStateOf("") }
    val catalogCache = remember(appContext) { StremioCatalogCache(appContext) }
    val featuredSlices = remember { mutableStateMapOf<String, List<StremioMeta>>() }
    var featuredAccount by remember { mutableStateOf("") }
    val recentWatches = remember(revision) { watchStore.get() }
    var busy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(if (startOnDiscover) "Discover" else "Home") }
    val sectionHistory = remember { PersonalMediaNavHistory(if (startOnDiscover) "Discover" else "Home") }
    fun navigateSection(destination: String) {
        sectionHistory.visit(section, destination)
        section = destination
    }
    LaunchedEffect(discoverSignal) {
        if (phoneUi && discoverSignal > 0 && section != "Discover") navigateSection("Discover")
    }
    var searchResults by remember { mutableStateOf(emptyList<StremioMeta>()) }
    var browseResults by remember { mutableStateOf(emptyList<StremioMeta>()) }
    var searchType by remember { mutableStateOf("All") }
    var searchExecuted by remember { mutableStateOf(false) }
    var homeMovies by remember { mutableStateOf(emptyList<StremioMeta>()) }
    var homeSeries by remember { mutableStateOf(emptyList<StremioMeta>()) }
    var library by remember { mutableStateOf(emptyList<StremioMeta>()) }
    var detail by remember { mutableStateOf<StremioMeta?>(null) }
    var sources by remember { mutableStateOf(emptyList<StremioSource>()) }
    var streamQualityFilter by remember { mutableStateOf("All") }
    var streamVisibleCount by remember { mutableIntStateOf(StremioStreamListPolicy.PAGE_SIZE) }
    var streamFetchStatus by remember { mutableStateOf("") }
    fun resetStreamView() {
        streamQualityFilter = PlaybackPreferencesPolicy.streamFilter(
            preferredQuality, sources.map { it.quality })
        streamVisibleCount = StremioStreamListPolicy.PAGE_SIZE
        streamFetchStatus = ""
    }
    var selectedVideo by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf<StremioCatalog?>(null) }
    var category by remember { mutableStateOf(if (startOnDiscover) "all" else "movie") }
    var season by remember { mutableIntStateOf(1) }
    var episodeLimit by remember { mutableIntStateOf(32) }
    var genre by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var sourceBusy by remember { mutableStateOf(false) }
    var torrentStopping by remember { mutableStateOf(false) }
    var torrentPreparation by remember { mutableStateOf<Job?>(null) }
    // Each attempt owns its UI and cleanup. A cancelled, slow native opener
    // may finish later but must never reset or stop a newer torrent session.
    var preparationGeneration by remember { mutableLongStateOf(0L) }
    // Preparation/progress belongs to one stream card, never to the top of the list.
    var activeSource by remember { mutableStateOf<StremioSource?>(null) }
    var activeAction by remember { mutableStateOf("Play") }
    var torrentStage by remember { mutableStateOf("") }
    var torrentStats by remember { mutableStateOf("") }
    var torrentStartedAtMs by remember { mutableLongStateOf(0L) }
    var torrentElapsedMs by remember { mutableLongStateOf(0L) }
    var downloadProgress by remember { mutableStateOf("") }
    val p2pPrefs = remember(appContext) {
        appContext.getSharedPreferences("personalmedia_p2p_consent", android.content.Context.MODE_PRIVATE)
    }
    var p2pEnabled by remember { mutableStateOf(p2pPrefs.getBoolean("enabled", false)) }
    var awaitingDownloadSource by remember { mutableStateOf<Pair<StremioSource, String>?>(null) }
    var downloadFolderMessage by remember { mutableStateOf("") }
    // Storage Access Framework folder grants persist across process restarts.
    val queueReady = remember(appContext) {
        runCatching { DownloadQueueRepository.init(appContext); true }.getOrElse { false }
    }
    fun queueDownload(source: StremioSource, title: String) {
        if (!queueReady) { onMessage("Encrypted download storage is unavailable"); return }
        try {
            if (source.kind == "Torrent") {
                val issue = TorrentStartPolicy.issue(p2pEnabled, NativeBridge.isTorrentReady(), source.hash)
                if (issue != null) { onMessage(issue); return }
            }
            val extensionHint = if (source.kind == "Direct HTTP")
                runCatching { java.net.URI(source.url).path.orEmpty() }.getOrDefault("")
                else source.release
            val name = TorrentExportPolicy.suggestedName(title, extensionHint, source.hash)
            val added = DownloadQueueRepository.enqueue(appContext, source, title, name)
            if (!DownloadQueueService.kickChecked(appContext)) {
                DownloadQueueRepository.update(appContext, added.id) {
                    it.copy(state = DownloadQueuePolicy.PAUSED, info = "Service unavailable; select Resume")
                }
                onMessage("Download saved to queue. Open Downloads and resume when Android allows it.")
            } else onMessage("Download added to persistent queue")
            section = "Downloads"
            detail = null
            sources = emptyList()
        } catch (e: Exception) {
            onMessage((e.message ?: "Could not queue this source").take(140))
        }
    }
    val downloadFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                appContext.contentResolver.takePersistableUriPermission(uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                DownloadQueueRepository.setFolder(appContext, uri)
                downloadFolderMessage = "Downloads location saved"
                awaitingDownloadSource?.let { (selected, title) -> queueDownload(selected, title) }
            } catch (_: Exception) {
                onMessage("Could not save folder permission; select a writable subfolder")
            }
        } else if (awaitingDownloadSource != null) onMessage("No downloads folder selected")
        awaitingDownloadSource = null
    }
    // Do not conceal errors when a preparation attempt ends.
    fun torrentNotice(text: String) {
        torrentStage = text
        onMessage(text)
    }
    /** Cancels the *current* attempt immediately. Native shutdown detaches its
     * old session, so the next selection does not wait for peer sockets to exit.
     * The generation check prevents late completion of this coroutine from
     * overwriting another stream's UI or closing its engine. */
    fun cancelPreparation(note: String = "Torrent preparation cancelled") {
        if (!sourceBusy) return
        val cancelledKind = activeSource?.kind
        val cancelGeneration = ++preparationGeneration
        torrentStage = note
        sourceBusy = false
        torrentStopping = cancelledKind == "Torrent"
        torrentPreparation?.cancel()
        if (cancelledKind == "Torrent" && NativeBridge.loaded) {
            runCatching { NativeBridge.torrentRequestCancel() }
            scope.launch {
                try {
                    withContext(NonCancellable + Dispatchers.IO) { NativeBridge.torrentStop() }
                } finally {
                    TorrentEngineLease.releasePlayback()
                    DownloadQueueService.kick(appContext)
                    if (TorrentAttemptPolicy.isCurrent(cancelGeneration, preparationGeneration)) {
                        torrentStopping = false
                        torrentStats = ""
                        torrentPreparation = null
                    }
                }
            }
        } else {
            torrentStopping = false
            torrentPreparation = null
        }
    }
    fun consent(enabled: Boolean) {
        p2pEnabled = enabled
        p2pPrefs.edit().putBoolean("enabled", enabled).apply()
        if (!enabled) cancelPreparation("P2P disabled; torrent preparation cancelled")
    }
    // Per-card, safe numeric status; the source URL and tracker secrets never
    // appear in UI messages. No stats polling after Cancel is pressed.
    LaunchedEffect(sourceBusy, activeSource?.hash) {
        while (sourceBusy && activeSource?.kind == "Torrent") {
            torrentElapsedMs = (android.os.SystemClock.elapsedRealtime() - torrentStartedAtMs)
                .coerceAtLeast(0L)
            val row = withContext(Dispatchers.IO) {
                runCatching { JSONObject(NativeBridge.torrentStatus()) }.getOrNull()
            }
            if (row != null && row.optBoolean("active")) {
                // M05d deliberately hides the libtorrent status snapshot that
                // caused the native crash. Its peer/rate counters are unknown,
                // not zero: only show fields the safe status API can measure.
                torrentStats = StremioStartupPolicy.torrentProgress(
                    row.optBoolean("metadata", false), row.optLong("verifiedAhead", 0L),
                    row.optInt("trackerReplies", 0), row.optInt("dhtNodes", 0))
            }
            delay(1250)
        }
    }
    var requestedResumeVideo by remember { mutableStateOf("") }

    BackHandler(enabled = detail != null || section != "Home") {
        cancelPreparation()
        if (detail != null) {
            detail = null
            sources = emptyList()
        } else {
            section = sectionHistory.previous(section) ?: "Home"
            sources = emptyList()
        }
    }

    fun editWatched(meta: StremioMeta, video: StremioVideo, isWatched: Boolean) {
        if (meta.type != "series") return
        watchedStore.rememberSeries(meta)
        watchedStore.setLocal(meta.id, video.id, isWatched)
        watchedRevision++
        onMessage(if (isWatched) "Episode marked watched locally" else "Episode marked unwatched locally")
    }
    fun importWatched() {
        val owner = client.email
        if (!client.loggedIn || owner.isBlank() || syncBusy) return
        scope.launch {
            syncBusy = true
            try {
                val remote = client.accountWatchedRaw()
                if (client.loggedIn && client.email == owner) {
                    watchedStore.importRemote(remote)
                    watchedRevision++
                    historyStatus = "Imported watched state from ${remote.size} Stremio series records"
                    onMessage(historyStatus)
                }
            } catch (_: Exception) {
                if (client.email == owner) onMessage("Watched history import unavailable; pending local changes kept")
            } finally { syncBusy = false }
        }
    }
    fun uploadWatched() {
        val owner = client.email
        if (!client.loggedIn || owner.isBlank() || syncBusy || !watchedStore.twoWayEnabled) return
        scope.launch {
            syncBusy = true
            var completed = 0
            try {
                val queue = watchedStore.pending()
                for (batch in queue.groupBy { it.seriesId }) {
                    if (!client.loggedIn || client.email != owner) break
                    val seed = watchedStore.seriesMetadata(batch.key)
                        ?: throw IllegalStateException("Open the series details before syncing its watched edits")
                    val fresh = client.detail(seed)
                    if (client.email != owner) break
                    // Refuse an unrecognised episode list; no blind bitfield replacement.
                    check(fresh.videos.isNotEmpty()) { "Episode metadata missing for this series" }
                    val raw = client.putAccountWatchedChanges(fresh, batch.value)
                    if (client.email != owner) break
                    watchedStore.updateAfterUpload(batch.key, raw, batch.value)
                    completed += batch.value.size
                    watchedRevision++
                }
                if (client.email == owner) onMessage("Synced $completed watched changes; ${watchedStore.pending().size} queued")
            } catch (e: Exception) {
                if (client.email == owner) onMessage("Sync paused: ${e.message ?: "Account unavailable"}. Unsent edits are preserved")
            } finally { syncBusy = false }
        }
    }
    // STR-3b: Place the same watched-sync controls where episodes are edited,
    // rather than hiding them exclusively in the off-screen Settings tab.
    // This only changes navigation/UI: two-way writes still require opt-in AND Sync.
    @Composable fun watchedSyncControls() {
        val currentWatchRevision = watchedRevision
        val queuedCount = remember(currentWatchRevision, client.email) { watchedStore.pending().size }
        val twoWay = watchedStore.twoWayEnabled
        Column(Modifier.fillMaxWidth().background(stPanel, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Watched history sync", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 16.sp)
            Text(
                (if (watchedStore.imported) "Official history imported · ${watchedStore.remoteCount()} series"
                 else "Official history not imported") + " · $queuedCount local change(s) queued",
                color = stMuted, fontSize = 11.sp
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Two-way account sync (opt-in)", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("Off by default. Import is read-only; only Sync uploads changes.",
                        color = stMuted, fontSize = 11.sp)
                }
                Switch(checked = twoWay, enabled = !syncBusy, onCheckedChange = { enabled ->
                    watchedStore.twoWayEnabled = enabled
                    watchedRevision++
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                StAction("↻ Import history", enabled = !syncBusy) { importWatched() }
                StAction("↑ Sync ($queuedCount)", primary = true,
                    enabled = twoWay && queuedCount > 0 && !syncBusy) { uploadWatched() }
            }
            if (!twoWay && queuedCount > 0) Text(
                "Enable two-way sync to send queued edits to your Stremio account.",
                color = stMuted, fontSize = 11.sp)
            if (historyStatus.isNotBlank()) Text(historyStatus, color = stMuted, fontSize = 11.sp)
        }
    }

    fun task(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() } catch (e: Exception) { onMessage(e.message ?: "Stremio request failed") }
            finally { busy = false }
        }
    }
    suspend fun refreshConnectedAddons() {
        try {
            client.refreshAddons()
            onRevision()
        } catch (_: Exception) {
            // A temporary add-on outage must never be reported as a failed login.
            onMessage("Signed in, but add-on refresh is currently unavailable")
        }
    }
    fun openItem(item: StremioMeta) {
        cancelPreparation()
        activeSource = null
        val requested = requestedResumeVideo.also { requestedResumeVideo = "" }
        detail = item; sources = emptyList(); resetStreamView()
        selectedVideo = requested.ifEmpty { item.id }
        episodeLimit = 32
        val accountAtStart = client.email
        task {
            val result = client.detail(item)
            if (detail?.id != item.id || detail?.type != item.type || client.email != accountAtStart) return@task
            detail = result
            if (result.type == "series" && result.videos.isNotEmpty()) watchedStore.rememberSeries(result)
            val chosen = result.videos.firstOrNull { it.id == requested }
                ?: result.videos.firstOrNull { it.season > 0 } ?: result.videos.firstOrNull()
            selectedVideo = chosen?.id ?: result.id
            season = chosen?.season ?: 1
        }
    }
    fun playSource(source: StremioSource, saveTo: Uri? = null) {
        if (source.kind !in setOf("Torrent", "Direct HTTP")) {
            onMessage("Unsupported Stremio stream type")
            return
        }
        if (source.kind == "Direct HTTP" && saveTo == null) {
            val selected = detail ?: return
            if (!StremioPlaybackPolicy.safeTransport(source.url, source.requestHeaders)) {
                onMessage("The stream address or request headers are not safe for native playback")
                return
            }
            onDirectPlay(source, selected, selectedVideo.ifEmpty { selected.id })
            return
        }
        if (sourceBusy || torrentStopping) {
            torrentStage = if (torrentStopping) "Finishing previous session cleanup…" else
                "A stream is already preparing. Cancel it before selecting another."
            return
        }
        if (source.kind == "Torrent") {
            val issue = TorrentStartPolicy.issue(p2pEnabled, NativeBridge.isTorrentReady(), source.hash)
            if (issue != null) { activeSource = source; torrentNotice(issue); return }
            if (!TorrentEngineLease.acquirePlayback()) {
                activeSource = source
                torrentNotice("A queued torrent download is active. Pause it in Downloads before playing this stream.")
                return
            }
        } else if (!StremioPlaybackPolicy.safeTransport(source.url, source.requestHeaders)) {
            activeSource = source; torrentNotice("This direct media address cannot be downloaded safely")
            return
        }
        val selected = detail ?: return
        val selectedId = selectedVideo.ifEmpty { selected.id }
        activeSource = source
        activeAction = if (saveTo == null) "Play" else "Download"
        sourceBusy = true
        torrentStartedAtMs = android.os.SystemClock.elapsedRealtime()
        torrentElapsedMs = 0L
        torrentStage = if (saveTo == null) "Connecting to peers…"
            else if (source.kind == "Torrent") "Preparing verified pieces for export…"
            else "Downloading direct media to your chosen document…"
        torrentStats = ""
        downloadProgress = ""
        val attemptGeneration = ++preparationGeneration
        torrentPreparation = scope.launch {
            var handedOff = false
            try {
                if (source.kind == "Direct HTTP") {
                    check(saveTo != null)
                    StremioDirectFileSave.export(appContext, source, saveTo) { done, total ->
                        downloadProgress = if (total > 0) "Saving: ${TorrentExportPolicy.percent(done, total)}%" +
                            " · ${done / 1048576} / ${total / 1048576} MiB" else
                            "Saving: ${done / 1048576} MiB"
                    }
                    torrentNotice("Download complete. Saved to the document location you selected.")
                } else {
                    val result = withContext(Dispatchers.IO) {
                        NativeBridge.torrentOpen(source.hash, source.fileIndex,
                            source.trackers.joinToString("\n"), cachePath)
                    }
                    check(result.startsWith("ready:")) {
                        if (result.contains("cancelled", true)) "Torrent preparation cancelled" else
                            "Native torrent session could not start. Verify cache permissions and your torrent-enabled APK."
                    }
                    torrentStage = "Connecting to peers and retrieving authenticated metadata…"
                    // -1 is a common Stremio fileIdx. JNI resolves it from verified metadata.
                    var mediaUrl = ""
                    for (attempt in 1..120) {
                        delay(1000)
                        mediaUrl = withContext(Dispatchers.IO) {
                            NativeBridge.torrentRangeUrl(source.hash, source.fileIndex)
                        }
                        if (mediaUrl.isNotEmpty()) break
                        if (attempt % 10 == 0) torrentStage =
                            "Still preparing torrent (${attempt}s). " +
                                "If this continues, try a better-seeded source."
                    }
                    check(mediaUrl.isNotBlank()) {
                        "No authenticated metadata or playable video after two minutes. " +
                            "Try a different source with active peers."
                    }
                    check(TorrentLoopbackPolicy.isNativeTorrentUrl(mediaUrl)) {
                        "Native torrent bridge did not return a private loopback URL"
                    }
                    if (saveTo != null) {
                        val info = withContext(Dispatchers.IO) {
                            JSONObject(NativeBridge.torrentStatus())
                        }
                        val size = info.optLong("size", 0L)
                        check(size > 0) { "Authenticated torrent file size unavailable" }
                        torrentStage = "Saving authenticated torrent pieces to your chosen document…"
                        TorrentVerifiedFileSave.export(appContext, saveTo, size) { done, total ->
                            downloadProgress = "Saving verified bytes: " +
                                "${TorrentExportPolicy.percent(done, total)}% · " +
                                "${done / 1048576} / ${total / 1048576} MiB"
                        }
                        torrentNotice("Verified download complete. Saved to the chosen document location.")
                    } else {
                        torrentStage = "Preparing verified video for native MPV…"
                        onWatch(selected, selectedId)
                        onTorrentPlay(mediaUrl, source.subtitles, selected, selectedId)
                        handedOff = true
                    }
                }
            } catch (_: CancellationException) {
                // The onClick handler already removed the spinner immediately.
                if (torrentStage.isBlank()) torrentStage = "Operation cancelled"
            } catch (ex: Exception) {
                torrentNotice((ex.message ?: "Unable to prepare the source").take(200))
            } finally {
                // Cancel increments preparationGeneration. The old JNI call may
                // return after a new start, and MUST NOT stop that new session.
                if (TorrentAttemptPolicy.isCurrent(attemptGeneration, preparationGeneration)) {
                    if (!handedOff && source.kind == "Torrent" && NativeBridge.loaded) {
                        sourceBusy = false
                        torrentStopping = true
                        // torrentStop detaches the old session; slow libtorrent
                        // destruction proceeds on a native reaper thread.
                        withContext(NonCancellable + Dispatchers.IO) { NativeBridge.torrentStop() }
                        TorrentEngineLease.releasePlayback()
                        DownloadQueueService.kick(appContext)
                    }
                    if (TorrentAttemptPolicy.isCurrent(attemptGeneration, preparationGeneration)) {
                        sourceBusy = false
                        torrentStopping = false
                        torrentPreparation = null
                        if (!handedOff && downloadProgress.isBlank()) torrentStats = ""
                    }
                }
            }
        }
    }
    // Android Storage Access Framework: choosing a save location is a real action.
    // No torrent is started until the picker supplies a writable document URI.
    fun chooseDownload(source: StremioSource) {
        val selected = detail ?: return
        if (!queueReady) { onMessage("Encrypted download storage unavailable"); return }
        if (DownloadQueueRepository.chosenFolder(appContext).isEmpty()) {
            awaitingDownloadSource = source to selected.name
            downloadFolderPicker.launch(null)
        } else queueDownload(source, selected.name)
    }
    fun publishHome() {
        homeMovies = StremioBrowsePolicy.uniqueTitles(
            StremioBrowsePolicy.featuredCatalogs(client.catalogs, "movie")
                .flatMap { featuredSlices[it.memoryKey()].orEmpty() }).take(60)
        homeSeries = StremioBrowsePolicy.uniqueTitles(
            StremioBrowsePolicy.featuredCatalogs(client.catalogs, "series")
                .flatMap { featuredSlices[it.memoryKey()].orEmpty() }).take(60)
    }
    // Import official account watch flags in the background; preserve local queued changes.
    LaunchedEffect(client.email) {
        val owner = client.email
        if (owner.isNotBlank() && client.loggedIn) {
            runCatching { client.accountWatchedRaw() }.onSuccess { remote ->
                if (client.loggedIn && client.email == owner) {
                    watchedStore.importRemote(remote)
                    watchedRevision++
                    historyStatus = "Official watched history imported"
                }
            }
        }
    }
    // Home no longer waits for (possibly slow) account-library sync, and does
    // not fetch each catalogue sequentially. Cached shelves appear first;
    // independent providers fill in as soon as each response arrives.
    LaunchedEffect(client.email, revision) {
        if (!client.loggedIn) {
            featuredAccount = ""; featuredSlices.clear()
            homeMovies = emptyList(); homeSeries = emptyList(); library = emptyList()
            return@LaunchedEffect
        }
        val owner = client.email
        if (featuredAccount != owner) {
            featuredAccount = owner
            featuredSlices.clear()
            homeMovies = emptyList(); homeSeries = emptyList(); library = emptyList()
            browseResults = emptyList(); selectedCat = null
        }
        val plans = StremioBrowsePolicy.featuredCatalogs(client.catalogs, "movie") +
            StremioBrowsePolicy.featuredCatalogs(client.catalogs, "series")
        publishHome()
        coroutineScope {
            launch {
                runCatching { client.library() }.onSuccess { if (client.email == owner) library = it }
            }
            plans.forEach { cat ->
                launch {
                    val key = cat.memoryKey()
                    val cached = catalogCache.load(owner, cat)
                    if (client.loggedIn && client.email == owner && cached.isNotEmpty() &&
                        featuredSlices[key].isNullOrEmpty()) {
                        featuredSlices[key] = cached
                        publishHome()
                    }
                    val fetched = runCatching { client.catalog(cat) }.getOrNull()
                    if (fetched != null && fetched.isNotEmpty()) {
                        if (client.loggedIn && client.email == owner) {
                            featuredSlices[key] = fetched
                            publishHome()
                        }
                        catalogCache.save(owner, cat, titles = fetched)
                    }
                }
            }
        }
    }
    // Discover opens on combined results, not a blank "choose catalogue" page.
    // All provider requests run independently and are cancelled when the
    // user switches type, genre, catalogue or account.
    LaunchedEffect(client.email, revision, section, category, selectedCat, genre) {
        if (!client.loggedIn || section != "Discover") return@LaunchedEffect
        val owner = client.email
        val chosen = selectedCat?.takeIf { category == "all" || it.type == category }
        val plans = if (chosen != null) listOf(chosen)
            else if (category == "all")
                StremioBrowsePolicy.featuredCatalogs(client.catalogs, "movie", 4) +
                    StremioBrowsePolicy.featuredCatalogs(client.catalogs, "series", 4)
            else StremioBrowsePolicy.featuredCatalogs(client.catalogs, category, 4)
        val selectedGenre = if (chosen == null) "" else genre
        val chunks = mutableMapOf<String, List<StremioMeta>>()
        fun publish() {
            if (client.loggedIn && client.email == owner) {
                browseResults = StremioBrowsePolicy.uniqueTitles(
                    plans.flatMap { chunks[it.memoryKey(selectedGenre)].orEmpty() }).take(160)
            }
        }
        plans.forEach { cat ->
            if (selectedGenre.isEmpty()) {
                val memory = featuredSlices[cat.memoryKey()]
                if (!memory.isNullOrEmpty()) chunks[cat.memoryKey(selectedGenre)] = memory
            }
        }
        publish() // This is synchronous when switching Home → Discover.
        coroutineScope {
            plans.forEach { cat ->
                launch {
                    val key = cat.memoryKey(selectedGenre)
                    val cached = catalogCache.load(owner, cat, selectedGenre)
                    if (cached.isNotEmpty() && chunks[key].isNullOrEmpty()) {
                        chunks[key] = cached; publish()
                    }
                    val latest = runCatching { client.catalog(cat, selectedGenre) }.getOrNull()
                    if (!latest.isNullOrEmpty()) {
                        if (client.loggedIn && client.email == owner) {
                            chunks[key] = latest
                            publish()
                        }
                        if (latest.isNotEmpty()) catalogCache.save(owner, cat, selectedGenre, latest)
                    }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(if (phoneUi) phoneNavy else pmBg)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = if (phoneUi) 17.dp else 24.dp,
            vertical = if (phoneUi) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PERSONALMEDIA  /  STREMIO", color = stBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                Text(if (phoneUi && section == "Discover") "Discover" else "Your cinema",
                    fontWeight = FontWeight.Bold, color = Color.White, fontSize = 29.sp)
            }
            if (client.loggedIn && !phoneUi) Text("Your add-ons · Native playback",
                fontSize = 11.sp, color = Color(0xFFA6C7EE))
        }
        if (!client.loggedIn) {
            Column(Modifier.fillMaxWidth().background(stPanel, RoundedCornerShape(16.dp)).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in to your Stremio account", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                StAction("Sign in", true, !busy) {
                    task {
                        try {
                            client.signIn(email, password)
                            onRevision() // Login succeeds independently of add-on network availability.
                            refreshConnectedAddons()
                        } finally { password = "" }
                    }
                }
                Text("Or use an existing auth key", color = stMuted, fontSize = 13.sp)
                OutlinedTextField(authKey, { authKey = it }, label = { Text("Stremio auth key") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
                StAction("Use auth key", enabled = !busy) {
                    task {
                        try {
                            client.signInWithKey(authKey)
                            onRevision()
                            refreshConnectedAddons()
                        } finally { authKey = "" }
                    }
                }
                Text("Your credentials are protected by Android Keystore-backed encrypted preferences.",
                    color = stMuted, fontSize = 11.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth().background(stPanel, RoundedCornerShape(11.dp))
                .padding(if (phoneUi) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("STREMIO", color = Color(0xFF67AEFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("● Connected", color = Color(0xFF85E4BB), fontSize = 11.sp)
                if (!phoneUi) Text(client.email, color = stMuted, fontSize = 12.sp,
                    maxLines = 1, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                Text("${client.addonInfo.size} add-ons", color = stMuted, fontSize = 11.sp)
                StAction("↻ Refresh", enabled = !busy) { task { refreshConnectedAddons() } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(listOf("Home", "Search", "Discover", "Library", "Downloads", "Add-ons", "Settings")) { name ->
                    PmPill(name, section == name) {
                        navigateSection(name); detail = null; sources = emptyList()
                        if (name == "Library") task {
                        val owner = client.email
                        val fresh = client.library()
                        if (client.email == owner) library = fresh
                    }
                    }
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = stBlue)
            if (detail != null) {
                val item = detail!!
                Text("‹  Back to $section", color = stBlue, fontSize = 12.sp,
                    modifier = Modifier.clickable { detail = null; sources = emptyList() })
                Box(Modifier.fillMaxWidth().height(305.dp).clip(RoundedCornerShape(15.dp))) {
                    StBackground(item.background.ifEmpty { item.poster }, Modifier.fillMaxSize())
                    Row(Modifier.align(Alignment.CenterStart).padding(23.dp),
                        horizontalArrangement = Arrangement.spacedBy(17.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.poster.isNotBlank()) AsyncImage(item.poster, contentDescription = item.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.width(100.dp).height(157.dp)
                                .clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(item.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                                lineHeight = 31.sp, maxLines = 2)
                            Text("${item.year}  ·  ${if (item.type == "series") "TV Series" else "Movie"}",
                                color = Color(0xFFB7D7FF), fontSize = 12.sp)
                            Text(item.description.ifBlank { "Details provided by your installed Stremio add-ons." },
                                color = Color(0xFFE2EAF7), fontSize = 12.sp, maxLines = 4)
                            StAction(if (library.any { it.type == item.type && it.id == item.id })
                                "✓ In Library · Remove" else "+ Add to Library", true, !busy) {
                                task {
                                    val saved = library.any { it.type == item.type && it.id == item.id }
                                    client.setLibraryMembership(item, !saved)
                                    library = client.library()
                                    onMessage(if (saved) "Removed from Stremio library" else "Saved to Stremio account")
                                }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val narrow = maxWidth < 640.dp
                    @Composable fun info() {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (item.type == "series") {
                                Text("Seasons", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                val seasons = remember(item.videos) { item.videos.map { it.season }.distinct().sorted() }
                                if (seasons.isEmpty()) Text("No episode list supplied by the connected add-ons.",
                                    color = stMuted, fontSize = 12.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items(seasons) { value ->
                                        PmPill(if (value == 0) "Specials" else "Season $value", season == value) {
                                            season = value; episodeLimit = 32
                                            selectedVideo = StremioBrowsePolicy.episodesInSeason(item.videos, value)
                                                .firstOrNull()?.id.orEmpty()
                                            sources = emptyList(); resetStreamView()
                                        }
                                    }
                                }
                                val episodes = remember(item.videos, season) {
                                    StremioBrowsePolicy.episodesInSeason(item.videos, season)
                                }
                                if (episodes.isNotEmpty()) {
                                    // Reading this state causes immediate recomposition after a local edit/import.
                                    val watchVersion = watchedRevision
                                    val statuses = remember(item.videos, watchVersion, item.id) {
                                        watchedStore.statuses(item.id, item.videos)
                                    }
                                    val watchedCount = episodes.count { statuses[it.id] == true }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Episodes  ·  $watchedCount/${episodes.size} watched", fontSize = 19.sp, color = Color.White,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    }
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(listOf("All", "Watched", "Unwatched")) { option ->
                                            PmPill(option, watchedFilter == option) { watchedFilter = option; episodeLimit = 32 }
                                        }
                                    }
                                    // Keep the upload controls visible above the episode list on phones.
                                    watchedSyncControls()
                                    val filteredEpisodes = episodes.filter { video ->
                                        watchedFilter == "All" || (watchedFilter == "Watched" && statuses[video.id] == true) ||
                                            (watchedFilter == "Unwatched" && statuses[video.id] == false)
                                    }
                                    if (filteredEpisodes.isEmpty()) Text("No episodes match this watched filter.", color = stMuted)
                                    filteredEpisodes.take(episodeLimit).forEach { episode ->
                                        StEpisodeRow(episode, selectedVideo == episode.id, statuses[episode.id], {
                                            selectedVideo = episode.id; sources = emptyList()
                                        }, { checked -> editWatched(item, episode, checked) })
                                    }
                                    if (filteredEpisodes.size > episodeLimit)
                                        StAction("Show ${minOf(32, filteredEpisodes.size - episodeLimit)} more episodes") {
                                            episodeLimit += 32
                                        }
                                }
                            } else {
                                Column(Modifier.fillMaxWidth().background(Color(0xFF101D2F), RoundedCornerShape(13.dp))
                                    .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("About this movie", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Text(item.description.ifEmpty { "Choose an available source to watch." }, color = stMuted, fontSize = 13.sp)
                                    Text("Sources are supplied by your installed add-ons and checked when selected.",
                                        color = Color(0xFF93ACC8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    @Composable fun streamPanel() {
                        Column(Modifier.background(Color(0xFF101D2F), RoundedCornerShape(13.dp)).padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Available streams", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = p2pEnabled, onCheckedChange = ::consent)
                                Text("Allow peer-to-peer torrent streaming (your IP is visible to peers)",
                                    color = stMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            }
                            Text("Native P2P engine: " +
                                (if (NativeBridge.isTorrentReady()) "Available" else "Unavailable in installed APK"),
                                color = if (NativeBridge.isTorrentReady()) Color(0xFF85E4BB) else Color(0xFFFFC578),
                                fontSize = 11.sp)
                            StAction("↻ Find streams", true, !busy && !sourceBusy && !torrentStopping) {
                                task {
                                    val requestedId = selectedVideo.ifEmpty { item.id }
                                    val report = client.sourcesReport(item, requestedId)
                                    // Do not show the previous film/episode's stream response.
                                    if (detail?.id == item.id && selectedVideo.ifEmpty { item.id } == requestedId) {
                                        sources = report.streams
                                        resetStreamView()
                                        streamFetchStatus = "${report.streams.size} streams from " +
                                            "${report.queriedAddons - report.failedAddons}/${report.queriedAddons} responding add-ons" +
                                            (if (report.failedAddons > 0) " · ${report.failedAddons} provider requests failed" else "")
                                    }
                                }
                            }
                            if (streamFetchStatus.isNotBlank()) Text(streamFetchStatus, color = stMuted,
                                fontSize = 12.sp)
                            if (sources.isEmpty()) Text("Find streams supplied by your installed add-ons.",
                                color = stMuted, fontSize = 12.sp)
                            val counts = remember(sources) { StremioStreamListPolicy.counts(sources) }
                            if (sources.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    items(listOf("All") + StremioStreamListPolicy.categories.filter {
                                        (counts[it] ?: 0) > 0
                                    }) { quality ->
                                        val count = if (quality == "All") sources.size else counts[quality] ?: 0
                                        PmPill("$quality ($count)", streamQualityFilter == quality) {
                                            streamQualityFilter = quality
                                            streamVisibleCount = StremioStreamListPolicy.PAGE_SIZE
                                        }
                                    }
                                }
                            }
                            val filteredStreams = remember(sources, streamQualityFilter) {
                                StremioStreamListPolicy.filter(sources, streamQualityFilter)
                            }
                            val visibleStreams = filteredStreams.take(streamVisibleCount)
                            visibleStreams.forEach { source ->
                                val own = activeSource?.let {
                                    it.hash == source.hash && it.fileIndex == source.fileIndex &&
                                        it.release == source.release && it.kind == source.kind && it.url == source.url
                                } ?: false
                                Column(Modifier.fillMaxWidth().background(Color(0xFF172B43), RoundedCornerShape(11.dp))
                                    .padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("${source.name}  ${source.quality}", color = Color.White, fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text(source.kind, color = stBlue, fontSize = 10.sp)
                                    }
                                    Text(source.addon + " · " + source.release, maxLines = 3,
                                        color = stMuted, fontSize = 11.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        StAction("▶ Play", primary = true,
                                            enabled = !sourceBusy && !torrentStopping && source.kind != "Unsupported") {
                                            playSource(source)
                                        }
                                        StAction("⬇ Download", enabled = !sourceBusy && !torrentStopping &&
                                            source.kind in setOf("Torrent", "Direct HTTP")) {
                                            chooseDownload(source)
                                        }
                                    }
                                    if (own) {
                                        if (torrentStage.isNotBlank())
                                            Text(torrentStage, color = stBlue, fontSize = 11.sp)
                                        if (downloadProgress.isNotBlank())
                                            Text(downloadProgress, color = Color(0xFF91E2BA), fontSize = 11.sp)
                                        if (sourceBusy) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp),
                                                    color = stBlue, strokeWidth = 2.dp)
                                                Text("${activeAction} in progress", color = stMuted, fontSize = 11.sp)
                                            }
                                            if (source.kind == "Torrent")
                                                Text("Preparing for ${StremioStartupPolicy.elapsedLabel(torrentElapsedMs)}",
                                                    color = stMuted, fontSize = 11.sp)
                                            if (torrentStats.isNotBlank())
                                                Text(torrentStats, color = stMuted, fontSize = 11.sp)
                                            StAction("Cancel ${activeAction.lowercase()}") {
                                                cancelPreparation("${activeAction} cancelled")
                                            }
                                        } else if (torrentStopping) {
                                            Text(if (source.kind == "Torrent") "Stopping native torrent session…"
                                                else "Finishing download cleanup…", color = stMuted,
                                                fontSize = 11.sp)
                                        }
                                    }
                                    if (source.kind == "Torrent" && !p2pEnabled)
                                        Text("Enable P2P above before Play or Download", color = Color(0xFFFFC578),
                                            fontSize = 11.sp)
                                    else if (source.kind == "Torrent" && !NativeBridge.isTorrentReady())
                                        Text("Native torrent engine unavailable in this APK", color = Color(0xFFFFC578),
                                            fontSize = 11.sp)
                                }
                            }
                            if (visibleStreams.size < filteredStreams.size) {
                                StAction("Show more streams (${filteredStreams.size - visibleStreams.size} remaining)",
                                    enabled = !sourceBusy && !torrentStopping) {
                                    streamVisibleCount = StremioStreamListPolicy.nextCount(streamVisibleCount, filteredStreams.size)
                                }
                            }
                        }
                    }
                    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { info(); streamPanel() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.weight(.55f)) { info() }
                        Box(Modifier.weight(.45f)) { streamPanel() }
                    }
                }
            } else when (section) {
                "Home" -> {
                    val featured = homeMovies.firstOrNull() ?: homeSeries.firstOrNull()
                    if (featured != null) StCinemaHero(featured, ::openItem)
                    else Column(Modifier.fillMaxWidth().background(Color(0xFF111F33), RoundedCornerShape(14.dp))
                        .padding(26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Discover something to watch", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("No featured titles yet. Open Discover or refresh your installed add-ons.", color = stMuted)
                        StAction("Discover titles") { navigateSection("Discover") }
                    }
                    if (recentWatches.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Continue Watching", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Saved on this device", color = Color(0xFF9EB9DA), fontSize = 11.sp)
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            items(recentWatches.take(15)) { entry -> StContinueCard(entry) {
                                requestedResumeVideo = entry.videoId
                                openItem(StremioMeta(entry.id, entry.type, entry.title,
                                    poster = entry.poster, year = entry.year, background = entry.background))
                            } }
                        }
                    }
                    StCinemaRail("Discover Movies", homeMovies, goTo = { category = "movie"; navigateSection("Discover") }, open = ::openItem)
                    StCinemaRail("Discover Series", homeSeries, goTo = { category = "series"; navigateSection("Discover") }, open = ::openItem)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        StAction("Search all add-ons") { navigateSection("Search") }
                        StAction("Your Stremio library") { navigateSection("Library") }
                    }
                }
                "Search" -> {
                    Text("Search your add-ons", fontSize = 23.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(search, { search = it }, placeholder = { Text("Search movies and series") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        StAction("Search", true, !busy && search.trim().length >= 2) {
                            task {
                                val requested = search.trim()
                                val result = client.search(requested)
                                if (search.trim() == requested && section == "Search") {
                                    searchResults = StremioBrowsePolicy.uniqueTitles(result)
                                    searchExecuted = true
                                }
                            }
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("All", "Movies", "Series")) { type ->
                            PmPill(type, searchType == type) { searchType = type }
                        }
                    }
                    val filtered = remember(searchResults, searchType) {
                        searchResults.filter { searchType == "All" ||
                            (searchType == "Movies" && it.type == "movie") ||
                            (searchType == "Series" && it.type == "series") }
                    }
                    if (filtered.isEmpty() && searchExecuted)
                        Text("No matching titles from the installed add-ons.", color = stMuted, fontSize = 12.sp)
                    else if (filtered.isNotEmpty()) {
                        Text("${filtered.size} titles", color = stMuted, fontSize = 12.sp)
                        StBrowseGrid(filtered, ::openItem)
                    }
                }
                "Discover" -> {
                    if (phoneUi) {
                        OutlinedTextField(search, { search = it },
                            placeholder = { Text("Search movies and series") },
                            leadingIcon = { Text("⌕", color = stBlue, fontSize = 24.sp) },
                            trailingIcon = {
                                Text("Go", color = stBlue, modifier = Modifier.clickable {
                                    if (search.trim().length >= 2) {
                                        val requested = search.trim()
                                        navigateSection("Search")
                                        task {
                                            val result = client.search(requested)
                                            if (search.trim() == requested && section == "Search") {
                                                searchResults = StremioBrowsePolicy.uniqueTitles(result)
                                                searchExecuted = true
                                            }
                                        }
                                    } else navigateSection("Search")
                                })
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                val requested = search.trim()
                                navigateSection("Search")
                                if (requested.length >= 2) task {
                                    val result = client.search(requested)
                                    if (search.trim() == requested && section == "Search") {
                                        searchResults = StremioBrowsePolicy.uniqueTitles(result)
                                        searchExecuted = true
                                    }
                                }
                            }),
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("Stremio catalogs", color = stMuted, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold)
                    } else Text("Discover movies and series", color = Color.White,
                        fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        items(if (phoneUi) listOf("all", "movie", "series") else listOf("movie", "series")) { type ->
                            PmPill(when (type) { "all" -> "All"; "movie" -> "Movies"; else -> "Series" }, category == type) {
                                category = type; selectedCat = null; browseResults = emptyList()
                                genre = ""; page = 0
                            }
                        }
                    }
                    val cats = client.catalogs.filter { category == "all" || it.type == category }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            PmPill("All catalogues", selectedCat == null) {
                                selectedCat = null; genre = ""; page = 0
                            }
                        }
                        items(cats, key = { "${it.addonId}:${it.type}:${it.id}" }) { cat ->
                            PmPill("${cat.addonName} · ${cat.name}", selectedCat == cat) {
                                selectedCat = cat; page = 0; genre = ""
                            }
                        }
                    }
                    selectedCat?.takeIf { category == "all" || it.type == category }?.let { cat ->
                        if (cat.genres.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(listOf("All") + cat.genres) { g ->
                                PmPill(g, (if (genre.isEmpty()) "All" else genre) == g) {
                                    val requestedGenre = if (g == "All") "" else g
                                    genre = requestedGenre; page = 0
                                }
                            }
                        }
                    }
                    if (browseResults.isEmpty())
                        Text(if (cats.isEmpty()) "No compatible catalogues available from your installed add-ons."
                            else "Fetching catalogue titles… The first load needs an internet connection.", color = stMuted)
                    else {
                        Text("${browseResults.size} titles · refreshes in background", color = stMuted, fontSize = 12.sp)
                        StBrowseGrid(browseResults, ::openItem)
                    }
                    selectedCat?.takeIf { category == "all" || it.type == category }?.let { cat ->
                        if (cat.paginated) StAction("Load more", enabled = !busy) {
                            task {
                                val nextPage = page + 1
                                val requestedGenre = genre
                                val more = client.catalog(cat, requestedGenre, nextPage * 100)
                                if (selectedCat == cat && genre == requestedGenre && section == "Discover") {
                                    page = nextPage
                                    browseResults = StremioBrowsePolicy.uniqueTitles(browseResults + more)
                                }
                            }
                        }
                    }
                }
                "Library" -> {
                    Text("Your Stremio library", fontSize = 23.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text("${library.size} account titles · ${watchedStore.pending().size} local watched edits pending",
                        color = stMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StAction("↻ Refresh library", enabled = !busy) { task {
                            val owner = client.email
                            val fresh = client.library()
                            if (client.email == owner) library = fresh
                        } }
                    }
                    watchedSyncControls()
                    val movies = remember(library) { library.filter { it.type == "movie" } }
                    val shows = remember(library) { library.filter { it.type == "series" } }
                    if (movies.isNotEmpty()) {
                        Text("Movies · ${movies.size}", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold)
                        StBrowseGrid(movies, ::openItem)
                    }
                    if (shows.isNotEmpty()) {
                        Text("TV Series · ${shows.size}", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold)
                        StBrowseGrid(shows, ::openItem)
                    }
                    if (library.isEmpty()) Text("Your library is empty or has not synced yet.", color = stMuted)
                }
                "Add-ons" -> {
                    StremioAddonManagerScreen(client, onRevision, onMessage)
                }
                "Downloads" -> StremioDownloadQueueScreen(
                    onChooseFolder = { downloadFolderPicker.launch(null) }, onMessage = onMessage)
                "Settings" -> {
                    Text("Stremio settings", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = p2pEnabled, onCheckedChange = ::consent)
                        Text("Allow peer-to-peer torrent streaming", color = Color.White,
                            modifier = Modifier.weight(1f))
                    }
                    Text("Torrent peers can see your IP. Streams use only hash-verified pieces and a private MPV Range endpoint.",
                        color = stMuted, fontSize = 12.sp)
                    Text(client.email, color = stMuted)
                    watchedSyncControls()
                    StAction("Refresh add-ons", enabled = !busy) { task { refreshConnectedAddons() } }
                    StAction("Sign out") { consent(false); client.signOut(); onRevision(); detail = null; searchResults = emptyList(); browseResults = emptyList() }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
