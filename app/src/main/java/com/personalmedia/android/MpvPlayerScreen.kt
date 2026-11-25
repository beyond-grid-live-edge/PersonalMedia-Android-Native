package com.personalmedia.android

import android.content.res.Configuration
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Real optional libmpv EGL surface frontend (never a WebView). Requires ABI-matched libmpv.so. */
@Composable
fun MpvPlayerScreen(uri: String, onFallback: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val phoneUi = configuration.smallestScreenWidthDp < 600
    val compactPortrait = phoneUi && configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    var paused by remember { mutableStateOf(false) }
    var controlsVisible by remember(uri) { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(uri) {
        onDispose { if (NativeBridge.loaded) NativeBridge.mpvStop() }
    }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(if (phoneUi) phoneNavy else Color.Black)) {
        AndroidView(factory = { context ->
            SurfaceView(context).apply {
                if (phoneUi) setOnClickListener { controlsVisible = !controlsVisible }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        scope.launch(Dispatchers.IO) {
                            val success = NativeBridge.mpvStart(holder.surface, uri)
                            if (!success) {
                                error = "MPV EGL renderer unavailable; switching to Android playback"
                                onFallback()
                            }
                        }
                    }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) { NativeBridge.mpvStop() }
                })
            }
        }, modifier = if (compactPortrait) Modifier.align(Alignment.Center).fillMaxWidth()
            .aspectRatio(16f / 9f) else Modifier.fillMaxSize())
        if (controlsVisible) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0xCC091A34)).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("← Back", color = Color.White) }
                Text("libmpv · EGL native", color = Color.LightGray)
                TextButton(onClick = { paused = !paused; NativeBridge.mpvPause(paused) }) {
                    Text(if (paused) "▶ Play" else "Ⅱ Pause", color = Color.White)
                }
            }
        }
        if (error.isNotEmpty()) {
            Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}
