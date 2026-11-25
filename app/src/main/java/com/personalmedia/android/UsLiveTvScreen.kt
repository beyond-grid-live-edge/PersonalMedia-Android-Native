package com.personalmedia.android

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val usBg = Color(0xFF090E17)
private val usPanel = Color(0xFF111925)
private val usLight = Color(0xFFE9F2FF)
private val usMuted = Color(0xFF9AB0CA)
private val usAccent = Color(0xFF78BAFF)

/** US-LTV-3: channel-only browser, without programme or schedule controls.
 * The UK Freeview guide remains a separate, unchanged screen.
 * Channel and source addresses still come exclusively from Global Free TV. */
@Composable
internal fun UsLiveTvScreen(onFullscreenChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    val repo = remember { UsTvRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf(repo.cached()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("US channels are sourced exclusively from Global Free TV.") }
    var search by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var favouriteRevision by remember { mutableIntStateOf(0) }
    var selectedChannel by remember { mutableStateOf<UsChannel?>(null) }
    var availableSources by remember { mutableStateOf(emptyList<UsSource>()) }
    var selectedIndex by remember { mutableIntStateOf(1) }
    var allowHttpSource by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var activePlayback by remember { mutableStateOf<UsSource?>(null) }
    var autoTuneOnResolve by remember { mutableStateOf(false) }

    fun refreshCatalog() {
        scope.launch {
            loadingCatalog = true
            message = "Loading US channels from Global Free TV…"
            try {
                val fresh = withContext(Dispatchers.IO) { repo.refresh() }
                channels = fresh
                message = "${fresh.size} US channels found on the site's public pages. " +
                    "Some dynamically loaded channels might not be discoverable."
            } catch (e: Exception) {
                channels = repo.cached()
                message = e.message ?: "Channel refresh failed. Your saved channel list is unchanged."
            } finally { loadingCatalog = false }
        }
    }
    LaunchedEffect(Unit) { if (channels.isEmpty()) refreshCatalog() }

    // When a user taps a channel, resolve its own Global Free TV sources.
    // HTTPS sources auto-play; HTTP needs the user's explicit opt-in.
    LaunchedEffect(selectedChannel?.id) {
        val channel = selectedChannel ?: return@LaunchedEffect
        resolving = true
        availableSources = emptyList()
        selectedIndex = repo.preferredSource(channel.id)
        try {
            val sources = withContext(Dispatchers.IO) { repo.sources(channel) }
            if (selectedChannel?.id == channel.id) {
                availableSources = sources
                if (autoTuneOnResolve && sources.isNotEmpty()) {
                    val preferred = sources.firstOrNull { it.number == selectedIndex } ?: sources.first()
                    if (preferred.url.startsWith("https://", ignoreCase = true)) activePlayback = preferred
                }
                autoTuneOnResolve = false
                message = if (sources.isEmpty())
                    "${channel.name}: no direct sources exposed by this page. Open it on Global Free TV to use its browser player."
                else "${channel.name}: ${sources.size} available direct source(s)."
            }
        } catch (e: Exception) {
            if (selectedChannel?.id == channel.id) {
                autoTuneOnResolve = false
                message = "Unable to load channel sources: ${e.message ?: "connection failed"}"
            }
        } finally {
            if (selectedChannel?.id == channel.id) resolving = false
        }
    }

    // Preserve US-LTV-2 Media3 live playback and its source fallback.
    // Keep UK T405 Live TV and Stremio's MPV pipeline entirely separate.
    val playbackVisible = activePlayback != null
    DisposableEffect(playbackVisible) {
        onFullscreenChanged(playbackVisible)
        onDispose { if (playbackVisible) onFullscreenChanged(false) }
    }
    if (activePlayback != null && selectedChannel != null) {
        val channel = selectedChannel!!
        UsTvPlayerScreen(
            channel = channel,
            sources = availableSources,
            initialSource = activePlayback!!.number,
            allowHttpSource = allowHttpSource,
            onWorkingSource = { number -> repo.setPreferredSource(channel.id, number) },
            onClose = { activePlayback = null; selectedChannel = null }
        )
        return
    }
    BackHandler(enabled = selectedChannel != null) { selectedChannel = null }

    val filtered = remember(channels, search, favouritesOnly, favouriteRevision) {
        channels.filter { channel ->
            (!favouritesOnly || repo.isFavourite(channel.id)) &&
                (search.isBlank() || channel.name.contains(search, ignoreCase = true))
        }
    }
    Column(Modifier.fillMaxSize().background(usBg)) {
        Row(Modifier.fillMaxWidth().background(usPanel).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("US channels", color = usLight, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("🇺🇸", color = usLight)
        }
        if (selectedChannel != null) {
            val channel = selectedChannel!!
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { selectedChannel = null }) { Text("← All US channels") }
                Text(channel.name, style = MaterialTheme.typography.headlineSmall, color = usLight)
                Text("Global Free TV · United States", color = usMuted)
                if (resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (availableSources.isNotEmpty()) {
                    Text("Available channel sources", color = usLight, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableSources, key = { it.number }) { source ->
                            FilterChip(selected = selectedIndex == source.number,
                                onClick = {
                                    selectedIndex = source.number
                                    repo.setPreferredSource(channel.id, source.number)
                                }, label = { Text("Source ${source.number}") })
                        }
                    }
                    val chosen = availableSources.firstOrNull { it.number == selectedIndex }
                        ?: availableSources.firstOrNull()
                    if (chosen != null && chosen.url.startsWith("http://", ignoreCase = true)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowHttpSource, onCheckedChange = { allowHttpSource = it })
                            Text("Allow this unencrypted HTTP source", color = usMuted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = {
                        if (chosen != null && (chosen.url.startsWith("https://", ignoreCase = true) || allowHttpSource))
                            activePlayback = chosen
                    }, enabled = chosen != null &&
                        (chosen.url.startsWith("https://", ignoreCase = true) || allowHttpSource)) {
                        Text("Watch · Source ${chosen?.number ?: 1}")
                    }
                    Text("Change sources in the player if one is unavailable.", color = usMuted,
                        style = MaterialTheme.typography.bodySmall)
                } else if (!resolving) {
                    Text("This channel's player may load sources dynamically. No direct video link was exposed by its public page.",
                        color = usMuted)
                }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(channel.pageUrl)))
                }) { Text("Open on Global Free TV") }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(search, { search = it },
                        label = { Text("Search US channels") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    if (!phoneUi) {
                        TextButton(onClick = { favouritesOnly = !favouritesOnly }) {
                            Text(if (favouritesOnly) "★ Saved" else "☆ All")
                        }
                        TextButton(onClick = { refreshCatalog() }, enabled = !loadingCatalog) { Text("↻") }
                        TextButton(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UsTvPolicy.COUNTRY_URL)))
                        }) { Text("Site") }
                    }
                }
                if (phoneUi) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { favouritesOnly = !favouritesOnly }) {
                        Text(if (favouritesOnly) "★ Saved" else "☆ All")
                    }
                    TextButton(onClick = { refreshCatalog() }, enabled = !loadingCatalog) { Text("↻ Refresh") }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UsTvPolicy.COUNTRY_URL)))
                    }) { Text("Website") }
                }
                Text(message, color = usMuted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp))
                if (loadingCatalog) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${filtered.size} channels · Tap a channel to watch or choose a source",
                    color = usMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
                    items(filtered, key = { it.id }) { channel ->
                        Row(Modifier.fillMaxWidth()
                            .clickable { autoTuneOnResolve = true; selectedChannel = channel }
                            .background(usPanel, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = {
                                repo.toggleFavourite(channel.id)
                                favouriteRevision++
                            }) { Text(if (repo.isFavourite(channel.id)) "★" else "☆") }
                            Column(Modifier.weight(1f)) {
                                Text(channel.name, color = usLight, fontWeight = FontWeight.SemiBold,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("United States · Global Free TV", color = usMuted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Text("▶", color = usAccent)
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    if (filtered.isEmpty()) item {
                        Column(Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("No US channels currently listed", color = usLight)
                            Text("Refresh the Global Free TV US directory, or open the website if its channel catalogue is loaded dynamically.", color = usMuted)
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UsTvPolicy.COUNTRY_URL)))
                            }) { Text("Open Global Free TV · United States") }
                        }
                    }
                }
            }
        }
    }
}
