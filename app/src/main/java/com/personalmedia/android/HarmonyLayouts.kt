package com.personalmedia.android

import androidx.compose.foundation.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Geometry/colour matching Index.ets: Header, HomeSpotlight, Shelf, Tile, LibraryPage, DetailsView. */
internal val pmBg = Color(0xFF07070B)
internal val pmPanel = Color(0xFF12131C)
internal val pmAccent = Color(0xFF7DB9FF)
internal val pmMuted = Color(0xFFA1A3B3)
private val tileGradient = Brush.linearGradient(listOf(Color(0xFF243D8F), Color(0xFF5B2589), Color(0xFF15141E)))
private val posterShade = Brush.verticalGradient(listOf(Color.Transparent, Color(0xB9030407)))

@Composable
internal fun PmPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(22.dp))
        .background(if (selected) Color(0xFF353541) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 11.dp)) {
        Text(text, color = if (selected) Color.White else Color(0xFFE1E1E9),
            fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
internal fun PmRoundButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(24.dp))
        .background(if (primary) Color(0xFFF3F0F5) else Color(0xFF4A4B62))
        .clickable(onClick = onClick).height(39.dp).padding(horizontal = 17.dp),
        contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color(0xFF161823) else Color.White,
            fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun PmEyebrow(text: String) {
    Text(text, color = Color(0xFF9FC6F8), fontSize = 11.sp, letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Bold)
}

@Composable
internal fun PmPosterTile(item: MediaEntry, landscape: Boolean, onClick: () -> Unit) {
    val w = if (landscape) 225.dp else 158.dp
    val h = if (landscape) 133.dp else 229.dp
    Box(Modifier.width(w).height(h).clip(RoundedCornerShape(12.dp))
        .background(tileGradient).clickable(onClick = onClick)) {
        val artUri = (if (landscape) item.backdrop.ifBlank { item.art } else item.art.ifBlank { item.backdrop })
            .ifEmpty { if (item.type == "picture") item.uri else "" }
        if (artUri.isNotEmpty()) {
            AsyncImage(model = artUri, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(posterShade))
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Bottom) {
            Text(when (item.type) { "tv" -> "TV SHOW"; "picture" -> "PICTURES"; else -> item.type.uppercase() },
                color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0x44FFFFFF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 3.dp))
            Spacer(Modifier.height(5.dp))
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = if (landscape) 16.sp else 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(item.folder.ifEmpty { "Local file" }, color = Color(0xFFCFCDD5),
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PmShelf(heading: String, entries: List<MediaEntry>, landscape: Boolean = false,
    onItem: (MediaEntry) -> Unit) {
    if (entries.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(heading, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(entries, key = { it.uri }) { PmPosterTile(it, landscape) { onItem(it) } }
        }
    }
}

@Composable
private fun PmWatchTile(item: MediaEntry, onClick: () -> Unit) {
    Column(Modifier.width(252.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF27385E), Color(0xFF11111B))))) {
            if (item.backdrop.isNotEmpty() || item.art.isNotEmpty())
                AsyncImage(item.backdrop.ifBlank { item.art }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(posterShade))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text("CONTINUE WATCHING", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFBFD8FD))
                Text(item.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                Text("▶ Resume · ${item.positionMs / 60000} min", fontSize = 11.sp, color = Color(0xFFE5E7EE))
            }
        }
        LinearProgressIndicator(progress = { .13f }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Color(0xFF8DBEFF), trackColor = Color(0xFF3E4155))
    }
}

