package org.openplay.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

class PlayerActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private lateinit var playerView: PlayerView
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 3
    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    private val resizeNames = arrayOf("Fit", "Fill", "Zoom")
    private var resizeIndex = 0
    private var backgroundPlay = false
    private var brightness = 0.5f
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::addSubtitle) }

    private fun btn(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE)
        setBackgroundColor(0x66000000); setPadding(28, 20, 28, 20)
        setOnClickListener { onClick(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let {
            if (Formats.needsVlc(this, it)) { openInVlc(it); return }
        }
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        playerView = PlayerView(this)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("1x") { b ->
                speedIndex = (speedIndex + 1) % speeds.size
                controller?.setPlaybackSpeed(speeds[speedIndex]); b.text = "${speeds[speedIndex]}x"
            })
            addView(btn("Fit") { b ->
                resizeIndex = (resizeIndex + 1) % resizeModes.size
                playerView.resizeMode = resizeModes[resizeIndex]; b.text = resizeNames[resizeIndex]
            })
            addView(btn("CC") { pickSubtitle.launch(arrayOf("*/*")) })
            addView(btn("BG: off") { b -> backgroundPlay = !backgroundPlay; b.text = if (backgroundPlay) "BG: on" else "BG: off" })
        }
        setContentView(FrameLayout(this).apply {
            addView(playerView)
            addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        })
        connect()
        setupGestures()
    }

    private fun connect() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val c = future.get(); controller = c; playerView.player = c
            c.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { intent.data?.let { openInVlc(it) } }
            })
            val uri = intent.data ?: return@addListener
            c.setMediaItem(MediaItem.fromUri(uri), prefs.getLong(uri.toString(), 0L))
            c.prepare(); c.play()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun openInVlc(uri: Uri) {
        startActivity(Intent(this, VlcPlayerActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private fun addSubtitle(sub: Uri) {
        val c = controller ?: return
        val video = intent.data ?: return
        val name = contentResolver.query(sub, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: ""
        val mime = when {
            name.endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
            name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val cfg = MediaItem.SubtitleConfiguration.Builder(sub)
            .setMimeType(mime).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val pos = c.currentPosition
        c.setMediaItem(MediaItem.Builder().setUri(video).setSubtitleConfigurations(listOf(cfg)).build(), pos)
    }

    private fun setupGestures() {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                val c = controller ?: return false
                if (e1 == null) return false
                if (abs(dx) > abs(dy)) {
                    c.seekTo((c.currentPosition - dx * 100).toLong().coerceAtLeast(0))
                } else if (e1.x < playerView.width / 2f) {
                    brightness = (brightness + dy / playerView.height).coerceIn(0.01f, 1f)
                    window.attributes = window.attributes.also { it.screenBrightness = brightness }
                } else if (abs(dy) > 8) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + if (dy > 0) 1 else -1).coerceIn(0, max), 0)
                }
                return true
            }
        })
        playerView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
    }

    override fun onUserLeaveHint() {
        if (controller?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onStop() {
        super.onStop()
        val c = controller ?: return
        intent.data?.let { prefs.edit().putLong(it.toString(), c.currentPosition).apply() }
        if (!isInPictureInPictureMode && !backgroundPlay) c.pause()
    }

    override fun onDestroy() { controller?.release(); super.onDestroy() }
}
