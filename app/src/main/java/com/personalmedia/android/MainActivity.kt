package com.personalmedia.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val bg = Color(0xFF07070B)
private val panel = Color(0xFF12131C)
private val accent = Color(0xFF7DB9FF)
private val muted = Color(0xFFA1A3B3)
private val tabs = listOf("Home", "Movies", "TV Shows", "Stremio", "Live TV", "Music", "Pictures", "Favourites", "Settings")

class MainActivity : ComponentActivity() {
    private var onFiles: ((List<Uri>) -> Unit)? = null
    private var onTree: ((Uri) -> Unit)? = null
    private var onWriteBackup: ((Uri) -> Unit)? = null
    private var onReadBackup: ((Uri) -> Unit)? = null
    private var onWriteDiagnostics: ((Uri) -> Unit)? = null
    private val saveBackupPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val callback = onWriteBackup; onWriteBackup = null
        if (uri != null) callback?.invoke(uri)
    }
    private val readBackupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callback = onReadBackup; onReadBackup = null
        if (uri != null) callback?.invoke(uri)
    }
    private val saveDiagnosticsPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val callback = onWriteDiagnostics; onWriteDiagnostics = null
        if (uri != null) callback?.invoke(uri)
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
        onFiles?.invoke(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onTree?.invoke(uri)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PersonalMediaApp(
                chooseFiles = { category, callback ->
                    onFiles = callback
                    pickFiles.launch(LibraryFolderPolicy.mimeTypes(category))
                },
                chooseFolder = { callback -> onTree = callback; pickFolder.launch(null) },
                saveBackup = { callback ->
                    onWriteBackup = callback
                    saveBackupPicker.launch("PersonalMedia-backup.json")
                },
                loadBackup = { callback ->
                    onReadBackup = callback
                    readBackupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
                saveDiagnostics = { callback ->
                    onWriteDiagnostics = callback
                    saveDiagnosticsPicker.launch("PersonalMedia-diagnostics.txt")
                })
        }
    }
    override fun onDestroy() {
        if (NativeBridge.loaded) runCatching {
            NativeBridge.tvDisconnect()
            if (!TorrentEngineLease.heldByDownload()) {
                NativeBridge.torrentStop()
                TorrentEngineLease.releasePlayback()
            }
        }
        super.onDestroy()
    }
}

