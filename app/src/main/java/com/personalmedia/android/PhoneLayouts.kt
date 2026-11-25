package com.personalmedia.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

internal val phoneNavy = Color(0xFF071424)
internal val phoneSurface = Color(0xFF12253E)
internal val phoneBlue = Color(0xFF1681FF)
private val phoneLine = Color(0xFF2A4568)
private val phoneMuted = Color(0xFFAFBED7)
private val phoneBackdrop = Brush.verticalGradient(
    listOf(Color(0xFF101D35), Color(0xFF071424), Color(0xFF0B1729)))

/** Navigation remains the tablet original above 600 smallest-width dp. */
@Composable
internal fun PhoneHeader(onSearch: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(68.dp).background(Color(0xFF0D1A30))
        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.pm_mark), "PersonalMedia", Modifier.size(39.dp))
        Spacer(Modifier.width(12.dp))
        Text("PersonalMedia", modifier = Modifier.weight(1f), color = Color.White,
            fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White,
                modifier = Modifier.size(27.dp))
        }
    }
}

@Composable
internal fun PhoneBottomNavigation(activeTab: String, onNavigate: (String) -> Unit) {
    val destinations = listOf(
        Triple("Home", "Home", Icons.Default.Home),
        Triple("Discover", "Stremio", Icons.Default.Explore),
        Triple("Library", "Library", Icons.Default.VideoLibrary),
        Triple("Settings", "Settings", Icons.Default.Settings)
    )
    Row(Modifier.fillMaxWidth().height(70.dp).background(Color(0xFF0D1B30))
        .border(width = 0.5.dp, color = phoneLine),
        verticalAlignment = Alignment.CenterVertically) {
        destinations.forEach { (name, route, image) ->
            val selected = when (route) {
                "Library" -> activeTab in listOf("Library", "Movies", "TV Shows", "Music", "Pictures", "Favourites")
                else -> activeTab == route
            }
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onNavigate(route) }
                .padding(top = 8.dp, bottom = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween) {
                Icon(image, contentDescription = name, tint = if (selected) phoneBlue else phoneMuted,
                    modifier = Modifier.size(24.dp))
                Text(name, fontSize = 11.sp, maxLines = 1,
                    color = if (selected) phoneBlue else phoneMuted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.width(35.dp).height(3.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (selected) phoneBlue else Color.Transparent))
            }
        }
    }
}

@Composable
internal fun PhonePosterCard(item: MediaEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val poster = item.art.ifBlank { item.backdrop }.ifBlank {
        if (item.type == "picture") item.uri else ""
    }
    Box(modifier.height(222.dp).clip(RoundedCornerShape(15.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF233D69), Color(0xFF111D34))))
        .border(1.dp, phoneLine, RoundedCornerShape(15.dp))
        .clickable(onClick = onClick)) {
        if (poster.isNotBlank()) AsyncImage(poster, contentDescription = item.title,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            Color.Transparent, Color(0x3907101C), Color(0xF006111F)))))
        Column(Modifier.align(Alignment.BottomStart).padding(11.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (item.type) {
                "movie" -> "Movie"; "tv" -> "TV Shows"; "music" -> "Music"; else -> "Pictures"
            }, color = phoneMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RowScope.PhoneCategory(name: String, count: Int, route: String, onNavigate: (String) -> Unit,
    icon: @Composable () -> Unit) {
    Column(Modifier.weight(1f).height(105.dp).clip(RoundedCornerShape(15.dp))
        .background(phoneSurface).border(1.dp, phoneLine, RoundedCornerShape(15.dp))
        .clickable { onNavigate(route) }.padding(vertical = 10.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        icon()
        Text(name, fontSize = 11.sp, maxLines = 1, color = Color.White,
            fontWeight = FontWeight.SemiBold)
        Text("$count", fontSize = 12.sp, color = phoneMuted)
    }
}

/** Phone-first Home, backed entirely by saved local items (never synthetic samples). */
@Composable
internal fun PhoneHomeScreen(library: List<MediaEntry>, onPlay: (MediaEntry) -> Unit,
    onNavigate: (String) -> Unit) {
    val continuations = library.filter { it.positionMs > 0 && (it.type == "movie" || it.type == "tv") }
        .sortedByDescending { it.positionMs }
    val recent = library.asReversed().filter { it.type == "movie" || it.type == "tv" || it.type == "picture" }
        .take(4)
    Column(Modifier.fillMaxSize().background(phoneBackdrop).verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val current = continuations.firstOrNull()
        if (current != null) {
            Text("Continue Watching", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold)
            val art = current.backdrop.ifBlank { current.art }
            Box(Modifier.fillMaxWidth().height(207.dp).clip(RoundedCornerShape(17.dp))
                .background(phoneSurface).border(1.dp, phoneLine, RoundedCornerShape(17.dp))
                .clickable { onPlay(current) }) {
                if (art.isNotBlank()) AsyncImage(art, current.title,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                    Color(0x0805101A), Color(0x6205101A), Color(0xED061223)))))
                Column(Modifier.align(Alignment.BottomStart).padding(15.dp)) {
                    Text(current.title, color = Color.White, fontSize = 21.sp,
                        fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Resume at ${current.positionMs / 60000} min", color = phoneMuted, fontSize = 12.sp)
                }
                Text("▶", color = Color.White, fontSize = 25.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(15.dp)
                        .clip(RoundedCornerShape(30.dp)).background(phoneBlue)
                        .padding(horizontal = 13.dp, vertical = 7.dp))
            }
        } else if (library.isNotEmpty()) {
            Text("Your Library", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("Pick up a film or explore your saved media.", color = phoneMuted, fontSize = 13.sp)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recently Added", color = Color.White, fontSize = 23.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("See All ›", color = phoneBlue, fontSize = 13.sp,
                modifier = Modifier.clickable { onNavigate("Library") })
        }
        if (recent.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(phoneSurface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your library is empty", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Choose media folders in Settings to begin.", color = phoneMuted, fontSize = 12.sp)
                Text("Open Settings ›", color = phoneBlue,
                    modifier = Modifier.clickable { onNavigate("Settings") })
            }
        } else recent.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { item -> Box(Modifier.weight(1f)) {
                    PhonePosterCard(item, Modifier.fillMaxWidth()) { onPlay(item) }
                } }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("Your Library", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhoneCategory("Movies", library.count { it.type == "movie" }, "Movies", onNavigate) {
                Icon(Icons.Default.Movie, null, tint = phoneBlue, modifier = Modifier.size(25.dp))
            }
            PhoneCategory("TV Shows", library.count { it.type == "tv" }, "TV Shows", onNavigate) {
                Icon(Icons.Default.Tv, null, tint = phoneBlue, modifier = Modifier.size(25.dp))
            }
            PhoneCategory("Music", library.count { it.type == "music" }, "Music", onNavigate) {
                Icon(Icons.Default.MusicNote, null, tint = phoneBlue, modifier = Modifier.size(25.dp))
            }
            PhoneCategory("Photos", library.count { it.type == "picture" }, "Pictures", onNavigate) {
                Icon(Icons.Default.PhotoLibrary, null, tint = phoneBlue, modifier = Modifier.size(25.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(phoneSurface)
                .clickable { onNavigate("Stremio") }.padding(15.dp),
                contentAlignment = Alignment.Center) {
                Text("Discover Stremio ›", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(phoneSurface)
                .clickable { onNavigate("Live TV") }.padding(15.dp),
                contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LiveTv, null, tint = phoneBlue, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Live TV ›", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
