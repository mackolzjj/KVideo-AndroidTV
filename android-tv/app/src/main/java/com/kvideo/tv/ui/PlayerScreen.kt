package com.kvideo.tv.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import com.kvideo.tv.MainActivity
import com.kvideo.tv.data.Net
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

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
    var speed by remember { mutableStateOf(vm.playSpeed) }
    var speedOpen by remember { mutableStateOf(false) }
    // 长按 2 倍速：按住时临时改播放速度，松手回落到原速度
    var holdFast by remember { mutableStateOf(false) }
    var speedBeforeHold by remember { mutableStateOf(1f) }
    var longFired by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var epDialog by remember { mutableStateOf(false) }
    var brightness by remember {
        val w = activity?.window
        mutableStateOf(w?.attributes?.screenBrightness?.takeIf { it > 0f } ?: 0.5f)
    }
    var volumePct by remember { mutableStateOf(0) }
    var hudText by remember { mutableStateOf<String?>(null) }
    // 亮度/音量改用贴在对应半屏的竖条 HUD："" 无 · "bright" 亮度 · "vol" 音量
    var hudKind by remember { mutableStateOf("") }
    var seekPreview by remember { mutableStateOf<Long?>(null) }
    var doubleHint by remember { mutableStateOf<String?>(null) }
    var lockChipVisible by remember { mutableStateOf(false) }
    // 播完自动下一集：倒计时秒数（-1 = 不显示）、下一集
    var autoNextSec by remember(route.url) { mutableStateOf(-1) }
    var autoNextEp by remember(route.url) { mutableStateOf<com.kvideo.tv.data.Episode?>(null) }

    LaunchedEffect(doubleHint) {
        if (doubleHint != null) {
            delay(600)
            doubleHint = null
        }
    }

    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(900)
            hudText = null
        }
    }

    LaunchedEffect(hudKind) {
        if (hudKind.isNotEmpty()) {
            delay(900)
            hudKind = ""
        }
    }

    // 线路顺序完全照上游：直播 = 代理(带 UA/Referer) → 直连（route.fallbacks）；
    // 点播 = 直连 → 代理（proxyMode=auto）。任何一条出错就换下一条，都试完才报错。
    var viaProxy by remember { mutableStateOf(vm.proxyMode == 2) }
    var fbIndex by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }

    /** 换到某一集：有 detail 走 switchEpisode，没有（从收藏/历史进的）走 switchEpisodeRaw */
    fun goEpisode(ep: com.kvideo.tv.data.Episode) {
        autoNextSec = -1
        autoNextEp = null
        val det = vm.detail
        if (det != null && det.id == route.videoId) {
            vm.switchEpisode(route.videoId, route.sourceId, ep, det, route.title)
        } else {
            vm.switchEpisodeRaw(route, ep)
        }
    }

    /** 播完时判断有没有下一集：有就起 5 秒倒计时（直播/电影/最后一集不触发） */
    fun armAutoNext() {
        val eps = route.episodes
        if (eps.isEmpty()) return
        val i = eps.indexOfFirst { it.index == route.epIndex }
        if (i < 0 || i >= eps.size - 1) return
        autoNextEp = eps[i + 1]
        autoNextSec = 5
    }

    fun play(rawUrl: String) {
        val item = MediaItem.Builder()
            .setUri(rawUrl)
            .apply { route.mime?.let { setMimeType(it) } }
            .build()
        try {
            // 换集时先把上一条的缓冲/轨道清干净，否则字幕/音轨状态会串到下一集
            exo.stop()
            exo.clearMediaItems()
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
        } catch (t: Throwable) {
            // 别再静默吞掉：换集失败要能看见原因
            errorText = "播放器异常 · " + (t.message ?: t.javaClass.simpleName)
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
        // 直播：这个频道的线路全挂了就自动换下一个台（最多 3 次）
        if (route.isLive && route.liveTries < 3) {
            val before = route.url
            vm.playNextLive(route.url, route.liveTries + 1)
            if (vm.route !== route && (vm.route as? Route.Player)?.url != before) return
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
                if (state == Player.STATE_READY) {
                    ready = true
                    // 记住的倍速要在这里应用：只改 state 不会作用到已经 prepare 的 exo
                    exo.setPlaybackSpeed(speed)
                }
                if (state == Player.STATE_ENDED) {
                    playing = false
                    armAutoNext()
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val cause = error.cause?.message?.take(120).orEmpty()
                nextOrFail(error.errorCodeName + if (cause.isNotBlank()) " · " + cause else "")
            }
        }
        exo.addListener(listener)
        onDispose {
            runCatching { exo.removeListener(listener) }
            // 注意：这里绝对不能 release()。换集走的是同一个 exo 实例，
            // 上一版在这里 release 掉了，下一集 setMediaItem 全被 runCatching 吞掉，
            // 表现就是「切到第 2 集卡死」。
        }
    }

    // 播放器实例与播放页同生共死：只有离开播放页才释放
    DisposableEffect(exo) {
        onDispose {
            runCatching { exo.stop() }
            runCatching { exo.release() }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 加载超时：上游 IPTVPlayer 有 LOADING_TIMEOUT_MS，卡住就换线路而不是一直黑屏
    LaunchedEffect(fbIndex, viaProxy, route.url) {
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

    var resumed by remember { mutableStateOf(false) }

    // 换集或重播当前集时 route.url 会变（同集重播 startMs 也变），续播标记要重置
    LaunchedEffect(route.url, route.startMs) { resumed = false }

    LaunchedEffect(Unit) {
        var lastSave = 0L
        while (true) {
            runCatching {
                position = exo.currentPosition
                duration = exo.duration.coerceAtLeast(0L)
                // 续播：媒体准备好后跳到上次的位置（只跳一次）
                if (!resumed && route.startMs > 0 && duration > 0) {
                    resumed = true
                    if (route.startMs < duration - 5_000) exo.seekTo(route.startMs)
                }
                // 每 10 秒把进度写回历史
                val now = System.currentTimeMillis()
                if (position > 3_000 && now - lastSave > 10_000) {
                    lastSave = now
                    vm.saveProgress(route, position, duration)
                }
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

    // 自动下一集倒计时：每秒 -1，到 0 就换集
    LaunchedEffect(autoNextSec) {
        val sec = autoNextSec
        if (sec < 0) return@LaunchedEffect
        if (sec == 0) {
            autoNextEp?.let { goEpisode(it) }
            return@LaunchedEffect
        }
        delay(1000)
        autoNextSec = sec - 1
    }

    // 画中画：进 PiP 时收起控制层，回到前台再恢复
    val inPip by MainActivity.inPipState
    LaunchedEffect(inPip) {
        if (inPip) {
            showControls = false
            speedOpen = false
        }
    }

    // 播放中才允许离开 App 时自动缩成画中画
    DisposableEffect(playing) {
        MainActivity.canEnterPip = playing
        onDispose { MainActivity.canEnterPip = false }
    }

    LaunchedEffect(locked, lockChipVisible) {
        if (locked && lockChipVisible) {
            delay(2500)
            lockChipVisible = false
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
                .pointerInput(locked) {
                    detectTapGestures(
                        // 长按 480ms = 2 倍速，松手回落。withTimeoutOrNull 返回 null = 时间到了还按着。
                        onPress = {
                            if (!locked) {
                                val released = withTimeoutOrNull(480L) { tryAwaitRelease() }
                                if (released == null) {
                                    speedBeforeHold = speed
                                    exo.setPlaybackSpeed(2f)
                                    holdFast = true
                                    longFired = true
                                    showControls = true
                                    tryAwaitRelease()
                                    exo.setPlaybackSpeed(speedBeforeHold)
                                    holdFast = false
                                }
                            }
                        },
                        onTap = {
                            // 长按松手后系统还会补一个 tap，这里吞掉，避免控制条又闪一下
                            if (longFired) {
                                longFired = false
                            } else if (locked) {
                                lockChipVisible = true
                            } else {
                                showControls = !showControls
                            }
                        },
                        onDoubleTap = { off ->
                            if (!locked) {
                                if (off.x < size.width / 2f) {
                                    exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                                    doubleHint = "« 10s"
                                } else {
                                    exo.seekTo(exo.currentPosition + 10_000)
                                    doubleHint = "10s »"
                                }
                                showControls = true
                            }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var startX = 0f
                    var mode = 0 // 0 未定 · 1 横滑进度 · 2 亮度 · 3 音量
                    var baseSeek = 0L
                    var lastSeekAt = 0L
                    detectDragGestures(
                        onDragStart = { p ->
                            startX = p.x
                            mode = 0
                            baseSeek = exo.currentPosition
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            if (mode == 0) {
                                mode = if (abs(drag.x) > abs(drag.y)) 1 else if (startX < size.width / 2f) 2 else 3
                            }
                            when (mode) {
                                1 -> {
                                    // 跟手：位移实时累加到预览位置，整屏宽 ≈ ±3 分钟
                                    val max = if (duration > 0) duration else Long.MAX_VALUE
                                    val target = ((seekPreview ?: baseSeek) +
                                        ((drag.x / size.width) * 180_000f).toLong())
                                        .coerceIn(0L, max)
                                    seekPreview = target
                                    position = target
                                    hudText = "${fmt(target)} / ${fmt(duration)}"
                                    // 边拖边真的跳，画面跟着手指走（约 8 帧节流）
                                    val now = System.currentTimeMillis()
                                    if (now - lastSeekAt > 120) {
                                        lastSeekAt = now
                                        exo.seekTo(target)
                                    }
                                }
                                2 -> {
                                    // 上滑变亮、下滑变暗（之前符号写反了）
                                    brightness = (brightness - drag.y / size.height * 1.6f).coerceIn(0.05f, 1f)
                                    applyBrightness(activity, brightness)
                                    hudKind = "bright"
                                }
                                else -> {
                                    val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                    val next = (cur - drag.y / size.height * max * 1.6f).toInt().coerceIn(0, max)
                                    if (next != cur) {
                                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0)
                                    }
                                    volumePct = if (max > 0) next * 100 / max else 0
                                    hudKind = "vol"
                                }
                            }
                            showControls = true
                        },
                        onDragEnd = {
                            if (mode == 1) {
                                seekPreview?.let { exo.seekTo(it) }
                                position = seekPreview ?: position
                            }
                            seekPreview = null
                            hudText = null
                            hudKind = ""
                        }
                    )
                }
        )

        if (!playing && !locked && !inPip) {
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

        val visible = (showControls || !playing) && !inPip
        if (visible && !locked) {
            // 顶栏
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent)))
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KvIconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 40) { vm.back() }
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
                    value = if (duration > 0) {
                        ((seekPreview ?: position).toFloat() / duration).coerceIn(0f, 1f)
                    } else 0f,
                    // 拖动时只更新预览，松手才真 seek —— 之前每动一个像素 seek 一次，卡
                    onValueChange = { v ->
                        if (duration > 0) seekPreview = (v * duration).toLong()
                    },
                    onValueChangeFinished = {
                        seekPreview?.let {
                            exo.seekTo(it)
                            position = it
                        }
                        seekPreview = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Kv.Red,
                        activeTrackColor = Kv.Red,
                        inactiveTrackColor = Color(0x47FFFFFF)
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
                )

                // 按钮组：横竖屏都是单行。锁已移到右缘中部，底部只留播放相关 + 剧集/倍速/全屏
                if (compact) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = 34) {
                            exo.seekTo((position - 10_000).coerceAtLeast(0))
                        }
                        KvIconBtn(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放",
                            size = 38
                        ) {
                            if (playing) exo.pause() else exo.play()
                            playing = !playing
                        }
                        KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = 34) {
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
                            KvIconBtn(Icons.AutoMirrored.Filled.PlaylistPlay, "剧集", size = 34) { epDialog = true }
                        }
                        SpeedChip(speed, true, speedOpen, { speedOpen = !speedOpen }) { s ->
                            speed = s
                            vm.playSpeed = s
                            exo.setPlaybackSpeed(s)
                            speedOpen = false
                        }
                        KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = 34) {
                            activity?.requestedOrientation = if (isLandscape)
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
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
                            KvIconBtn(Icons.AutoMirrored.Filled.PlaylistPlay, "剧集", size = 42) { epDialog = true }
                        }
                        SpeedChip(speed, false, speedOpen, { speedOpen = !speedOpen }) { s ->
                            speed = s
                            vm.playSpeed = s
                            exo.setPlaybackSpeed(s)
                            speedOpen = false
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

        hudText?.let { text ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        // 亮度 / 音量：竖条 + 数值，贴在各自那半屏，跟手指在哪边一致
        if (hudKind.isNotEmpty()) {
            val isBright = hudKind == "bright"
            val pct = if (isBright) (brightness * 100).toInt() else volumePct
            Box(
                Modifier
                    .align(if (isBright) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 34.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xB3000000))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 6.dp, height = 74.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x4DFFFFFF))
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(pct.coerceIn(0, 100) / 100f)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$pct%",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isBright) "亮度" else "音量",
                        fontSize = 10.sp,
                        color = Color(0xB3FFFFFF)
                    )
                }
            }
        }

        doubleHint?.let { text ->
            Box(
                Modifier
                    .align(if (text.startsWith("«")) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 40.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }

        if (autoNextSec >= 0) {
            val ep = autoNextEp
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 92.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6000000))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        if (ep != null) "${autoNextSec} 秒后播放 ${ep.name}" else "${autoNextSec} 秒后播放下一集",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "取消",
                            fontSize = 12.sp,
                            color = Kv.Ink2,
                            modifier = Modifier.clickable {
                                autoNextSec = -1
                                autoNextEp = null
                            }
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(
                            "立即播放",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.clickable { ep?.let { goEpisode(it) } }
                        )
                    }
                }
            }
        }

        if (holdFast) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    "2.0X 快放中",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // 锁定键固定在左缘中部（用户左手操作，这里最好按）：
        // 未锁时跟控制层一起显隐，锁定时同位置浮出实心锁 2.5 秒后淡掉。
        val lockEdgeVisible = (visible && !inPip && hudKind.isEmpty()) || (locked && lockChipVisible)
        if (lockEdgeVisible) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .size(if (locked) 40.dp else 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(if (locked) 0xB3000000 else 0x66000000))
                    .clickable {
                        if (locked) {
                            locked = false
                            lockChipVisible = false
                            showControls = true
                        } else {
                            locked = true
                            lockChipVisible = true
                            showControls = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    if (locked) "解锁" else "锁定",
                    tint = Color.White.copy(alpha = if (locked) 1f else 0.55f),
                    modifier = Modifier.size(if (locked) 19.dp else 17.dp)
                )
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
                                        if (det != null && det.id == route.videoId) {
                                            vm.switchEpisode(
                                                route.videoId, route.sourceId, ep, det, route.title
                                            )
                                        } else {
                                            // 从收藏/历史直接进的播放页没有 detail，也要能换
                                            vm.switchEpisodeRaw(route, ep)
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

        val err = errorText
        if (err != null) {
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
                Text(err, fontSize = 11.5.sp, color = Kv.Ink2)
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

/** 倍速按钮 + 弹出的倍速列表（横竖屏共用） */
@Composable
private fun SpeedChip(
    speed: Float,
    compact: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (Float) -> Unit
) {
    Box {
        Text(
            if (compact) "${"%.1f".format(speed)}X" else "倍速 ${"%.1f".format(speed)}X",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        if (open) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = (-42).dp)
            ) {
                SpeedList(speed = speed, onPick = onPick)
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

/** 亮度写进窗口属性（不需要 WRITE_SETTINGS 权限），离开播放页不还原属于预期行为 */
private fun applyBrightness(activity: android.app.Activity?, v: Float) {
    val w = activity?.window ?: return
    val lp = w.attributes
    lp.screenBrightness = v
    w.attributes = lp
}
