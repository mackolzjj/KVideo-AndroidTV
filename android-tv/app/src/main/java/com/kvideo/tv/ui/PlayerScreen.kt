package com.kvideo.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.kvideo.tv.data.Net
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route
import kotlinx.coroutines.delay

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun PlayerScreen(vm: AppVm, route: Route.Player) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    // 资源站的 CDN 会挑 UA / Referer（浏览器能播、裸请求 403），
    // 这里把浏览器那套头补上，并允许 http→https 跳转。
    val exo = remember {
        val ds = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .setDefaultRequestProperties(
                mapOf("Referer" to (Net.base.trimEnd('/') + "/"))
            )
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(ds)
            )
            .build()
    }

    var playing by remember { mutableStateOf(true) }
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var speedOpen by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 上游 VideoPlayer 的做法：proxyMode=auto 时先直连，出错再切 /api/iptv/stream 代理重试一次
    var viaProxy by remember { mutableStateOf(vm.proxyMode == 2) }

    fun mediaFor(useProxy: Boolean): MediaItem {
        val url = if (useProxy) {
            Net.abs("/api/iptv/stream?url=" + java.net.URLEncoder.encode(route.url, "UTF-8"))
        } else route.url
        return MediaItem.Builder()
            .setUri(url)
            .apply { route.mime?.let { setMimeType(it) } }
            .build()
    }

    DisposableEffect(Unit) {
        val item = mediaFor(viaProxy)
        exo.setMediaItem(item)
        exo.prepare()
        exo.playWhenReady = true
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlaybackStateChanged(state: Int) {
                duration = exo.duration.coerceAtLeast(0L)
                if (state == Player.STATE_ENDED) playing = false
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 自动模式：直连失败就切服务器代理再试一次。
                // 直播不做这个兜底 —— 直播中继会按「短时间内请求了 N 个不同频道」限流，
                // 一失败就再打一次只会更快被判成批量探测。
                if (vm.proxyMode == 0 && !route.isLive && !viaProxy) {
                    viaProxy = true
                    runCatching {
                        exo.setMediaItem(mediaFor(true))
                        exo.prepare()
                        exo.playWhenReady = true
                    }
                    return
                }
                val cause = error.cause?.message?.take(120).orEmpty()
                errorText = error.errorCodeName + if (cause.isNotBlank()) " · " + cause else ""
            }
        }
        exo.addListener(listener)
        onDispose {
            runCatching { exo.removeListener(listener) }
            exo.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            runCatching {
                position = exo.currentPosition
                duration = exo.duration.coerceAtLeast(0L)
            }
            delay(400)
        }
    }

    // 3 秒无操作自动收起控制层
    LaunchedEffect(showControls, locked, playing) {
        if (showControls && !locked && playing) {
            delay(3200)
            showControls = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = exo
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (locked) showControls = true else showControls = !showControls
                }
        )

        if (!playing && !locked) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x8C000000))
                    .clickable {
                        exo.play()
                        playing = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        val visible = showControls || !playing
        if (visible && !locked) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KvIconBtn(Icons.Filled.ArrowBack, "返回", size = 40) { vm.back() }
                Column(Modifier.weight(1f)) {
                    Text(route.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                    if (route.subtitle.isNotBlank()) {
                        Text(route.subtitle, fontSize = 11.sp, color = Kv.Ink2, maxLines = 1)
                    }
                }
            }

            // 底栏
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 12.dp, end = 12.dp, top = 40.dp, bottom = 10.dp)
            ) {
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration) else 0f,
                    onValueChange = { v ->
                        if (duration > 0) {
                            exo.seekTo((v * duration).toLong())
                            position = (v * duration).toLong()
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Kv.Red,
                        activeTrackColor = Kv.Red,
                        inactiveTrackColor = Color(0x47FFFFFF)
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = 42) { exo.seekTo((position - 10_000).coerceAtLeast(0)) }
                    KvIconBtn(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "暂停" else "播放",
                        size = 46
                    ) {
                        if (playing) exo.pause() else exo.play()
                        playing = !playing
                    }
                    KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = 42) {
                        exo.seekTo((position + 10_000).coerceAtMost(if (duration > 0) duration else position + 10_000))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${fmt(position)} / ${fmt(duration)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    Box {
                        Text(
                            "倍速 ${"%.1f".format(speed)}X",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { speedOpen = !speedOpen }
                                .padding(horizontal = 9.dp, vertical = 8.dp)
                        )
                        if (speedOpen) {
                            Column(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(y = (-42).dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Kv.Surface)
                                    .padding(6.dp)
                            ) {
                                listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                    Text(
                                        "${s}X",
                                        fontSize = 12.sp,
                                        fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                                        color = if (s == speed) Kv.Red else Color.White,
                                        modifier = Modifier
                                            .clickable {
                                                speed = s
                                                exo.setPlaybackSpeed(s)
                                                speedOpen = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                    KvIconBtn(Icons.Filled.Lock, "锁定", size = 40) {
                        locked = true
                        showControls = false
                    }
                    KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = 40) {
                        landscape = !landscape
                        activity?.requestedOrientation = if (landscape)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            }
        }

        if (locked) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x66000000))
                    .clickable {
                        locked = false
                        showControls = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.LockOpen, "解锁", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        if (errorText != null) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (route.isLive) "这条线路播不了" else "这条线路播不了",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White
                )
                if (route.isLive) {
                    Spacer(Modifier.height(4.dp))
                    Text("直播线路多半是中继限流或临时 404，等 5 秒重试，或换个频道", fontSize = 11.sp, color = Kv.Ink3)
                }
                Spacer(Modifier.height(6.dp))
                Text(errorText!!, fontSize = 11.5.sp, color = Kv.Ink2)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "重试",
                        fontSize = 13.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                errorText = null
                                runCatching {
                                    exo.prepare()
                                    exo.play()
                                }
                            }
                            .padding(8.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "返回换个源",
                        fontSize = 13.sp,
                        color = Kv.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { vm.back() }.padding(8.dp)
                    )
                }
            }
        }
    }
}