@Composable
private fun PersonalMediaApp(chooseFiles: (String?, (List<Uri>) -> Unit) -> Unit,
    chooseFolder: ((Uri) -> Unit) -> Unit,
    saveBackup: ((Uri) -> Unit) -> Unit,
    loadBackup: ((Uri) -> Unit) -> Unit,
    saveDiagnostics: ((Uri) -> Unit) -> Unit) {
    val context = LocalContext.current
    // Smallest width remains stable across portrait/landscape rotations.
    // Foldables and tablets at 600dp+ retain the original large-screen shell.
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    var discoverTapSignal by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Keep the interface locale separate from media track and artwork metadata preferences.
    val uiLanguageStore = remember { PmUiLanguageStore(context.applicationContext) }
    var uiLanguage by remember { mutableStateOf(uiLanguageStore.read()) }
    val preferencesStore = remember { PlaybackPreferencesStore(context.applicationContext) }
    var playbackPreferences by remember { mutableStateOf(preferencesStore.read()) }
    val artworkSettingsStore = remember { ArtworkPreferencesStore(context.applicationContext) }
    var artworkPreferences by remember { mutableStateOf(artworkSettingsStore.read()) }
    var tmdbCredential by remember { mutableStateOf("") }
    var omdbCredential by remember { mutableStateOf("") }
    var artworkStatus by remember { mutableStateOf("Artwork has not been fetched yet.") }
    var artworkBusy by remember { mutableStateOf(false) }
    var artworkJob by remember { mutableStateOf<Job?>(null) }
    val checkedArtwork = remember { mutableSetOf<String>() }
    val artworkClient = remember { ArtworkLookupClient() }
    val local = remember { LocalLibrary(context.applicationContext) }
    val folderManager = remember { LibraryFolderManager(context.applicationContext, local) }
    val backupManager = remember { PersonalMediaBackup(context.applicationContext) }
    var backupCandidate by remember { mutableStateOf<PersonalMediaBackupCandidate?>(null) }
    var backupStatus by remember { mutableStateOf("No backup exported or imported in this session.") }
    var backupBusy by remember { mutableStateOf(false) }
    var backupUndoAvailable by remember { mutableStateOf(false) }
    var savedFolders by remember { mutableStateOf(folderManager.sources()) }
    var folderNotice by remember { mutableStateOf("") }
    var scanningTree by remember { mutableStateOf<String?>(null) }
    val client = remember { StremioClient(context.applicationContext) }
    // The remembered track identity follows the Stremio episode/title, not the
    // temporary HTTP redirect, magnet, or torrent loopback address.
    fun playbackMemoryKey(local: MediaEntry?, stremio: Pair<StremioMeta, String>?): String? =
        stremio?.let { (meta, videoId) ->
            TrackMemoryPolicy.stremioKey(client.email, meta.type, meta.id, videoId)
        } ?: local?.let { TrackMemoryPolicy.localKey(it.uri) }

    val stWatch = remember(client.email) { StremioWatchStore(context.applicationContext, client.email) }
    var tab by remember { mutableStateOf("Home") }
    val tabHistory = remember { PersonalMediaNavHistory("Home") }
    var libraryRoute by remember { mutableStateOf("") }
    fun navigateTab(destination: String) {
        libraryRoute = ""
        tabHistory.visit(tab, destination)
        tab = destination
    }
    var liveFullscreen by remember { mutableStateOf(false) }
    var liveRegion by remember { mutableStateOf("UK") }
    LaunchedEffect(tab) { if (tab != "Live TV") liveFullscreen = false }
    var localItems by remember { mutableStateOf(local.get()) }
    var message by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf("") }
    var playingSubtitles by remember { mutableStateOf(emptyList<StremioSubtitle>()) }
    var stremioDirect by remember { mutableStateOf<StremioSource?>(null) }
    var torrentPlayback by remember { mutableStateOf(false) }
    var mpvFallback by remember { mutableStateOf(false) }
    var playingLocal by remember { mutableStateOf<MediaEntry?>(null) }
    var playingStremio by remember { mutableStateOf<Pair<StremioMeta, String>?>(null) }
    var artwork by remember { mutableStateOf("") }
    var accountRevision by remember { mutableIntStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    LaunchedEffect(accountRevision) {
        backupUndoAvailable = withContext(Dispatchers.IO) {
            backupManager.canUndo(client.email)
        }
    }
    fun refreshAfterBackup() {
        localItems = local.get()
        savedFolders = folderManager.sources()
        playbackPreferences = preferencesStore.read()
        artworkPreferences = artworkSettingsStore.read()
    }
    fun exportPersonalMediaBackup() {
        if (backupBusy) return
        saveBackup { uri ->
            scope.launch {
                backupBusy = true
                backupStatus = "Writing private PersonalMedia backup…"
                try {
                    backupStatus = withContext(Dispatchers.IO) {
                        backupManager.export(uri, client.email)
                    }
                } catch (_: Exception) {
                    backupStatus = "Backup export failed. Check storage space and folder access."
                } finally { backupBusy = false }
            }
        }
    }
    fun inspectPersonalMediaBackup() {
        if (backupBusy) return
        loadBackup { uri ->
            scope.launch {
                backupBusy = true
                backupStatus = "Validating selected backup…"
                try {
                    backupCandidate = withContext(Dispatchers.IO) { backupManager.read(uri) }
                    backupStatus = "Backup validated. Review the summary before confirming restore."
                } catch (_: Exception) {
                    backupCandidate = null
                    backupStatus = "Backup is invalid, unsupported or unreadable. Nothing was changed."
                } finally { backupBusy = false }
            }
        }
    }
    fun confirmPersonalMediaRestore() {
        val selected = backupCandidate ?: return
        if (backupBusy) return
        scope.launch {
            backupBusy = true
            backupStatus = "Restoring library and preferences…"
            backupCandidate = null
            try {
                backupStatus = withContext(Dispatchers.IO) {
                    backupManager.restore(selected, client.email)
                }
                refreshAfterBackup()
                backupUndoAvailable = true
                folderNotice = "Backup restored. Reconnect folders where Android no longer has permission."
            } catch (_: Exception) {
                backupStatus = "Restore failed; previous state was recovered where possible. Check your library."
                refreshAfterBackup()
                backupUndoAvailable = withContext(Dispatchers.IO) { backupManager.canUndo(client.email) }
            } finally { backupBusy = false }
        }
    }
    fun undoPersonalMediaRestore() {
        if (backupBusy) return
        scope.launch {
            backupBusy = true
            try {
                backupStatus = withContext(Dispatchers.IO) {
                    backupManager.undo(client.email)
                }
                refreshAfterBackup()
                backupUndoAvailable = false
            } catch (_: Exception) {
                backupStatus = "Undo unavailable: backed-up settings changed or the snapshot could not be restored."
                backupUndoAvailable = false
            } finally { backupBusy = false }
        }
    }
    fun exportPersonalMediaDiagnostics() {
        if (backupBusy) return
        saveDiagnostics { uri ->
            scope.launch {
                backupBusy = true
                try {
                    withContext(Dispatchers.IO) {
                        val report = PersonalMediaDiagnostics.report(context, client.loggedIn)
                        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                            stream.write(report.toByteArray(Charsets.UTF_8))
                            stream.flush()
                        } ?: error("Cannot write diagnostics")
                    }
                    backupStatus = "Redacted support diagnostics saved. No URLs, tokens, paths or channel names included."
                } catch (_: Exception) {
                    backupStatus = "Diagnostics export failed; no account data was changed."
                } finally { backupBusy = false }
            }
        }
    }

    fun startArtworkFetch(providerOverride: String? = null, automatic: Boolean = false) {
        if (artworkJob?.isActive == true) {
            artworkStatus = "An artwork scan is already running."
            return
        }
        val options = artworkPreferences.copy(
            provider = providerOverride ?: artworkPreferences.provider,
            batchSize = if (automatic) 25 else artworkPreferences.batchSize)
        if (options.provider == "tmdb" && tmdbCredential.isBlank()) {
            artworkStatus = "Enter a TMDb credential first. Nothing was changed."
            return
        }
        if (options.provider == "omdb" && omdbCredential.isBlank()) {
            artworkStatus = "Enter an OMDb key first. Nothing was changed."
            return
        }
        artworkJob = scope.launch {
            artworkBusy = true
            try {
                val planned = withContext(Dispatchers.Default) {
                    ArtworkBatchPlanner.plan(local.get(), options)
                }
                val targets = planned.filterNot { it.key in checkedArtwork }.take(options.batchSize)
                if (targets.isEmpty()) {
                    artworkStatus = "No more unmatched titles in this session. Restart the app or use Retry missing to check again."
                    return@launch
                }
                var matched = 0
                var changed = 0
                for ((index, target) in targets.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    artworkStatus = "Checking ${index + 1}/${targets.size}: ${target.title}…"
                    checkedArtwork.add(target.key)
                    val result = artworkClient.lookup(target.title, target.year, target.kind,
                        options, tmdbCredential.trim(), omdbCredential.trim())
                    currentCoroutineContext().ensureActive()
                    if (result != null) {
                        val (latest, updates) = withContext(Dispatchers.IO) {
                            local.mergeArtwork(target.uris, result, options)
                        }
                        if (updates > 0) {
                            localItems = latest
                            changed += updates
                            matched++
                        }
                    }
                    // Respect public metadata endpoints; do not send bursts or use all titles at once.
                    if (index + 1 < targets.size) delay(750L)
                }
                artworkStatus = "Checked ${targets.size} unique titles; matched $matched, updated $changed file record(s). " +
                    "Unmatched titles and existing media were preserved."
            } catch (_: kotlinx.coroutines.CancellationException) {
                artworkStatus = "Artwork lookup cancelled; already matched entries were retained."
            } catch (_: Exception) {
                artworkStatus = "Artwork request failed; existing catalog and media were preserved."
            } finally {
                artworkBusy = false
            }
        }
    }

    fun scanFolder(id: String) {
        scope.launch {
            scanningTree = id
            folderNotice = "Scanning saved library folder…"
            try {
                val result = folderManager.scan(id)
                localItems = result.items
                folderNotice = result.message
                if (artworkPreferences.automatic && result.message.contains("matching files") &&
                    !result.message.contains("incomplete scan")) startArtworkFetch(automatic = true)
            } catch (_: Exception) {
                folderNotice = "Folder scan failed. Indexed files were preserved; try Reconnect."
            } finally {
                savedFolders = folderManager.sources()
                scanningTree = null
            }
        }
    }
    fun scanAllFolders(category: String? = null) {
        scope.launch {
            scanningTree = "ALL"
            val selected = savedFolders.filter { category == null || it.category == category }
            if (selected.isEmpty()) folderNotice = "No folders selected. Choose a library folder first."
            var last = ""
            try {
                selected.forEach { folder ->
                    folderNotice = "Scanning ${folder.category}: ${folder.label}…"
                    val result = folderManager.scan(folder.treeUri)
                    localItems = result.items
                    last = result.message
                }
                if (selected.isNotEmpty()) folderNotice = "Scanned ${selected.size} saved folder(s). $last"
                if (artworkPreferences.automatic && selected.isNotEmpty() && last.contains("matching files") &&
                    !last.contains("incomplete scan"))
                    startArtworkFetch(automatic = true)
            } catch (_: Exception) {
                folderNotice = "Folder scan failed. No device files were changed."
            } finally {
                savedFolders = folderManager.sources()
                scanningTree = null
            }
        }
    }
    fun selectFolder(category: String, reconnectFrom: String? = null) {
        chooseFolder { uri ->
            scope.launch {
                val report = withContext(Dispatchers.IO) { folderManager.add(category, uri, reconnectFrom) }
                savedFolders = folderManager.sources()
                folderNotice = report
                if (report.startsWith("Saved ") || report.startsWith("Reconnected ")) {
                    scanFolder(uri.toString())
                }
            }
        }
    }
    fun importCategory(category: String) {
        chooseFiles(category) { uris ->
            scope.launch {
                val (records, added) = withContext(Dispatchers.IO) {
                    local.addFilesToCategory(uris, category)
                }
                localItems = records
                folderNotice = "Imported $added new $category file(s) (selected ${uris.size})."
            }
        }
    }

    // Player screens handle their own Back gesture. Here, swipe-back returns
    // through app tabs instead of finishing MainActivity.
    BackHandler(enabled = playing.isEmpty() && artwork.isEmpty()) {
        when {
            searchOpen -> searchOpen = false
            liveFullscreen -> liveFullscreen = false
            else -> {
                val previous = tabHistory.previous(tab)
                if (previous != null) { tab = previous; searchOpen = false }
                else message = "Already on Home — use the tabs to navigate"
            }
        }
    }
    BackHandler(enabled = playing.isEmpty() && artwork.isNotEmpty()) { artwork = "" }

    LaunchedEffect(Unit) {
        try {
            if (client.restore()) {
                // A momentary add-on outage must not make a valid restored account vanish.
                accountRevision++
                runCatching { client.refreshAddons() }
                    .onSuccess { accountRevision++ }
                    .onFailure { message = "Stremio signed in; add-on refresh is temporarily unavailable" }
            }
        } catch (_: Exception) { message = "Saved Stremio session could not be restored" }
    }
    val playLocal: (MediaEntry) -> Unit = { item ->
        playingStremio = null; stremioDirect = null; torrentPlayback = false
        if (item.type == "picture") artwork = item.uri
        else { playingLocal = item; playingSubtitles = emptyList(); mpvFallback = false; playing = item.uri }
    }
    val playRemote: (String, List<StremioSubtitle>) -> Unit = { url, subs ->
        if (url.startsWith("http://") || url.startsWith("https://")) {
            playingLocal = null; stremioDirect = null; torrentPlayback = false
            playingSubtitles = subs; mpvFallback = false; playing = url
        } else message = "This provider did not supply a playable URL"
    }
    val playDirect: (StremioSource, StremioMeta, String) -> Unit = { source, item, videoId ->
        if (StremioPlaybackPolicy.safeTransport(source.url, source.requestHeaders)) {
            stWatch.mark(item, videoId)
            playingStremio = item to videoId
            playingLocal = null
            stremioDirect = source
            torrentPlayback = false
            playingSubtitles = source.subtitles
            mpvFallback = false
            playing = source.url
        } else message = "Unsupported or unsafe stream transport"
    }
    val playTorrent: (String, List<StremioSubtitle>, StremioMeta, String) -> Unit =
        { url, subtitles, selected, selectedId ->
            // Native libtorrent has authenticated the selected file and exposed
            // only its loopback Range URL. Native MPV uses the same controls as
            // direct streams; no fallback to Media3's unverified player.
            if (TorrentLoopbackPolicy.isNativeTorrentUrl(url)) {
                playingStremio = selected to selectedId
                playingLocal = null
                torrentPlayback = true
                stremioDirect = StremioSource("Verified torrent", "", url, "", -1,
                    emptyList(), "Torrent", "", "Native libtorrent", subtitles, emptyMap())
                playingSubtitles = subtitles
                mpvFallback = false
                playing = url
            } else message = "Native torrent service returned an invalid playback address"
        }
    fun clearPlayer() {
        if (stremioDirect == null && playing.contains("/live.ts") && NativeBridge.loaded) NativeBridge.tvStop()
        playing = ""; playingLocal = null; playingStremio = null; stremioDirect = null
        // The Stremio player captures the previous session's native cleanup callback.
        torrentPlayback = false
    }
    CompositionLocalProvider(LocalPmUiLanguage provides uiLanguage) {
    MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = bg,
        surface = panel, onBackground = Color.White, onSurface = Color.White)) {
        if (playing.isNotEmpty() && stremioDirect != null) {
            val current = stremioDirect!!
            val watch = playingStremio
            val startMs = remember(playing, watch) {
                watch?.let { (meta, videoId) -> stWatch.position(meta.id, meta.type, videoId) } ?: 0L
            }
            val cleanupTorrent = torrentPlayback
            StremioMpvPlayerScreen(uri = playing, requestHeaders = current.requestHeaders,
                externalSubtitles = current.subtitles, resumeMs = startMs,
                preferences = playbackPreferences,
                mediaKey = playbackMemoryKey(playingLocal, watch),
                torrentPlayback = torrentPlayback,
                afterStop = {
                    if (cleanupTorrent) {
                        if (NativeBridge.loaded) NativeBridge.torrentStop()
                        TorrentEngineLease.releasePlayback()
                        DownloadQueueService.kick(context.applicationContext)
                    }
                },
                onProgress = { ms ->
                    watch?.let { (meta, videoId) -> stWatch.progress(meta.id, meta.type, videoId, ms) }
                }, onClose = ::clearPlayer)
        } else if (playing.isNotEmpty() && !mpvFallback && !playing.startsWith("content://") && NativeBridge.loaded &&
            remember(playing) { NativeBridge.mpvAvailable() }) {
            MpvPlayerScreen(uri = playing, onFallback = { mpvFallback = true }, onClose = ::clearPlayer)
        } else if (playing.isNotEmpty()) {
            PlayerScreen(playing, playingSubtitles, playingLocal?.positionMs ?: 0L,
                preferences = playbackPreferences,
                mediaKey = playbackMemoryKey(playingLocal, playingStremio),
                onProgress = { ms ->
                    playingStremio?.let { (item, videoId) ->
                        stWatch.progress(item.id, item.type, videoId, ms)
                    }
                    playingLocal?.let { item ->
                        if (kotlin.math.abs(ms - item.positionMs) >= 4000L) {
                            val new = item.copy(positionMs = ms)
                            playingLocal = new
                            localItems = local.update(new)
                        }
                    }
                }, onClose = {
                    if (playing.contains("/live.ts") && NativeBridge.loaded) NativeBridge.tvStop()
                    playing = ""; playingLocal = null; playingStremio = null
                })
        } else if (artwork.isNotEmpty()) {
            Column(Modifier.fillMaxSize().background(bg)) {
                TextButton(onClick = { artwork = "" }) { Text("← Back") }
                AsyncImage(artwork, contentDescription = "Photo", modifier = Modifier.fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().background(if (phoneUi) phoneNavy else bg).safeDrawingPadding()) {
                if (!liveFullscreen) {
                    if (phoneUi) PhoneHeader(onSearch = { searchOpen = !searchOpen })
                    else HarmonyHeader(tab, onNavigate = { navigateTab(it); searchOpen = false },
                        onSearch = { searchOpen = !searchOpen }, onAdd = { navigateTab("Settings") })
                }
                if (searchOpen && !liveFullscreen) {
                    OutlinedTextField(searchText, { searchText = it },
                        placeholder = { Text("⌕ Search your PersonalMedia library") },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp))
                    if (searchText.isNotBlank()) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            items(localItems.filter { it.title.contains(searchText, ignoreCase = true) }.take(20)) { entry ->
                                PmRoundButton(entry.title) {
                                    searchOpen = false
                                    navigateTab(when (entry.type) {
                                        "movie" -> "Movies"; "tv" -> "TV Shows";
                                        "music" -> "Music"; else -> "Pictures"
                                    })
                                    playLocal(entry)
                                }
                            }
                        }
                    }
                }
                if (message.isNotEmpty() && !liveFullscreen) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF14243A))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), color = accent,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { message = "" }) { Text("Dismiss") }
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        "Stremio" -> StremioScreen(client, accountRevision,
                            onRevision = { accountRevision++ }, onMessage = { message = it },
                            onPlay = playRemote, onTorrentPlay = playTorrent,
                            onDirectPlay = playDirect,
                            preferredQuality = playbackPreferences.preferredQuality,
                            startOnDiscover = phoneUi,
                            discoverSignal = discoverTapSignal,
                            onWatch = { meta, videoId ->
                                stWatch.mark(meta, videoId); playingStremio = meta to videoId
                            })
                        "Live TV" -> Column(Modifier.fillMaxSize()) {
                            if (!liveFullscreen) Row(Modifier.fillMaxWidth().background(Color(0xFF0F1723))
                                .padding(horizontal = 14.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = liveRegion == "UK", onClick = { liveRegion = "UK" },
                                    label = { Text("UK Freeview · T405") })
                                FilterChip(selected = liveRegion == "US", onClick = { liveRegion = "US" },
                                    label = { Text("🇺🇸 US Internet TV") })
                            }
                            Box(Modifier.fillMaxSize()) {
                                if (liveRegion == "UK") LiveTvScreen(
                                    onPlay = { playRemote(it, emptyList()) },
                                    onFullscreenChanged = { liveFullscreen = it })
                                else UsLiveTvScreen(onFullscreenChanged = { liveFullscreen = it })
                            }
                        }
                        "Settings" -> SettingsScreen(
                            count = localItems.size, loggedIn = client.loggedIn,
                            uiLanguage = uiLanguage,
                            onUiLanguageChange = { selected ->
                                if (uiLanguageStore.save(selected)) uiLanguage = selected
                            },
                            backupStatus = backupStatus, backupBusy = backupBusy,
                            backupCandidate = backupCandidate, backupUndoAvailable = backupUndoAvailable,
                            onExportBackup = ::exportPersonalMediaBackup,
                            onInspectBackup = ::inspectPersonalMediaBackup,
                            onConfirmRestore = ::confirmPersonalMediaRestore,
                            onCancelRestore = { backupCandidate = null },
                            onUndoRestore = ::undoPersonalMediaRestore,
                            onExportDiagnostics = ::exportPersonalMediaDiagnostics,
                            savedFolders = savedFolders,
                            playbackPreferences = playbackPreferences,
                            artworkPreferences = artworkPreferences,
                            onArtworkPreferencesChange = { updated ->
                                if (artworkSettingsStore.save(updated)) artworkPreferences = updated
                                else artworkStatus = "Could not save artwork preferences"
                            },
                            tmdbCredential = tmdbCredential, onTmdbCredentialChange = { tmdbCredential = it },
                            omdbCredential = omdbCredential, onOmdbCredentialChange = { omdbCredential = it },
                            artworkStatus = artworkStatus, artworkBusy = artworkBusy,
                            onFetchArtwork = { selected -> startArtworkFetch(providerOverride = selected) },
                            onRetryArtwork = {
                                checkedArtwork.clear()
                                startArtworkFetch()
                            },
                            onCancelArtwork = { artworkJob?.cancel() },
                            onPlaybackPreferencesChange = { update ->
                                if (preferencesStore.save(update)) playbackPreferences = update
                                else message = "Could not save playback preferences"
                            },
                            accessibleTrees = savedFolders.filter { folderManager.hasReadPermission(it.treeUri) }
                                .map { it.treeUri }.toSet(),
                            folderCounts = mapOf("Movies" to localItems.count { it.type == "movie" },
                                "TV Shows" to localItems.count { it.type == "tv" },
                                "Music" to localItems.count { it.type == "music" },
                                "Pictures" to localItems.count { it.type == "picture" }),
                            scanningTree = scanningTree, folderNotice = folderNotice,
                            onChooseSource = ::selectFolder, onImportCategory = ::importCategory,
                            onScanSource = ::scanFolder, onScanAll = { scanAllFolders() },
                            onRemoveSource = { id ->
                                val (records, status) = folderManager.remove(id)
                                localItems = records
                                savedFolders = folderManager.sources()
                                folderNotice = status
                            },
                            onClear = { local.save(emptyList()); localItems = emptyList() },
                            duplicateRecords = LocalLibraryOrganizer.exactDuplicateCount(localItems),
                            missingRecords = folderManager.missingCandidates().size,
                            canUndoRepair = local.hasUndo(),
                            onRepairDuplicates = {
                                val (records, result) = local.repairExactDuplicates()
                                localItems = records; folderNotice = result; message = result
                            },
                            onCheckMissing = {
                                val count = folderManager.missingCandidates().size
                                val result = if (count == 0) "No missing entries from completed folder scans. " +
                                    "Scan a registered folder to check."
                                else "$count potentially missing catalog record(s). " +
                                    "Use Review missing to safely verify them before cleanup."
                                folderNotice = result; message = result
                            },
                            onCleanupMissing = {
                                scope.launch {
                                    folderNotice = "Verifying missing library files…"
                                    val result = folderManager.cleanupConfirmedMissing()
                                    localItems = result.items; folderNotice = result.message; message = result.message
                                }
                            },
                            onUndoRepair = {
                                val (records, result) = local.undoRepair()
                                localItems = records; folderNotice = result; message = result
                            })
                        else -> if (phoneUi && tab == "Home") PhoneHomeScreen(localItems,
                            onPlay = playLocal, onNavigate = { navigateTab(it); searchOpen = false })
                        else HarmonyMediaScreen(tab, localItems,
                            onPlay = playLocal,
                            onFavourite = { item -> localItems = local.update(item.copy(favourite = !item.favourite)) },
                            onWatched = { item -> localItems = local.update(item.copy(watched = !item.watched)) },
                            onRemove = { item -> localItems = local.delete(item.uri) },
                            onImport = {
                                val category = LibraryFolderPolicy.categoryForTab(tab)
                                if (category == null) navigateTab("Settings") else importCategory(category)
                            },
                            onFolder = {
                                val category = LibraryFolderPolicy.categoryForTab(tab)
                                if (category == null) navigateTab("Settings")
                                else if (savedFolders.any { it.category == category }) scanAllFolders(category)
                                else selectFolder(category)
                            },
                            requestedLibraryView = libraryRoute,
                            goTo = { destination ->
                                val split = destination.split('#', limit = 2)
                                navigateTab(split.first())
                                if (split.size == 2) libraryRoute = split[1]
                            })
                    }
                }
                if (phoneUi && !liveFullscreen) PhoneBottomNavigation(tab) {
                    if (it == "Stremio") discoverTapSignal++
                    navigateTab(it)
                    searchOpen = false
                }
            }
        }
    }
    }
}

