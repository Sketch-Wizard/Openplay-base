package org.openplay.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Video(val uri: Uri, val title: String, val durationMs: Long, val folder: String)

fun Context.play(uri: Uri) = startActivity(
    Intent(this, if (Formats.needsVlc(this, uri)) VlcPlayerActivity::class.java else PlayerActivity::class.java).setData(uri)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var vaultKey by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { reload++ }

    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perm) }
    LaunchedEffect(granted, reload) { if (granted) videos = withContext(Dispatchers.IO) { queryVideos(ctx) } }

    val onLock: (Video) -> Unit = { v ->
        if (!Vault.hasPin(ctx)) Toast.makeText(ctx, "Set a PIN in the Private tab first", Toast.LENGTH_LONG).show()
        else scope.launch {
            val ok = withContext(Dispatchers.IO) { Vault.add(ctx, v) }
            vaultKey++
            if (!ok) Toast.makeText(ctx, "Could not copy video", Toast.LENGTH_SHORT).show()
            else if (Build.VERSION.SDK_INT >= 30) {
                val sender = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(v.uri)).intentSender
                deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } else Toast.makeText(ctx, "Copied. Delete the original manually.", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Videos", "Folders", "Stream", "Private").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        if (!granted && tab < 2) {
            Column(Modifier.padding(24.dp)) {
                Text("OpenPlay needs access to your videos.")
                Button(onClick = { permLauncher.launch(perm) }) { Text("Grant access") }
            }
        } else when (tab) {
            0 -> VideoList(videos, onLock)
            1 -> FoldersScreen(videos, onLock)
            2 -> StreamScreen()
            else -> PrivateScreen(vaultKey)
        }
    }
}

@Composable
fun VideoList(videos: List<Video>, onLock: (Video) -> Unit) {
    val ctx = LocalContext.current
    if (videos.isEmpty()) { Text("No videos found.", Modifier.padding(24.dp)); return }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(videos) { v ->
            Row(Modifier.fillMaxWidth().clickable { ctx.play(v.uri) }.padding(vertical = 10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(v.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Text("${v.durationMs / 60000}:${"%02d".format(v.durationMs / 1000 % 60)}  ·  ${v.folder}",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onLock(v) }) { Text("Lock") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun FoldersScreen(videos: List<Video>, onLock: (Video) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    val groups = videos.groupBy { it.folder }
    BackHandler(open != null) { open = null }
    val current = open
    if (current == null) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(groups.keys.toList()) { f ->
                Column(Modifier.fillMaxWidth().clickable { open = f }.padding(vertical = 12.dp)) {
                    Text(f, style = MaterialTheme.typography.bodyLarge)
                    Text("${groups[f]?.size ?: 0} videos", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    } else VideoList(groups[current].orEmpty(), onLock)
}

@Composable
fun StreamScreen() {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp)) {
        Text("Play a network stream (http/https, HLS .m3u8, DASH .mpd)")
        OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (url.startsWith("http")) ctx.play(Uri.parse(url.trim())) }) { Text("Play") }
    }
}

@Composable
fun PrivateScreen(refreshKey: Int) {
    val ctx = LocalContext.current
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(listOf<File>()) }
    val creating = !Vault.hasPin(ctx)

    if (!unlocked) {
        Column(Modifier.padding(24.dp)) {
            Text(if (creating) "Create a PIN (4–8 digits) for your private folder" else "Enter PIN")
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); error = false },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = error, singleLine = true
            )
            Button(onClick = {
                if (creating) { if (pin.length >= 4) { Vault.setPin(ctx, pin); unlocked = true } else error = true }
                else if (Vault.check(ctx, pin)) unlocked = true else error = true
            }) { Text(if (creating) "Set PIN" else "Unlock") }
        }
    } else {
        LaunchedEffect(refreshKey) { files = Vault.list(ctx) }
        if (files.isEmpty()) Text("Private folder is empty. Use \"Lock\" on a video.", Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(files) { f ->
                Row(Modifier.fillMaxWidth().clickable { ctx.play(Uri.fromFile(f)) }.padding(vertical = 10.dp)) {
                    Text(f.name, Modifier.weight(1f), maxLines = 2)
                    TextButton(onClick = { f.delete(); files = Vault.list(ctx) }) { Text("Delete") }
                }
                HorizontalDivider()
            }
        }
    }
}

fun queryVideos(c: Context): List<Video> {
    val out = mutableListOf<Video>()
    val m = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME
    )
    c.contentResolver.query(m, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { q ->
        while (q.moveToNext()) {
            out += Video(ContentUris.withAppendedId(m, q.getLong(0)), q.getString(1) ?: "Unknown",
                q.getLong(2), q.getString(3) ?: "Other")
        }
    }
    return out
}