@Composable
private fun PmHomeHero(featured: List<MediaEntry>, onItem: (MediaEntry) -> Unit,
    onSettings: () -> Unit, onMovies: () -> Unit) {
    var index by remember(featured) { mutableIntStateOf(0) }
    val current = featured.getOrNull(index.coerceIn(0, (featured.size - 1).coerceAtLeast(0)))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 28.dp)) {
        val compact = maxWidth < 650.dp
        Box(Modifier.fillMaxWidth().height(if (compact) 460.dp else 490.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF102949), Color(0xFF141026), pmBg)))) {
            if (current != null && (current.backdrop.isNotEmpty() || current.art.isNotEmpty())) {
                AsyncImage(current.backdrop.ifBlank { current.art }, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF07070C), Color(0xE507070C), Color(0x7707070C)))))
            if (!compact && current != null && current.art.isNotEmpty()) {
                AsyncImage(current.art, null, modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(end = 34.dp).width(208.dp).height(303.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop)
            }
            if (!compact && current == null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.pm_logo_full),
                    contentDescription = "PersonalMedia logo", modifier = Modifier.align(Alignment.CenterEnd)
                        .padding(end = 34.dp).width(226.dp).height(250.dp),
                    contentScale = ContentScale.Fit)
            }
            Column(Modifier.fillMaxHeight().fillMaxWidth(if (compact) 1f else .70f)
                .padding(horizontal = if (compact) 18.dp else 36.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Color(0xFF243C64), shape = RoundedCornerShape(11.dp), modifier = Modifier.size(43.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("PM", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black) }
                    }
                    Column {
                        Text("PERSONALMEDIA", color = Color(0xFFA7CDFD), fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                        Text("YOUR CINEMATIC LIBRARY", color = Color(0xFFDCE6FA), fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (current == null) {
                    Text("Your library,\nbeautifully yours.", color = Color.White,
                        fontSize = if (compact) 36.sp else 52.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 40.sp else 56.sp)
                    Spacer(Modifier.height(13.dp))
                    Text("Choose a movie or TV folder in Settings to start your cinematic library.",
                        color = Color(0xFFE1E4EE), fontSize = 14.sp)
                    Spacer(Modifier.height(15.dp))
                    PmRoundButton("＋ Add your media", primary = true, onClick = onSettings)
                } else {
                    PmEyebrow(if (current.positionMs > 0) "PICK UP WHERE YOU LEFT OFF" else "FEATURED FROM YOUR LIBRARY")
                    Spacer(Modifier.height(12.dp))
                    Text(current.title, fontSize = if (compact) 36.sp else 53.sp,
                        lineHeight = if (compact) 39.sp else 56.sp,
                        color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(9.dp))
                    Text(current.type.uppercase() + "  ·  YOUR COLLECTION", color = Color(0xFFDCE3F1), fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Your media, beautifully yours. Open details or start playing.",
                        color = Color(0xFFE2E1EC), fontSize = 14.sp, maxLines = 3)
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        PmRoundButton(if (current.positionMs > 0) "▶ Resume" else "▶ Play", true) { onItem(current) }
                        PmRoundButton("ⓘ More Info") { onItem(current) }
                        if (!compact) PmRoundButton("Browse Movies", onClick = onMovies)
                    }
                    if (featured.size > 1) {
                        Spacer(Modifier.height(20.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            PmRoundButton("‹") { index = (index + featured.size - 1) % featured.size }
                            Text("${index + 1} / ${featured.size}", color = Color.White, fontSize = 11.sp)
                            PmRoundButton("›") { index = (index + 1) % featured.size }
                        }
                    }
                }
            }
        }
    }
}

private fun seriesTitle(entry: MediaEntry): String = LocalLibraryOrganizer.tvEpisode(entry).show
private fun seriesSeason(entry: MediaEntry): Int = LocalLibraryOrganizer.tvEpisode(entry).season

@Composable
private fun PmMovieCollectionTile(group: MovieCollectionGroup, open: () -> Unit) {
    Box(Modifier.width(258.dp).height(156.dp).clip(RoundedCornerShape(14.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF1B2340), Color(0xFF331C37), Color(0xFF090910))))
        .clickable(onClick = open)) {
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            group.movies.take(3).forEach { movie ->
                val pic = movie.editions.firstOrNull { it.art.isNotBlank() }?.art.orEmpty()
                Box(Modifier.width(62.dp).height(119.dp).clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF283451))) {
                    if (pic.isNotBlank()) AsyncImage(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(
            Color(0xE90B0B18), Color(0x8C0B0B18), Color.Transparent))))
        Column(Modifier.align(Alignment.BottomStart).padding(13.dp).fillMaxWidth(.92f)) {
            Text(group.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${group.movies.size} films", fontSize = 11.sp, color = Color(0xFFE0E2F1))
        }
    }
}

