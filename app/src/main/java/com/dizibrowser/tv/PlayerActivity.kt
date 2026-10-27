package com.dizibrowser.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleBanner: TextView
    private lateinit var history: WatchHistory

    private var streamUrl: String = ""
    private var pageUrl: String = ""
    private var pageTitle: String = ""
    private var referer: String = ""
    private var userAgent: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val saveProgressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying && p.currentPosition > 3000L) {
                history.updatePositionByUrlOrStream(pageUrl, streamUrl, p.currentPosition)
            }
            handler.postDelayed(this, 5000L)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        history = WatchHistory(this)
        streamUrl = intent.getStringExtra("streamUrl") ?: ""
        pageUrl = intent.getStringExtra("pageUrl") ?: ""
        pageTitle = intent.getStringExtra("pageTitle") ?: "Video Oynatıcı"
        referer = intent.getStringExtra("referer") ?: pageUrl
        userAgent = intent.getStringExtra("userAgent") ?: "Mozilla/5.0 (SmartTV; Linux; Android 12)"

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "Video adresi bulunamadı", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 4000
            isFocusable = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        titleBanner = TextView(this).apply {
            text = "🎬 $pageTitle\n◀ / ▶ 10 sn İleri-Geri  •  OK Duraklat/Oynat  •  GERİ Çıkış"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 18, 28, 18)
            setBackgroundColor(Color.parseColor("#AA0D1117"))
        }
        root.addView(
            titleBanner,
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        )

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleBanner.visibility = visibility
        })

        setContentView(root)
        initializePlayer()
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        val headers = mutableMapOf<String, String>()
        if (referer.isNotBlank()) {
            headers["Referer"] = referer
            try {
                val uri = android.net.Uri.parse(referer)
                val origin = "${uri.scheme}://${uri.host}"
                headers["Origin"] = origin
            } catch (_: Exception) {}
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player = exoPlayer
        playerView.player = exoPlayer

        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)

        val savedPos = history.getResumePosition(pageUrl, streamUrl)
        if (savedPos > 10_000L) {
            exoPlayer.seekTo(savedPos)
            val mins = (savedPos / 1000) / 60
            val secs = (savedPos / 1000) % 60
            Toast.makeText(
                this,
                "Kaldığınız yerden devam ediliyor (%02d:%02d)".format(mins, secs),
                Toast.LENGTH_LONG
            ).show()
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlayerActivity,
                    "Video akışı açılamadı (${error.errorCodeName}). Tarayıcı player'ı ile deneyin.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        handler.postDelayed(saveProgressRunnable, 5000L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                p.seekTo((p.currentPosition + 10_000L).coerceAtMost(p.duration.coerceAtLeast(0L)))
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L))
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (!playerView.isControllerFullyVisible) {
                    if (p.isPlaying) p.pause() else p.play()
                    playerView.showController()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            if (it.currentPosition > 3000L) {
                history.updatePositionByUrlOrStream(pageUrl, streamUrl, it.currentPosition)
            }
            it.pause()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgressRunnable)
        player?.release()
        player = null
        super.onDestroy()
    }
}
