package com.kvideo.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Subtitles
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    // 方向以系统实际方向为准（之前用本地 boolean，横转竖会错乱）
    val isLandscape = cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // 竖屏窄屏：右侧按钮只留图标，保证「全屏」不被挤出屏幕
    val compact = cfg.screenWidthDp < 480
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
    var errorText by remember { mutableStateOf<String?>(null) }
    var epDialog by remember { mutableStateOf(false) }
    var subOff by remember { mutableStateOf(false) }

    fun toggleSub() {
        subOff = !subOff
        runCatching {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, subOff)
                .build()
        }
    }

    // 线路顺序完全照上游：直播 = 代理(带 UA/Referer) → 直连（route.fallbacks）；
    // 点播 = 直连 → 代理（proxyMode=auto）。任何一条出错就换下一条，都试完才报错。
    var viaProxy by remember { mutableStateOf(vm.proxyMode == 2) }
    var fbIndex by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    fun play(rawUrl: String) {
        val item = MediaItem.Builder()
            .setUri(rawUrl)
            .apply { route.mime?.let { setMimeType(it) } }
            .build()
        runCatching {
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    fun proxyUrlOf(raw: String): String =
        Net.abs("/api/iptv/stream?url=" + java.net.URLEncoder.encode(raw, "UTF-8"))

    fun nextOrFail(reason: String) {
        if (fbIndex < route.fallbacks.size) {
            val next = route.fallbacks[fbIndex]
            fbIndex++
            ready = false
            play(next)
            return
        }
        if (!route.isLive && vm.proxyMode == 0 && !viaProxy) {
            viaProxy = true
            ready = false
            play(proxyUrlOf(route.url))
            return
        }
        errorText = reason
    }

    // key 用 URL：换集时重新准备播放器（exo 实例本身仍复用）
    DisposableEffect(route.url) {
        fbIndex = 0
        viaProxy = vm.proxyMode == 2
        errorText = null
        play(if (viaProxy) proxyUrlOf(route.url) else route.url)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = exo.duration.coerceAtLeast(0L)
                if (state == Player.STATE_READY) ready = true
                if (state == Player.STATE_ENDED) playing = false
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val cause = error.cause?.message?.take(120).orEmpty()
                nextOrFail(error.errorCodeName + if (cause.isNotBlank()) " · " + cause else "")
            }
        }
        exo.addListener(listener)
        onDispose {
            runCatching { exo.removeListener(listener) }
            exo.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 加载超时：上游 IPTVPlayer 有 LOADING_TIMEOUT_MS，卡住就换线路而不是一直黑屏
    LaunchedEffect(fbIndex, viaProxy) {
        ready = false
        delay(20_000)
        if (!ready && errorText == null) {
            if (route.isLive) {
                nextOrFail("加载超时（20s）· 这条线路可能已失效或正被限流")
            } else {
                nextOrFail("加载超时（20s）")
            }
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

    // 播放页=沉浸式：藏掉状态栏/导航栏，滑一下才临时出现；离开播放页恢复
    DisposableEffect(Unit) {
        val win = activity?.window
        val controller = win?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (win != null) WindowCompat.setDecorFitsSystemWindows(win, false)
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (win != null) WindowCompat.setDecorFitsSystemWindows(win, true)
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

                if (compact) {
                    // 竖屏窄屏：拆成两行。上面一行放功能键，下面一行只有 播放组 + 全屏，
                    // 全屏键永远落在屏内（之前一行塞满 9 个控件，它被挤到屏幕外点不到）。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (route.episodes.isNotEmpty()) {
                            KvIconBtn(Icons.Filled.PlaylistPlay, "剧集", size = 36) { epDialog = true }
                        }
                        KvIconBtn(Icons.Filled.Subtitles, "字幕", size = 36) { toggleSub() }
                        Text(
                            "${"%.1f".format(speed)}X",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { speedOpen = !speedOpen }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        KvIconBtn(Icons.Filled.Lock, "锁定", size = 36) {
                            locked = true
                            showControls = false
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${fmt(position)}/${fmt(duration)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = 40) {
                            exo.seekTo((position - 10_000).coerceAtLeast(0))
                        }
                        KvIconBtn(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放",
                            size = 44
                        ) {
                            if (playing) exo.pause() else exo.play()
                            playing = !playing
                        }
                        KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = 40) {
                            exo.seekTo(
                                (position + 10_000).coerceAtMost(
                                    if (duration > 0) duration else position + 10_000
                                )
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = 44) {
                            activity?.requestedOrientation = if (isLandscape)
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    }
                    if (speedOpen) {
                        SpeedList(
                            speed = speed,
                            onPick = { s ->
                                speed = s
                                exo.setPlaybackSpeed(s)
                                speedOpen = false
                            }
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = 42) {
                            exo.seekTo((position - 10_000).coerceAtLeast(0))
                        }
                        KvIconBtn(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放",
                            size = 46
                        ) {
                            if (playing) exo.pause() else exo.play()
                            playing = !playing
                        }
                        KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = 42) {
                            exo.seekTo(
                                (position + 10_000).coerceAtMost(
                                    if (duration > 0) duration else position + 10_000
                                )
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${fmt(position)} / ${fmt(duration)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                        Spacer(Modifier.weight(1f))
                        if (route.episodes.isNotEmpty()) {
                            KvIconBtn(Icons.Filled.PlaylistPlay, "剧集", size = 42) { epDialog = true }
                        }
                        KvIconBtn(Icons.Filled.Subtitles, "字幕", size = 42) { toggleSub() }
                        Box {
                            Text(
                                "倍速 ${"%.1f".format(speed)}X",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { speedOpen = !speedOpen }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            )
                            if (speedOpen) {
                                Column(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(y = (-42).dp)
                                ) {
                                    SpeedList(
                                        speed = speed,
                                        onPick = { s ->
                                            speed = s
                                            exo.setPlaybackSpeed(s)
                                            speedOpen = false
                                        }
                                    )
                                }
                            }
                        }
                        KvIconBtn(Icons.Filled.Lock, "锁定", size = 40) {
                            locked = true
                            showControls = false
                        }
                        KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = 40) {
                            activity?.requestedOrientation = if (isLandscape)
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
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

        if (epDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { epDialog = false },
                title = { Text(route.title, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp)
                    ) {
                        items(route.episodes) { ep ->
                            Text(
                                ep.name,
                                fontSize = 13.sp,
                                color = if (ep.index == route.epIndex) Kv.Red else Color.White,
                                fontWeight = if (ep.index == route.epIndex) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        epDialog = false
                                        val det = vm.detail
                                        if (det != null) {
                                            vm.switchEpisode(
                                                route.videoId, route.sourceId, ep, det, route.title
                                            )
                                        }
                                    }
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Text(
                        "关闭",
                        fontSize = 13.sp,
                        color = Kv.Ink2,
                        modifier = Modifier.clickable { epDialog = false }.padding(8.dp)
                    )
                },
                containerColor = Kv.Surface
            )
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

/** 倍速列表：竖屏弹在控制条上方，横屏弹在按钮上方 */
@Composable
private fun SpeedList(speed: Float, onPick: (Float) -> Unit) {
    Column(
        Modifier
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
                    .clickable { onPick(s) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}
