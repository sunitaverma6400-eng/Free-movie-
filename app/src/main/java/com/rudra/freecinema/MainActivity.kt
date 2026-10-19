package com.rudra.freecinema

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    object Home : Screen
    object Settings : Screen
    object Downloads : Screen
    data class Detail(val id: String) : Screen
    data class Player(val backId: String?, val title: String, val options: List<Stream>, val index: Int) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

fun enqueueDownload(ctx: Context, url: String, title: String) {
    val name = title.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).removeSuffix(".mp4") + ".mp4"
    val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val req = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, name)
    dm.enqueue(req)
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("fc", Context.MODE_PRIVATE) }
    val api = remember {
        Api({ prefs.getString("url", "") ?: "" }, { prefs.getString("token", "") ?: "" })
    }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    BackHandler(screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Player -> s.backId?.let { Screen.Detail(it) } ?: Screen.Downloads
            else -> Screen.Home
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.statusBarsPadding()) {
            when (val s = screen) {
                Screen.Home -> HomeScreen(api, { screen = Screen.Detail(it) }, { screen = Screen.Downloads }, { screen = Screen.Settings })
                Screen.Settings -> SettingsScreen(prefs) { screen = Screen.Home }
                Screen.Downloads -> DownloadsScreen { f ->
                    screen = Screen.Player(null, f.name, listOf(Stream(f.name, "Offline", Uri.fromFile(f).toString())), 0)
                }
                is Screen.Detail -> DetailScreen(api, s.id) { title, opts, i -> screen = Screen.Player(s.id, title, opts, i) }
                is Screen.Player -> PlayerScreen(s)
            }
        }
    }
}

@Composable
fun HomeScreen(api: Api, onOpen: (String) -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        scope.launch {
            loading = true; err = null
            runCatching { withContext(Dispatchers.IO) { api.search(q) } }
                .onSuccess { items = it }
                .onFailure { err = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Free Cinema", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDownloads) { Text("Downloads") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Text("Public domain + Creative Commons movies", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, label = { Text("Movie search (any language)") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { search() }) { Text("Go") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(items) { m ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage("https://archive.org/services/img/${m.id}", null, Modifier.size(width = 72.dp, height = 96.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(m.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (m.year.isNotEmpty()) Text(m.year, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailScreen(api: Api, id: String, onPlay: (String, List<Stream>, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<Detail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var male by remember { mutableStateOf(false) }
    var jobFile by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<DubJob?>(null) }

    LaunchedEffect(id) {
        runCatching { withContext(Dispatchers.IO) { api.detail(id) } }
            .onSuccess { d = it }.onFailure { err = it.message }
    }

    fun dub(file: String) {
        scope.launch {
            err = null; jobFile = file; job = DubJob("queued", "Start", 0, null, null)
            runCatching { withContext(Dispatchers.IO) { api.startDub(id, file, male) } }
                .onSuccess { (jid, first) ->
                    job = first
                    var cur = first
                    while (cur.status == "queued" || cur.status == "running") {
                        delay(3000)
                        val next = runCatching { withContext(Dispatchers.IO) { api.jobStatus(jid) } }.getOrNull()
                        if (next != null) { cur = next; job = next }
                    }
                    if (cur.status == "error") err = cur.error
                }
                .onFailure { err = it.message; job = null }
        }
    }

    val detail = d
    LazyColumn(Modifier.fillMaxSize().padding(12.dp)) {
        if (detail == null) {
            item {
                if (err == null) CircularProgressIndicator()
                else Text(err!!, color = MaterialTheme.colorScheme.error)
            }
            return@LazyColumn
        }
        item {
            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
            if (detail.description.isNotEmpty()) Text(detail.description, maxLines = 6, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hindi awaaz:")
                Spacer(Modifier.width(8.dp))
                FilterChip(!male, { male = false }, { Text("Female") })
                Spacer(Modifier.width(8.dp))
                FilterChip(male, { male = true }, { Text("Male") })
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            job?.let { j ->
                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Hindi dub: ${j.stage} (${j.progress}%)")
                        LinearProgressIndicator(progress = { j.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        if (j.status == "done" && j.url != null) {
                            val dubbed = Stream("dub", "Hindi Dub", j.url)
                            Row {
                                Button(onClick = { onPlay(detail.title + " (Hindi)", listOf(dubbed) + detail.streams, 0) }) { Text("Play Hindi") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { enqueueDownload(ctx, j.url, detail.title + "_Hindi") }) { Text("Download Hindi") }
                            }
                        }
                    }
                }
            }
            Text("Quality / files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            if (detail.streams.isEmpty()) Text("Is item me playable mp4 nahi mila")
        }
        items(detail.streams.size) { i ->
            val s = detail.streams[i]
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(s.label, style = MaterialTheme.typography.titleSmall)
                    Row {
                        Button(onClick = { onPlay(detail.title, detail.streams, i) }) { Text("Play") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { enqueueDownload(ctx, s.url, detail.title + "_" + s.label) }) { Text("Download") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { dub(s.file) }, enabled = job?.status != "running") { Text("Hindi Dub") }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(s: Screen.Player) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(s.index) }
    val exo = remember { ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    LaunchedEffect(idx) {
        val pos = exo.currentPosition
        exo.setMediaItem(MediaItem.fromUri(s.options[idx].url), pos)
        exo.prepare()
        exo.playWhenReady = true
    }
    Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { player = exo } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Text(s.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            items(s.options.size) { i ->
                FilterChip(i == idx, { idx = i }, { Text(s.options[i].label) }, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun DownloadsScreen(onPlay: (File) -> Unit) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf(listFiles(ctx)) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Downloads", style = MaterialTheme.typography.headlineSmall)
        Text("Sirf is app ke andar save hote hain, gallery me nahi dikhte.", style = MaterialTheme.typography.bodySmall)
        if (files.isEmpty()) Text("Abhi kuch download nahi hai", modifier = Modifier.padding(top = 12.dp))
        LazyColumn {
            items(files) { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, Modifier.weight(1f).clickable { onPlay(f) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { f.delete(); files = listFiles(ctx) }) { Text("Delete") }
                }
            }
        }
    }
}

private fun listFiles(ctx: Context): List<File> =
    ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.listFiles()?.filter { it.isFile && it.name.endsWith(".mp4") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

@Composable
fun SettingsScreen(prefs: android.content.SharedPreferences, onDone: () -> Unit) {
    var url by remember { mutableStateOf(prefs.getString("url", "") ?: "") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true, label = { Text("Render server URL (https://...onrender.com)") })
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("APP_TOKEN") })
        Button(onClick = { prefs.edit().putString("url", url.trim()).putString("token", token.trim()).apply(); onDone() }, modifier = Modifier.padding(top = 12.dp)) { Text("Save") }
    }
}
