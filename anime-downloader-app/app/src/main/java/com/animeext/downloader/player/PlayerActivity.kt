package com.animeext.downloader.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.animeext.downloader.network.NetworkClient
import java.io.File

class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URI = "extra_uri"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_HEADERS_KEYS = "extra_header_keys"
        private const val EXTRA_HEADERS_VALS = "extra_header_vals"

        fun createIntent(
            context: Context,
            uriOrPath: String,
            title: String,
            headers: Map<String, String> = emptyMap()
        ): Intent {
            val keys = ArrayList(headers.keys)
            val vals = ArrayList(keys.map { headers[it].orEmpty() })
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URI, uriOrPath)
                putExtra(EXTRA_TITLE, title)
                putStringArrayListExtra(EXTRA_HEADERS_KEYS, keys)
                putStringArrayListExtra(EXTRA_HEADERS_VALS, vals)
            }
        }
    }

    private var player: ExoPlayer? = null
    private var isBufferingState by mutableStateOf(true)
    private var errorMessageState by mutableStateOf<String?>(null)

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val rawTarget = intent.getStringExtra(EXTRA_URI).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Player" }
        val keys = intent.getStringArrayListExtra(EXTRA_HEADERS_KEYS) ?: arrayListOf()
        val vals = intent.getStringArrayListExtra(EXTRA_HEADERS_VALS) ?: arrayListOf()
        val headers = LinkedHashMap<String, String>()
        for (i in keys.indices) {
            if (i < vals.size) headers[keys[i]] = vals[i]
        }

        val isRemote = rawTarget.startsWith("http://") || rawTarget.startsWith("https://")
        val mediaUri = if (isRemote) {
            Uri.parse(rawTarget)
        } else {
            val localFile = File(rawTarget)
            if (!localFile.exists() || localFile.length() == 0L) {
                errorMessageState = "Media file is missing or empty."
                isBufferingState = false
            }
            Uri.fromFile(localFile)
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: NetworkClient.CHROME_UA)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(25_000)
            .setAllowCrossProtocolRedirects(true)
            .apply {
                if (headers.isNotEmpty()) setDefaultRequestProperties(headers)
            }
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBufferingState = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_READY) {
                            errorMessageState = null
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isBufferingState = false
                        errorMessageState = "Playback failed (${error.errorCodeName}): ${error.cause?.localizedMessage ?: error.message ?: "Unsupported or expired stream"}"
                    }
                })
                if (errorMessageState == null) {
                    setMediaItem(MediaItem.fromUri(mediaUri))
                    prepare()
                    playWhenReady = true
                }
            }
        player = exo

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                useController = true
                                this.player = exo
                            }
                        }
                    )

                    if (isBufferingState && errorMessageState == null) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    errorMessageState?.let { err ->
                        Surface(
                            color = Color(0xE60B101B),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFFB7185),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = err,
                                    color = Color(0xFFF3F4F6),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { finish() }) {
                                        Text("Back")
                                    }
                                    Button(onClick = {
                                        errorMessageState = null
                                        isBufferingState = true
                                        exo.setMediaItem(MediaItem.fromUri(mediaUri))
                                        exo.prepare()
                                        exo.playWhenReady = true
                                    }) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Text(
                            text = title,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