@Composable
private fun PmTvCollectionTile(group: TvCollectionGroup, open: () -> Unit) {
    Box(Modifier.width(245.dp).height(148.dp).clip(RoundedCornerShape(13.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xFF152540), Color(0xFF452744), pmBg)))
        .clickable(onClick = open)) {
        Column(Modifier.align(Alignment.BottomStart).padding(13.dp)) {
            PmEyebrow("TV COLLECTION")
            Spacer(Modifier.height(13.dp))
            Text(group.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2)
            Text("${group.shows.size} series", fontSize = 11.sp, color = pmMuted)
        }
    }
}

@Composable
private fun PmEditionChooser(movie: MovieEditionGroup, onPlay: (MediaEntry) -> Unit) {
    if (movie.editions.size <= 1) return
    Spacer(Modifier.height(19.dp))
    Text("Available editions · ${movie.editions.size}", color = Color.White,
        fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Text("Each edition is a separate original file; changing editions never deletes media.",
        fontSize = 12.sp, color = pmMuted)
    movie.editions.forEachIndexed { i, entry ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF192330)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = Color.White, fontSize = 13.sp, maxLines = 2)
                Text(entry.folder.ifBlank { "File ${i + 1}" }, color = pmMuted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PmRoundButton(if (entry.positionMs > 0L) "▶ Resume" else "▶ Play") { onPlay(entry) }
        }
    }
}

@Composable
private fun PmSeriesTile(name: String, episodes: List<MediaEntry>, onClick: () -> Unit) {
    Box(Modifier.width(176.dp).height(250.dp).clip(RoundedCornerShape(13.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF17213D), Color(0xFF523073), Color(0xFF141420))))
        .clickable(onClick = onClick)) {
        if (episodes.firstOrNull()?.art?.isNotEmpty() == true)
            AsyncImage(episodes.first().art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(posterShade))
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Bottom) {
            Text("TV SHOW", fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Color(0xFFF2F3FA),
                modifier = Modifier.background(Color(0x7733333D), RoundedCornerShape(5.dp)).padding(4.dp))
            Spacer(Modifier.height(10.dp))
            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2)
            Text("${episodes.map(::seriesSeason).distinct().size} seasons  •  ${episodes.size} episodes",
                color = Color(0xFFE0E1ED), fontSize = 10.sp)
        }
    }
}

@Composable
private fun PmSeriesDetails(name: String, episodes: List<MediaEntry>, onBack: () -> Unit,
    onOpen: (MediaEntry) -> Unit) {
    var season by remember(name) { mutableIntStateOf(episodes.minOfOrNull(::seriesSeason) ?: 1) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back to TV Shows") }
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF17213D), Color(0xFF523073), pmBg)))) {
            val backdrop = episodes.firstOrNull { it.backdrop.isNotBlank() }?.backdrop
                ?: episodes.firstOrNull { it.art.isNotBlank() }?.art.orEmpty()
            if (backdrop.isNotBlank()) AsyncImage(backdrop, null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(posterShade))
            Column(Modifier.align(Alignment.BottomStart).padding(25.dp)) {
                PmEyebrow("YOUR LIBRARY / TV SHOW")
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 36.sp)
                Text("${episodes.map(::seriesSeason).distinct().size} seasons  ·  ${episodes.size} episodes",
                    color = Color(0xFFDCE3F1), fontSize = 13.sp)
            }
        }
        episodes.firstOrNull { it.plot.isNotBlank() }?.let { enriched ->
            Text(enriched.plot, color = Color(0xFFE0E2EB), fontSize = 13.sp, lineHeight = 20.sp)
            if (enriched.genres.isNotBlank()) Text(enriched.genres, color = pmAccent, fontSize = 12.sp)
            Text("Metadata: ${enriched.metadataSource}", color = pmMuted, fontSize = 11.sp)
        }
        Text("Seasons", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(episodes.map(::seriesSeason).distinct().sorted()) { number ->
                PmPill("Season $number", season == number) { season = number }
            }
        }
        Text("Episodes", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        episodes.filter { seriesSeason(it) == season }.forEach { episode ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF192330)).clickable { onOpen(episode) }.padding(13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.width(110.dp).height(67.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF27385B), Color(0xFF4B325B)))),
                    contentAlignment = Alignment.Center) {
                    Text(if (episode.watched) "✓" else "▶", color = Color.White, fontSize = 16.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text(episode.title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(if (episode.positionMs > 0) "Resume at ${episode.positionMs / 60000} min" else "Play episode",
                        color = pmMuted, fontSize = 11.sp)
                }
                Text("›", color = pmAccent, fontSize = 22.sp)
            }
        }
    }
}