@Composable
private fun HarmonyHeader(tab: String, onNavigate: (String) -> Unit,
    onSearch: () -> Unit, onAdd: () -> Unit) {
    val nav = listOf("Home", "Movies", "TV Shows", "Stremio", "Live TV", "Music", "Pictures", "Favourites", "Settings")
    BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp).background(Color(0xFF0B0B10))) {
        val narrow = maxWidth < 750.dp
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.pm_mark),
                contentDescription = "PersonalMedia", modifier = Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            if (!narrow) {
                Text("PersonalMedia", color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
            }
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                items(nav) { name -> PmPill(name, name == tab) { onNavigate(name) } }
            }
            Spacer(Modifier.width(7.dp))
            if (!narrow) {
                Text("● Native Library", color = Color(0xFF9CCBFF),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.background(Color(0xFF20212B), RoundedCornerShape(20.dp))
                        .padding(horizontal = 9.dp, vertical = 8.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text("＋", color = Color.White, modifier = Modifier.size(34.dp)
                .background(Color(0xFF242530), RoundedCornerShape(18.dp))
                .clickable(onClick = onAdd).wrapContentSize(), style = MaterialTheme.typography.titleMedium)
            Text("⌕", color = Color.White, modifier = Modifier.size(34.dp)
                .background(Color(0xFF242530), RoundedCornerShape(18.dp))
                .clickable(onClick = onSearch).wrapContentSize(), style = MaterialTheme.typography.titleLarge)
            if (!narrow) Text("P", color = Color(0xFF30333E), modifier = Modifier.size(34.dp)
                .background(Color(0xFFEFEFF1), RoundedCornerShape(18.dp))
                .clickable { onNavigate("Settings") }.wrapContentSize(), fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider(color = Color(0xFF1B1C25), thickness = 1.dp)
}

@Composable
private fun SettingsBlock(eyebrow: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    Column(Modifier.fillMaxWidth().background(if (phoneUi) phoneSurface else panel,
        RoundedCornerShape(17.dp)).padding(if (phoneUi) 16.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text(eyebrow, color = Color(0xFFB4C5EC), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.titleLarge,
            color = Color.White, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun SettingsRow(label: String, result: String) {
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
        Text(result, color = Color.White, style = MaterialTheme.typography.bodySmall,
            modifier = if (phoneUi) Modifier.weight(1f) else Modifier,
            textAlign = TextAlign.End)
    }
}

@Composable
private fun SettingsScreen(count: Int, loggedIn: Boolean,
    uiLanguage: String, onUiLanguageChange: (String) -> Unit,
    backupStatus: String, backupBusy: Boolean,
    backupCandidate: PersonalMediaBackupCandidate?, backupUndoAvailable: Boolean,
    onExportBackup: () -> Unit, onInspectBackup: () -> Unit,
    onConfirmRestore: () -> Unit, onCancelRestore: () -> Unit,
    onUndoRestore: () -> Unit, onExportDiagnostics: () -> Unit,
    savedFolders: List<SavedMediaFolder>,
    playbackPreferences: PlaybackPreferences,
    onPlaybackPreferencesChange: (PlaybackPreferences) -> Unit,
    artworkPreferences: ArtworkPreferences,
    onArtworkPreferencesChange: (ArtworkPreferences) -> Unit,
    tmdbCredential: String, onTmdbCredentialChange: (String) -> Unit,
    omdbCredential: String, onOmdbCredentialChange: (String) -> Unit,
    artworkStatus: String, artworkBusy: Boolean,
    onFetchArtwork: (String?) -> Unit, onRetryArtwork: () -> Unit, onCancelArtwork: () -> Unit,
    accessibleTrees: Set<String>,
    folderCounts: Map<String, Int>, scanningTree: String?, folderNotice: String,
    onChooseSource: (String, String?) -> Unit, onImportCategory: (String) -> Unit,
    onScanSource: (String) -> Unit, onScanAll: () -> Unit,
    onRemoveSource: (String) -> Unit, onClear: () -> Unit,
    duplicateRecords: Int, missingRecords: Int, canUndoRepair: Boolean,
    onRepairDuplicates: () -> Unit, onCheckMissing: () -> Unit,
    onCleanupMissing: () -> Unit, onUndoRepair: () -> Unit) {
    val settingsContext = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var catalogReport by remember { mutableStateOf("") }
    var confirmRepair by remember { mutableStateOf(false) }
    var confirmMissing by remember { mutableStateOf(false) }
    var artworkLanguageDraft by remember { mutableStateOf(artworkPreferences.language) }
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    Column(Modifier.fillMaxSize().background(if (phoneUi) phoneNavy else bg)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = if (phoneUi) 14.dp else 24.dp,
            vertical = if (phoneUi) 17.dp else 26.dp),
        verticalArrangement = Arrangement.spacedBy(17.dp)) {
        PmEyebrow("PERSONALMEDIA")
        Text("Settings", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
        SettingsBlock("NATIVE STATUS", "PersonalMedia media library") {
            SettingsRow("Runtime", "Android Jetpack Compose")
            SettingsRow("Native local files", "$count items")
            SettingsRow("Selected library folders", "${savedFolders.size}")
            SettingsRow("Stremio account", if (loggedIn) "Signed in" else "Not signed in")
            SettingsRow("Storage", "Android document providers")
            PmRoundButton("Check local catalog", true) { catalogReport = "Index contains $count items" }
            if (catalogReport.isNotEmpty()) Text(catalogReport, color = accent)
            Text("Folder scanning is permission-dependent. Optional metadata and artwork enrichment is managed separately below.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("LIBRARY INTEGRITY", "Catalog repair & safe reconciliation") {
            SettingsRow("Exact duplicate file records", "$duplicateRecords")
            SettingsRow("Possibly missing after full scans", "$missingRecords")
            Text("Movie editions and remakes are not duplicate files. Only an identical file URI " +
                "and media type is eligible for repair; the index can be restored with Undo.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PmRoundButton("Check missing files", onClick = onCheckMissing)
                if (duplicateRecords > 0) PmRoundButton("Repair $duplicateRecords exact duplicates") {
                    confirmRepair = true
                }
                if (missingRecords > 0) PmRoundButton("Review missing ($missingRecords)") {
                    confirmMissing = true
                }
                if (canUndoRepair) PmRoundButton("Undo last catalog repair", onClick = onUndoRepair)
            }
            Text("Disconnected or unreadable folders are never automatically cleaned. " +
                "Only a completed folder scan can mark a record as possibly missing. " +
                "A final permission check is required before removing its index entry; " +
                "the original file is never deleted.", color = muted,
                style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("MEDIA SOURCES", "Separate library folders") {
            LibraryFolderSettings(folders = savedFolders, counts = folderCounts,
                accessibleTrees = accessibleTrees, scanningTree = scanningTree,
                notice = folderNotice, onChoose = onChooseSource, onImport = onImportCategory,
                onScan = onScanSource, onRescanAll = onScanAll, onRemove = onRemoveSource)
        }
        SettingsBlock("PLAYBACK", "Playback and display") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Keep screen awake during video", Modifier.weight(1f), color = Color.White)
                Switch(checked = playbackPreferences.keepScreenAwake,
                    onCheckedChange = { onPlaybackPreferencesChange(playbackPreferences.copy(keepScreenAwake = it)) })
            }
            Text("Keeps the screen awake while the player is on screen. Android handles the normal timeout after closing playback.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("INTERFACE LANGUAGE", "PersonalMedia display language") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = uiLanguage == "en", onClick = { onUiLanguageChange("en") },
                    label = { Text("English") })
                FilterChip(selected = uiLanguage == "gd", onClick = { onUiLanguageChange("gd") },
                    label = { Text("Gàidhlig (Alba)") })
            }
            Text("Changes the PersonalMedia interface only. Artwork metadata and audio/subtitle language preferences stay independent.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("LANGUAGES", "Audio and subtitle preferences") {
            Text("Preferred audio language", color = muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Auto", "en" to "English", "fr" to "French", "de" to "German")
                    .forEach { (language, label) ->
                        FilterChip(selected = playbackPreferences.audioLanguage == language,
                            onClick = { onPlaybackPreferencesChange(playbackPreferences.copy(audioLanguage = language)) },
                            label = { Text(label) })
                    }
            }
            Text("Preferred subtitle language", color = muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Auto", "en" to "English", "fr" to "French", "de" to "German")
                    .forEach { (language, label) ->
                        FilterChip(selected = playbackPreferences.subtitleLanguage == language,
                            onClick = { onPlaybackPreferencesChange(playbackPreferences.copy(subtitleLanguage = language)) },
                            label = { Text(label) })
                    }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Show subtitles by default", Modifier.weight(1f), color = Color.White)
                Switch(checked = playbackPreferences.subtitlesEnabled,
                    onCheckedChange = { onPlaybackPreferencesChange(playbackPreferences.copy(subtitlesEnabled = it)) })
            }
            Text("Available embedded tracks are selected when their language matches. Players retain their manual Audio/CC controls.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("QUALITY", "Preferred streaming quality") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("auto" to "Original / Auto", "4K" to "4K", "1080p" to "1080p", "720p" to "720p")
                    .forEach { (quality, label) ->
                        FilterChip(selected = playbackPreferences.preferredQuality == quality,
                            onClick = { onPlaybackPreferencesChange(playbackPreferences.copy(preferredQuality = quality)) },
                            label = { Text(label) })
                    }
            }
            Text("Preselects this resolution when Stremio add-ons return it. All other stream qualities remain available; no video is transcoded.",
                color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("NATIVE ANDROID COMPATIBILITY", "Media capability diagnostics") {
            SettingsRow("Local playback", "Android Media3")
            SettingsRow("Native MPV", if (NativeBridge.loaded && NativeBridge.mpvAvailable()) "Available" else "Binary not bundled")
            SettingsRow("Verified-piece torrent", if (NativeBridge.isTorrentReady()) "Native engine available" else "Libtorrent dependency not bundled")
            SettingsRow("August T405", "Native UDP receiver · hardware required")
            Text("Decoder formats and video output depend on your Android device.", color = muted, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("BACKUP & RECOVERY", "Portable library backup") {
            Text("Export your library, watched/resume positions, saved folder registrations, " +
                "UK Freeview lineup, US favourites, playback preferences and remembered Audio/CC choices.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PmRoundButton("Create backup", onClick = onExportBackup)
                PmRoundButton("Restore backup", onClick = onInspectBackup)
                if (backupUndoAvailable) PmRoundButton("Undo restore", onClick = onUndoRestore)
            }
            Text("Portable backups are UNENCRYPTED JSON and contain media titles and storage document IDs. " +
                "Store them privately. No Stremio login tokens, passwords, signed stream URLs, " +
                "torrent trackers, download credentials or downloaded video files are included.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("Restoring merges library records and never deletes media. " +
                "Folder permissions belong to Android and must be granted again when needed. " +
                "Stremio Continue Watching is restored only for the same signed-in account; " +
                "two-way sync pending edits are never imported.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            if (backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(backupStatus, color = accent, style = MaterialTheme.typography.bodySmall)
        }
        SettingsBlock("SUPPORT", "Redacted diagnostics") {
            Text("Save a count-only capability report for troubleshooting. " +
                "No media names, folder paths, private IPs, stream URLs or account credentials are included.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            PmRoundButton("Export support diagnostics", onClick = onExportDiagnostics)
        }
        SettingsBlock("STABILITY & BACKUP", "Protect this installation") {
            SettingsRow("App database", "Private local preferences")
            Text("Clearing this index only removes PersonalMedia's saved entries; it never deletes your original media files.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            PmRoundButton("Clear local library index", onClick = { confirm = true })
        }
        SettingsBlock("METADATA & ARTWORK", "Artwork providers") {
            Text("Fetch missing posters and descriptions for your local Movies and TV Shows. " +
                "Title/year matching protects remakes and editions; original files and watch history stay untouched.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("Preferred provider", color = muted, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("auto" to "Auto", "tmdb" to "TMDB", "omdb" to "OMDb", "free" to "Free")
                    .forEach { (value, label) ->
                        FilterChip(selected = artworkPreferences.provider == value,
                            onClick = { onArtworkPreferencesChange(artworkPreferences.copy(provider = value)) },
                            label = { Text(label) })
                    }
            }
            Text("Auto: TMDB → OMDb when credentials are entered, then Wikipedia (movies) / TVmaze (series). Free never uses keyed providers.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = tmdbCredential, onValueChange = onTmdbCredentialChange,
                label = { Text("TMDB v3 key or read token · session only") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = omdbCredential, onValueChange = onOmdbCredentialChange,
                label = { Text("OMDb API key · session only") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("Credentials remain in app memory, are never added to the library or saved in settings, and are cleared when the app process ends.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("Metadata language", color = muted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(value = artworkLanguageDraft,
                onValueChange = { value ->
                    if (value.length <= 5) {
                        artworkLanguageDraft = value
                        if (value.matches(Regex("[a-z]{2}(?:-[A-Z]{2})?")))
                            onArtworkPreferencesChange(artworkPreferences.copy(language = value))
                    }
                }, label = { Text("Language (e.g. en-US)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Posters", Modifier.weight(1f), color = Color.White)
                Switch(artworkPreferences.fetchPosters,
                    onCheckedChange = { onArtworkPreferencesChange(artworkPreferences.copy(fetchPosters = it)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Backdrops where available", Modifier.weight(1f), color = Color.White)
                Switch(artworkPreferences.fetchBackdrops,
                    onCheckedChange = { onArtworkPreferencesChange(artworkPreferences.copy(fetchBackdrops = it)) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-fetch after a completed folder scan (up to 25 titles)", Modifier.weight(1f), color = Color.White)
                Switch(artworkPreferences.automatic,
                    onCheckedChange = { onArtworkPreferencesChange(artworkPreferences.copy(automatic = it)) })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                FilterChip(selected = artworkPreferences.batchSize == 25,
                    onClick = { onArtworkPreferencesChange(artworkPreferences.copy(batchSize = 25)) },
                    label = { Text("25-title batch") })
                FilterChip(selected = artworkPreferences.batchSize != 25,
                    onClick = { onArtworkPreferencesChange(artworkPreferences.copy(batchSize = 2000)) },
                    label = { Text("Entire library · up to 2,000 titles") })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PmRoundButton("Fetch artwork", true) { onFetchArtwork(null) }
                PmRoundButton("TMDB") { onFetchArtwork("tmdb") }
                PmRoundButton("OMDb") { onFetchArtwork("omdb") }
                PmRoundButton("Free") { onFetchArtwork("free") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (artworkBusy) PmRoundButton("Cancel scan") { onCancelArtwork() }
                else PmRoundButton("Retry missing") { onRetryArtwork() }
                PmRoundButton("Clear TMDB key") { onTmdbCredentialChange("") }
                PmRoundButton("Clear OMDb key") { onOmdbCredentialChange("") }
            }
            Text(artworkStatus, color = accent, style = MaterialTheme.typography.bodySmall)
            Text("Existing artwork is never overwritten. Wikipedia and TVmaze require no API key. " +
                "OMDb does not supply backdrops. Internet required.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("Credits: The Movie Database (TMDB) · www.themoviedb.org; TVmaze · www.tvmaze.com " +
                "(public data CC BY-SA); Wikipedia / Wikimedia contributors. Image and text reuse licences may vary.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.",
                color = muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PmRoundButton("TMDB credits") { settingsContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org"))) }
                PmRoundButton("TVmaze credits") { settingsContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tvmaze.com"))) }
                PmRoundButton("Wikimedia credits") { settingsContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.wikimedia.org"))) }
            }
        }
        if (backupCandidate != null) AlertDialog(onDismissRequest = onCancelRestore,
            title = { Text("Restore PersonalMedia backup?") },
            text = { Text(backupCandidate.preview + "\n\n" +
                "No original media or downloads will be deleted. Saved folders may require reconnecting. " +
                "Stremio account credentials and pending watched-sync changes will not be restored.") },
            confirmButton = { TextButton(onClick = onConfirmRestore) { Text("Merge backup") } },
            dismissButton = { TextButton(onClick = onCancelRestore) { Text("Cancel") } })
        if (confirmRepair) AlertDialog(onDismissRequest = { confirmRepair = false },
            title = { Text("Repair exact duplicate records?") },
            text = { Text("Only repeated entries with identical file URI AND media type will be merged. " +
                "Different movie editions stay separate. An undo catalog snapshot is saved.") },
            confirmButton = { TextButton(onClick = {
                onRepairDuplicates(); confirmRepair = false
            }) { Text("Repair records") } },
            dismissButton = { TextButton(onClick = { confirmRepair = false }) { Text("Cancel") } })
        if (confirmMissing) AlertDialog(onDismissRequest = { confirmMissing = false },
            title = { Text("Remove confirmed missing index entries?") },
            text = { Text("PersonalMedia will recheck saved folder permissions and file availability " +
                "before removing only missing catalog records. No media files are modified. " +
                "A snapshot allows undo until a later library change.") },
            confirmButton = { TextButton(onClick = {
                onCleanupMissing(); confirmMissing = false
            }) { Text("Check and clean index") } },
            dismissButton = { TextButton(onClick = { confirmMissing = false }) { Text("Cancel") } })
        if (confirm) AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Clear local index?") }, text = { Text("Your original media files remain untouched.") },
            confirmButton = { TextButton(onClick = { onClear(); confirm = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    }
}
