package org.openplay.player

import android.app.PictureInPictureParams
import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import kotlin.math.abs

/** Fallback player (libVLC) for formats ExoPlayer can't handle: AVI, WMV, RMVB, ASF, etc. */
class VlcPlayerActivity : ComponentActivity() {
    private lateinit var libVlc: LibVLC
    private lateinit var mp: MediaPlayer
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private lateinit var controls: LinearLayout
    private var pfd: ParcelFileDescriptor? = null
    private val handler = Handler(Looper.getMainLooper())
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 3
    private val scales = arrayOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT, MediaPlayer.ScaleType.SURFACE_FILL,
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    )
    private val scaleNames = arrayOf("Fit", "Fill", "Zoom")
    private var scaleIndex = 0
    private var resumed = false
    private var dragging = false
    private var brightness = 0.5f
    private val prefs by lazy { getSharedPreferences("resume", MODE_PRIVATE) }
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::addSubtitle) }

    private val ticker = object : Runnable {
        override fun run() {
            if (!dragging && mp.length > 0) { seek.max = mp.length.toInt(); seek.progress = mp.time.toInt() }
            timeText.text = "${fmt(mp.time)} / ${fmt(mp.length)}"
            handler.postDelayed(this, 500)
        }
    }

    private fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun btn(label: String, onClick: (TextView) -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE)
        setBackgroundColor(0x66000000); setPadding(28, 20, 28, 20)
        setOnClickListener { onClick(this) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: run { finish(); return }
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        libVlc = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
        mp = MediaPlayer(libVlc)
        val videoLayout = VLCVideoLayout(this)
        mp.attachViews(videoLayout, null, true, false)

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar?) { mp.time = progress.toLong(); dragging = false }
            })
        }
        timeText = TextView(this).apply { setTextColor(Color.WHITE); setPadding(24, 0, 0, 0) }
        playBtn = btn("Pause") { mp.let { if (it.isPlaying) it.pause() else it.play() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(playBtn)
            addView(btn("1x") { b -> speedIndex = (speedIndex + 1) % speeds.size; mp.rate = speeds[speedIndex]; b.text = "${speeds[speedIndex]}x" })
            addView(btn("Fit") { b -> scaleIndex = (scaleIndex + 1) % scales.size; mp.videoScale = scales[scaleIndex]; b.text = scaleNames[scaleIndex] })
            addView(btn("CC") { pickSubtitle.launch(arrayOf("*/*")) })
            addView(btn("Audio") { cycleAudio() })
            addView(timeText)
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0x88000000.toInt()); setPadding(16, 8, 16, 16)
            addView(seek, LinearLayout.LayoutParams(-1, -2)); addView(row)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoLayout, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)
        setupGestures(root)

        val media = if (uri.scheme == "content") {
            pfd = contentResolver.openFileDescriptor(uri, "r")
            Media(libVlc, pfd!!.fileDescriptor)
        } else Media(libVlc, uri)
        media.setHWDecoderEnabled(true, false)
        mp.media = media
        media.release()

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    playBtn.text = "Pause"
                    if (!resumed) {
                        resumed = true
                        val saved = prefs.getLong(uri.toString(), 0L)
                        if (saved > 0) mp.time = saved
                    }
                }
                MediaPlayer.Event.Paused -> playBtn.text = "Play"
                MediaPlayer.Event.EncounteredError ->
                    Toast.makeText(this, "Cannot play this file", Toast.LENGTH_LONG).show()
            }
        }
        mp.play()
        handler.post(ticker)
    }

    private fun cycleAudio() {
        val tracks = mp.audioTracks?.filter { it.id != -1 } ?: return
        if (tracks.isEmpty()) return
        val next = tracks[(tracks.indexOfFirst { it.id == mp.audioTrack } + 1) % tracks.size]
        mp.audioTrack = next.id
        Toast.makeText(this, "Audio: ${next.name}", Toast.LENGTH_SHORT).show()
    }

    private fun addSubtitle(sub: Uri) {
        val name = contentResolver.query(sub, null, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: "sub.srt"
        val f = File(cacheDir, name)
        contentResolver.openInputStream(sub)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        mp.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(f), true)
    }

    private fun setupGestures(root: View) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e1 == null) return false
                if (abs(dx) > abs(dy)) {
                    mp.time = (mp.time - dx * 100).toLong().coerceAtLeast(0)
                } else if (e1.x < root.width / 2f) {
                    brightness = (brightness + dy / root.height).coerceIn(0.01f, 1f)
                    window.attributes = window.attributes.also { it.screenBrightness = brightness }
                } else if (abs(dy) > 8) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (cur + if (dy > 0) 1 else -1).coerceIn(0, max), 0)
                }
                return true
            }
        })
        root.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    override fun onUserLeaveHint() {
        if (mp.isPlaying) enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }

    override fun onStop() {
        super.onStop()
        intent.data?.let { prefs.edit().putLong(it.toString(), mp.time).apply() }
        if (!isInPictureInPictureMode) mp.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        mp.stop(); mp.detachViews(); mp.release(); libVlc.release()
        pfd?.close()
        super.onDestroy()
    }
}