/** Main user library page and detail overlay; uses the same category taxonomy as ArkUI. */
@Composable
internal fun HarmonyMediaScreen(tab: String, library: List<MediaEntry>, onPlay: (MediaEntry) -> Unit,
    onFavourite: (MediaEntry) -> Unit, onWatched: (MediaEntry) -> Unit,
    onRemove: (MediaEntry) -> Unit, onImport: () -> Unit, onFolder: () -> Unit,
    requestedLibraryView: String = "", goTo: (String) -> Unit) {
    val phoneUi = LocalConfiguration.current.smallestScreenWidthDp < 600
    var detail by remember(tab) { mutableStateOf<MediaEntry?>(null) }
    var series by remember(tab) { mutableStateOf<String?>(null) }
    var movieCollectionOpen by remember(tab) { mutableStateOf<String?>(null) }
    var tvCollectionOpen by remember(tab) { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, requestedLibraryView) {
        if (tab == "Movies" && requestedLibraryView.startsWith("movie:")) {
            movieCollectionOpen = requestedLibraryView.removePrefix("movie:")
        }
        if (tab == "TV Shows" && requestedLibraryView.startsWith("tvcollection:")) {
            tvCollectionOpen = requestedLibraryView.removePrefix("tvcollection:")
        }
        if (tab == "TV Shows" && requestedLibraryView.startsWith("series:")) {
            series = requestedLibraryView.removePrefix("series:")
        }
    }
    var genre by remember(tab) { mutableStateOf("All") }
    var watch by remember(tab) { mutableStateOf("All") }
    var order by remember(tab) { mutableStateOf("Title") }
    var search by remember(tab) { mutableStateOf("") }
    val collection = when (tab) {
        "Movies" -> library.filter { it.type == "movie" }
        "TV Shows" -> library.filter { it.type == "tv" }
        "Music" -> library.filter { it.type == "music" }
        "Pictures" -> library.filter { it.type == "picture" }
        "Favourites" -> library.filter { it.favourite }
        else -> library
    }
    val shown = collection.filter { item ->
        item.title.contains(search, ignoreCase = true) &&
            (watch == "All" || if (watch == "Watched") item.watched else !item.watched)
    }.let { if (order == "Title") it.sortedBy { media -> media.title.lowercase() } else it }
    val allMovies = remember(library) { LocalLibraryOrganizer.movieEditions(library) }
    val allMovieCollections = remember(library) { LocalLibraryOrganizer.movieCollections(library) }
    val allShows = remember(library) { LocalLibraryOrganizer.tvShows(library) }
    val allTvCollections = remember(library) { LocalLibraryOrganizer.tvCollections(library) }
    val activeMovieKeys = remember(shown) { LocalLibraryOrganizer.movieEditions(shown).map { it.key }.toSet() }
    val activeTvKeys = remember(shown) { LocalLibraryOrganizer.tvShows(shown).map { it.key }.toSet() }
    var visibleMovieCount by remember(tab, search, watch, order) { mutableIntStateOf(72) }
    // Keep large music/photo libraries responsive on phones: reveal more only when requested.
    var visiblePhoneCount by remember(tab, search, watch, order) { mutableIntStateOf(60) }
    BackHandler(enabled = detail != null || series != null || movieCollectionOpen != null || tvCollectionOpen != null) {
        when {
            detail != null -> detail = null
            series != null -> series = null
            movieCollectionOpen != null -> movieCollectionOpen = null
            tvCollectionOpen != null -> tvCollectionOpen = null
        }
    }
    Column(Modifier.fillMaxSize().background(if (phoneUi) phoneNavy else pmBg)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = if (phoneUi || tab == "Home") 16.dp else 28.dp)
        .padding(bottom = 40.dp)) {
        if (movieCollectionOpen != null && detail == null) {
            val group = allMovieCollections.firstOrNull { it.key == movieCollectionOpen }
            TextButton(onClick = { movieCollectionOpen = null }) { Text("‹ Back to Movies") }
            if (group != null) {
                PmEyebrow("FRANCHISES & COLLECTIONS")
                Text(group.title, fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${group.movies.size} films · your original files stay in place", color = pmMuted, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                group.movies.forEach { movie ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp))
                        .clickable { detail = movie.editions.first() }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(movie.title + if (movie.year.isNotEmpty()) " (${movie.year})" else "",
                            Modifier.weight(1f), color = Color.White)
                        Text("${movie.editions.size} edition(s) ›", color = pmAccent, fontSize = 12.sp)
                    }
                }
            }
        } else if (tvCollectionOpen != null && series == null && detail == null) {
            val group = allTvCollections.firstOrNull { it.key == tvCollectionOpen }
            TextButton(onClick = { tvCollectionOpen = null }) { Text("‹ Back to TV Shows") }
            if (group != null) {
                PmEyebrow("YOUR LIBRARY / TV COLLECTION")
                Text(group.title, fontSize = 32.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Text("${group.shows.size} distinct series", color = pmMuted)
                Spacer(Modifier.height(16.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    items(group.shows) { show ->
                        PmSeriesTile(show.title, show.episodes) { series = show.key }
                    }
                }
            }
        } else if (series != null && detail == null) {
            val selectedShow = allShows.firstOrNull { it.key == series }
            if (selectedShow != null) PmSeriesDetails(selectedShow.title, selectedShow.episodes,
                onBack = { series = null }, onOpen = { detail = it })
            else TextButton(onClick = { series = null }) { Text("‹ Back to TV Shows") }
        } else if (detail != null) {
            val item = library.firstOrNull { it.uri == detail!!.uri } ?: detail!!
            val movieVersions = if (item.type == "movie") allMovies.firstOrNull {
                it.key == LocalLibraryOrganizer.movieIdentity(item).first } else null
            TextButton(onClick = { detail = null }) { Text("‹  Back to $tab") }
            Box(Modifier.fillMaxWidth().height(if (phoneUi) 235.dp else 305.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(tileGradient)) {
                val image = if (item.type == "picture") item.uri else item.backdrop.ifBlank { item.art }
                if (image.isNotEmpty()) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(posterShade))
                Column(Modifier.align(Alignment.BottomStart).padding(23.dp)) {
                    PmEyebrow("YOUR LIBRARY / ${item.type.uppercase()}")
                    Text(movieVersions?.let { it.title + if (it.year.isNotBlank()) " (${it.year})" else "" }
                        ?: item.title, fontSize = if (phoneUi) 26.sp else 36.sp,
                        lineHeight = if (phoneUi) 29.sp else 39.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text(item.folder.ifEmpty { "Local file" }, color = Color(0xFFB7D7FF), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { PmRoundButton(if (item.positionMs > 0) "▶ Resume" else "▶ Play", true) { onPlay(item) } }
                item { PmRoundButton(if (item.favourite) "★ Favourite" else "☆ Favourite") { onFavourite(item) } }
                item { PmRoundButton(if (item.watched) "✓ Watched" else "✓ Mark watched") { onWatched(item) } }
                item { PmRoundButton("Remove from library") { onRemove(item); detail = null } }
            }
            if (movieVersions != null) PmEditionChooser(movieVersions, onPlay)
            Spacer(Modifier.height(20.dp))
            Text("About this ${item.type}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
            Spacer(Modifier.height(9.dp))
            if (item.genres.isNotBlank()) {
                Text(item.genres, color = Color(0xFFB6D4FF), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }
            if (item.plot.isNotBlank()) {
                Text(item.plot, color = Color(0xFFE0E2EB), fontSize = 14.sp, lineHeight = 21.sp)
                Spacer(Modifier.height(10.dp))
            }
            if (item.metadataSource.isNotBlank()) {
                Text("Metadata: ${item.metadataSource} · Match checked by title and year", color = pmMuted, fontSize = 11.sp)
                Spacer(Modifier.height(7.dp))
            }
            Text("Stored in ${item.folder.ifEmpty { "your personal media library" }}. ${if (item.positionMs > 0) "Resume position: ${item.positionMs / 60000} minutes." else "Ready to watch."}", color = pmMuted)
        } else if (tab == "Home") {
            val heroItems = library.filter { it.type == "movie" || it.type == "tv" }
                .sortedByDescending { it.positionMs }.take(12)
            PmHomeHero(heroItems, onItem = { detail = it }, onSettings = { goTo("Settings") }, onMovies = { goTo("Movies") })
            if (library.isEmpty()) {
                PmEmpty("Your library is empty", "This native app does not include demo films. Choose media folders in Settings to begin.", onFolder)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.padding(bottom = 30.dp)) {
                    items(listOf("🎬 Movies · ${library.count { it.type == "movie" }}" to "Movies",
                        "📺 TV Shows · ${library.count { it.type == "tv" }}" to "TV Shows",
                        "▶ ${library.count { it.type == "tv" }} Episodes" to "TV Shows",
                        "♡ Favourites" to "Favourites")) { (text, section) -> PmRoundButton(text) { goTo(section) } }
                }
                if (allMovieCollections.isNotEmpty()) {
                    Text("Franchises & Collections", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 27.dp)) {
                        items(allMovieCollections) { group ->
                            PmMovieCollectionTile(group) { goTo("Movies#movie:${group.key}") }
                        }
                    }
                }
                val continuing = library.filter { it.positionMs > 0 }
                if (continuing.isNotEmpty()) {
                    Text("Continue Watching", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 26.dp)) {
                        items(continuing) { PmWatchTile(it) { detail = it } }
                    }
                }
                PmShelf("Explore Movies", library.filter { it.type == "movie" }.take(15), onItem = { detail = it })
                val seriesGroups = allShows
                if (seriesGroups.isNotEmpty()) {
                    Text("Your TV Shows", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(13.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(13.dp), modifier = Modifier.padding(bottom = 30.dp)) {
                        items(seriesGroups.take(15)) { show ->
                            PmSeriesTile(show.title, show.episodes) { goTo("TV Shows#series:${show.key}") }
                        }
                    }
                }
                PmShelf("Explore Music", library.filter { it.type == "music" }.take(15), landscape = true, onItem = { detail = it })
                PmShelf("Your Pictures", library.filter { it.type == "picture" }.take(15), onItem = { detail = it })
            }
        } else {
            Spacer(Modifier.height(26.dp))
            PmEyebrow("YOUR LIBRARY")
            Spacer(Modifier.height(5.dp))
            Text(tab, fontSize = if (phoneUi) 29.sp else 42.sp,
                lineHeight = if (phoneUi) 33.sp else 47.sp,
                fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(if (phoneUi) 16.dp else 30.dp))
            if (phoneUi && tab == "Library") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Movies", "TV Shows", "Music", "Pictures", "Favourites", "Live TV")) { name ->
                        PmPill(name, false) { goTo(name) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All")) { PmPill(it, genre == it) { genre = it } }
            }
            Spacer(Modifier.height(24.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { PmRoundButton("↻ Rescan", onClick = onFolder) }
                item { Text("Watch", color = pmMuted, fontSize = 12.sp) }
                item { PmRoundButton(watch) { watch = when (watch) { "All" -> "Unwatched"; "Unwatched" -> "Watched"; else -> "All" } } }
                item { Text("Sort", color = pmMuted, fontSize = 12.sp) }
                item { PmRoundButton(order) { order = if (order == "Title") "Recent" else "Title" } }
                item { PmRoundButton("＋ Add", onClick = onImport) }
            }
            Spacer(Modifier.height(23.dp))
            if (shown.isEmpty()) {
                PmEmpty("Nothing here yet", "Add files to this category from Settings.", onFolder)
            } else {
                OutlinedTextField(search, { search = it }, placeholder = { Text("Search local library") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                Text("${tab} · ${shown.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(15.dp))
                if (tab == "Movies") {
                    val groups = allMovieCollections.mapNotNull { group ->
                        val filtered = group.movies.filter { movie -> movie.key in activeMovieKeys ||
                            (search.isNotBlank() && group.title.contains(search, ignoreCase = true)) }
                        if (filtered.isEmpty()) null else group.copy(movies = filtered)
                    }
                    if (groups.isNotEmpty()) {
                        Text("Franchises & Collections", color = Color.White, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 24.dp)) {
                            items(groups) { group ->
                                PmMovieCollectionTile(group) { movieCollectionOpen = group.key }
                            }
                        }
                    }
                    val hidden = allMovieCollections.flatMap { it.movies }.map { it.key }.toSet()
                    val standalones = LocalLibraryOrganizer.movieEditions(shown).filterNot { it.key in hidden }
                    Text("Movies · ${standalones.size}", color = Color.White, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (phoneUi) 2 else (maxWidth.value / 174f).toInt().coerceAtLeast(1)
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            standalones.take(visibleMovieCount).chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { movie ->
                                        val best = movie.editions.firstOrNull { it.art.isNotBlank() } ?: movie.editions.first()
                                        val item = best.copy(title = movie.title +
                                            if (movie.year.isNotBlank()) " (${movie.year})" else "")
                                        if (phoneUi) Box(Modifier.weight(1f)) {
                                            PhonePosterCard(item, Modifier.fillMaxWidth()) { detail = movie.editions.first() }
                                        } else PmPosterTile(item, false) { detail = movie.editions.first() }
                                    }
                                    if (phoneUi) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    if (standalones.size > visibleMovieCount) {
                        Spacer(Modifier.height(18.dp))
                        PmRoundButton("Show more movies · ${standalones.size - visibleMovieCount} remaining") {
                            visibleMovieCount += 72
                        }
                    }
                } else if (tab == "TV Shows") {
                    val collections = allTvCollections.mapNotNull { group ->
                        val visible = group.shows.filter { it.key in activeTvKeys }
                        if (visible.isEmpty()) null else group.copy(shows = visible)
                    }
                    if (collections.isNotEmpty()) {
                        Text("TV Collections", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 24.dp)) {
                            items(collections) { group ->
                                PmTvCollectionTile(group) { tvCollectionOpen = group.key }
                            }
                        }
                    }
                    val hidden = allTvCollections.flatMap { it.shows }.map { it.key }.toSet()
                    val shows = LocalLibraryOrganizer.tvShows(shown).filterNot { it.key in hidden }
                    Text("TV Shows · ${shows.size} series", fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(15.dp))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (phoneUi) 2 else (maxWidth.value / 194f).toInt().coerceAtLeast(1)
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            shows.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { show ->
                                        if (phoneUi) Box(Modifier.weight(1f)) {
                                            val art = show.episodes.first().copy(title = show.title, type = "tv")
                                            PhonePosterCard(art, Modifier.fillMaxWidth()) { series = show.key }
                                        } else PmSeriesTile(show.title, show.episodes) { series = show.key }
                                    }
                                    if (phoneUi) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (phoneUi) 2 else (maxWidth.value / 174f).toInt().coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        shown.take(if (phoneUi) visiblePhoneCount else shown.size)
                            .chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { media ->
                                    if (phoneUi) Box(Modifier.weight(1f)) {
                                        PhonePosterCard(media, Modifier.fillMaxWidth()) { detail = media }
                                    } else PmPosterTile(media, media.type == "music") { detail = media }
                                }
                                if (phoneUi) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (phoneUi && shown.size > visiblePhoneCount) {
                        Spacer(Modifier.height(18.dp))
                        PmRoundButton("Show more · ${shown.size - visiblePhoneCount} remaining") {
                            visiblePhoneCount += 60
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PmEmpty(title: String, description: String, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 55.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("＋", color = Color(0xFFA8C8FC), fontSize = 33.sp)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(description, fontSize = 13.sp, color = pmMuted)
        PmRoundButton("＋ Add to a category", true, onClick = onAdd)
    }
}
