@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.kvideo.tv.data.Channel

/**
 * 播放页的「外壳」：顶栏、底栏、倍速弹层。
 *
 * 从 PlayerScreen.kt 里拆出来的，只做展示 + 回调，不碰播放器状态；
 * 这样播放页主体只剩「播放器 + 手势 + 状态」，改外观不用翻 900 行。
 */

/** 控制条尺寸：横竖屏只差这几个数 */
internal data class PlayerBarSpec(val icon: Int, val play: Int)

/** 顶栏：返回键贴画面左缘（有黑边时跟着内缩），标题居中；直播时返回右侧多一个「频道」按钮 */
@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String,
    startPad: Float,
    endPad: Float,
    topPad: Float,
    channelsAvailable: Boolean = false,
    channelsOpen: Boolean = false,
    onChannels: () -> Unit = {},
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent)))
            .padding(start = startPad.dp, end = endPad.dp, top = topPad.dp, bottom = 6.dp)
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            KvIconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 40) { onBack() }
        }
        if (channelsAvailable) {
            // 换台入口放右上角：左手拇指够不到也不影响，右上角不与返回键抢位置
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp)
                    .clip(RoundedCornerShape(KvRadius.s))
                    .then(
                        if (channelsOpen) Modifier.background(Kv.Red.copy(alpha = 0.22f))
                        else Modifier
                    )
                    .clickable { onChannels() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.LiveTv,
                    contentDescription = "频道",
                    tint = if (channelsOpen) Kv.Red else Kv.Ink,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 11.sp, color = Kv.Ink2, maxLines = 1)
            }
        }
    }
}

/**
 * 底栏：进度条 + 一排按钮。横竖屏共用同一段布局，只有尺寸不同（[PlayerBarSpec]）。
 * 进度条拖动只写 preview，松手才 seek —— 每动一像素 seek 一次会卡。
 */
@Composable
internal fun PlayerBottomBar(
    compact: Boolean,
    barDp: Float,
    position: Long,
    duration: Long,
    seekPreview: Long?,
    playing: Boolean,
    speed: Float,
    speedOpen: Boolean,
    hasEpisodes: Boolean,
    onReplay: (() -> Unit)? = null,
    onPreview: (Long?) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlay: () -> Unit,
    onEpisodes: () -> Unit,
    onToggleSpeed: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    onFullscreen: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = barDp.dp + KvSpace.m, end = barDp.dp + KvSpace.m, top = 40.dp, bottom = 10.dp)
    ) {
        Slider(
            value = if (duration > 0) {
                ((seekPreview ?: position).toFloat() / duration).coerceIn(0f, 1f)
            } else 0f,
            onValueChange = { v -> if (duration > 0) onPreview((v * duration).toLong()) },
            onValueChangeFinished = { onPreview(null) },
            colors = SliderDefaults.colors(
                thumbColor = Kv.Red,
                activeTrackColor = Kv.Red,
                inactiveTrackColor = Color(0x47FFFFFF)
            ),
            modifier = Modifier.fillMaxWidth().height(24.dp)
        )

        val bar = if (compact) PlayerBarSpec(icon = 34, play = 38) else PlayerBarSpec(icon = 42, play = 46)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = bar.icon) { onSeekBack() }
            KvIconBtn(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "暂停" else "播放",
                size = bar.play
            ) { onTogglePlay() }
            KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = bar.icon) { onSeekForward() }
            Spacer(Modifier.width(6.dp))
            Text(
                "${fmtTime(position)} / ${fmtTime(duration)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            if (onReplay != null) {
                // 直播专属：回看（catchup）
                KvIconBtn(Icons.Filled.History, "回看", size = bar.icon) { onReplay() }
            }
            if (hasEpisodes) {
                KvIconBtn(Icons.AutoMirrored.Filled.PlaylistPlay, "剧集", size = bar.icon) { onEpisodes() }
            }
            SpeedChip(speed, compact, speedOpen, { onToggleSpeed() }) { s -> onPickSpeed(s) }
            KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = bar.icon) { onFullscreen() }
        }
    }
}

/** 倍速按钮 + 弹出的倍速列表（横竖屏共用） */
@Composable
internal fun SpeedChip(
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
                .clip(RoundedCornerShape(KvRadius.s))
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
            .clip(RoundedCornerShape(KvRadius.s))
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
                    .padding(horizontal = KvSpace.m, vertical = 7.dp)
            )
        }
    }
}

/** mm:ss / h:mm:ss */
internal fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

/** 从台名里抽台号：CCTV-5 体育 → 5、CCTV-5+ → 5+、北京卫视 → 空 */
private fun channelNumber(n: String): String {
    val m = Regex("^CCTV[- ]?(\\d{1,2}\\+?)", RegexOption.IGNORE_CASE).find(n.trim())
    return m?.groupValues?.get(1) ?: ""
}

/** 横屏单列用的紧凑台名：CCTV-2 财经 → CCTV2 财经、CCTV-5+ 体育赛事 → CCTV5+ 体育赛事 */
private fun compactChannelName(n: String): String {
    val s = n.trim()
    val m = Regex("^CCTV[- ]?(\\d{1,2}\\+?)", RegexOption.IGNORE_CASE).find(s)
    if (m != null) {
        val rest = s.substring(m.range.last + 1).trim()
        return "CCTV" + m.groupValues[1] + if (rest.isNotBlank()) " $rest" else ""
    }
    return s
}

/**
 * 播放页内的换台抽屉（仅直播用）。
 *
 * 横屏 2 列、竖屏 3 列；每格两行：台名 + 正在播的节目（没有 EPG 就退回分组）。
 * 当前台用左侧 3dp 红条标记，不再整格红框。顶部分组默认落在当前台所在分组。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ChannelDrawerPanel(
    channels: List<Channel>,
    currentUrl: String,
    favUrls: Set<String>,
    isLandscape: Boolean,
    nowPlaying: (Channel) -> String = { "" },
    onToggleFav: (String) -> Unit = {},
    onLongPress: (Channel) -> Unit = {},
    onPick: (Channel) -> Unit,
    onClose: () -> Unit
) {
    if (channels.isEmpty()) return
    val ctx = LocalContext.current
    val cur = channels.firstOrNull { it.url == currentUrl }
    val groups = remember(channels, favUrls) {
        buildList {
            add("全部")
            if (favUrls.isNotEmpty()) add("收藏")
            addAll(channels.map { it.group.ifBlank { "其他" } }.distinct())
        }
    }
    var group by remember(cur?.group) {
        mutableStateOf(cur?.group?.takeIf { it.isNotBlank() } ?: "全部")
    }
    val shown = remember(channels, group, favUrls) {
        when (group) {
            "全部" -> channels
            "收藏" -> channels.filter { it.url in favUrls }
            else -> channels.filter { it.group.ifBlank { "其他" } == group }
        }.distinctBy { it.name to it.url }   // 清单里偶有同名同址的重复项，别在抽屉里出现两次
    }
    // 282 台光靠分组翻太慢：搜索按台名（含紧凑名）+ 台号过滤，纯数字时精确命中排最前
    var query by remember { mutableStateOf("") }
    val filtered = remember(shown, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            shown
        } else {
            val hits = shown.filter {
                it.name.contains(q, true) ||
                    compactChannelName(it.name).contains(q, true) ||
                    channelNumber(it.name) == q                 // 输入 5 → CCTV5
            }
            // 精确命中的（台号完全一致 / 台名以它开头）排前面，剩下的按原顺序
            hits.sortedWith(
                compareByDescending<Channel> { channelNumber(it.name) == q }
                    .thenByDescending { it.name.startsWith(q, true) }
            )
        }
    }
    // 打开时直接定位到正在播的那台（当前台在第 20 位以后时不用手滚）
    val gridState = rememberLazyGridState()
    LaunchedEffect(group, query, filtered.size) {
        if (query.isBlank()) {
            val idx = filtered.indexOfFirst { it.url == currentUrl }
            if (idx > 0) gridState.scrollToItem(idx)
        }
    }

    val swallow = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
    ) { }

    // 面板宽度跟着最长台名走：没填 EPG 时右侧不会有半屏空白，最长也就到 268dp
    val measurer = rememberTextMeasurer()
    val nameStyle = TextStyle(
        fontSize = if (isLandscape) 12.sp else 11.5.sp,
        fontWeight = FontWeight.Medium
    )
    val density = LocalDensity.current
    val panelWidth = remember(shown, isLandscape) {
        val widest = shown.maxOfOrNull { c ->
            measurer.measure(AnnotatedString(compactChannelName(c.name)), nameStyle).size.width
        } ?: 0
        val contentDp = with(density) { widest.toDp().value }
        val needSub = shown.any { nowPlaying(it).isNotBlank() }
        val needLogo = shown.any { it.logo.isNotBlank() }
        // 逐项加齐，别再用一个拍脑袋的常量：
        // 列表左右 padding 12*2 + 红条 3 + 台名右内距 9 + 台标 20+8+6 + 收藏星 13+10+6 + 余量 4
        var w = 24f + 3f + 9f + 4f + contentDp + 29f
        if (needLogo) w += 34f
        w += if (needSub) 74f else 44f   // 没有节目单时也要给「播放中」留位置
        w.dp.coerceIn(170.dp, 288.dp)
    }

    Box(Modifier.fillMaxSize()) {
        // 抽屉在右边，点左边剩下的空白处收起
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClose() }
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(panelWidth)
                .fillMaxHeight()
                .background(Color(0xF0151519))
                .then(swallow)
                .padding(vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("频道", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.width(7.dp))
                Text("共 ${channels.size} 台", fontSize = 10.5.sp, color = Kv.Ink3)
                Spacer(Modifier.weight(1f))
                Text(
                    "关闭",
                    fontSize = 11.sp,
                    color = Kv.Ink2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(KvRadius.s))
                        .clickable { onClose() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            // 搜索框：边打边过滤，清空回到当前分组
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0x14FFFFFF))
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text("搜台名 / CCTV1", fontSize = 11.sp, color = Kv.Ink3, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.5.sp, color = Color.White),
                    cursorBrush = SolidColor(Kv.Red),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(groups) { g -> KvChip(g, selected = g == group) { group = g } }
            }
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(14.dp)) {
                    Text("没找到「$query」", fontSize = 11.5.sp, color = Kv.Ink3)
                }
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                state = gridState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered) { c ->
                    val isCur = c.url == currentUrl
                    // 单行：左边台名，右边正在播的节目（没有 EPG 就空着，当前台显示「播放中」）
                    val epgTitle = nowPlaying(c)
                    val sub = when {
                        epgTitle.isNotBlank() -> epgTitle
                        isCur -> "播放中"
                        else -> ""
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isCur) Color(0x1FE50914) else Color(0x10FFFFFF))
                            .combinedClickable(
                                onClick = { onPick(c) },
                                onLongClick = { onLongPress(c) }
                            )
                            .padding(end = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 当前台：左侧 3dp 红条
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(36.dp)
                                .background(if (isCur) Kv.Red else Color.Transparent)
                        )
                        if (c.logo.isNotBlank()) {
                            // 外链台标：走 Coil 的磁盘/内存缓存，淡入避免一片片闪白
                            AsyncImage(
                                model = ImageRequest.Builder(ctx)
                                    .data(c.logo)
                                    .crossfade(150)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            // 竖屏也要保留后半截，否则 CCTV-4 中文国际 / 欧洲 全被压成同一个「C4」
                            compactChannelName(c.name),
                            fontSize = if (isLandscape) 12.sp else 11.5.sp,
                            fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCur) Kv.Red else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = if (c.logo.isNotBlank()) 6.dp else 9.dp, top = 10.dp, bottom = 10.dp)
                        )
                        if (sub.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                sub,
                                fontSize = 9.5.sp,
                                color = if (isCur) Kv.Red.copy(alpha = 0.85f) else Kv.Ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1.1f, fill = false)
                            )
                        }
                        val favNow = c.url in favUrls
                        Text(
                            if (favNow) "★" else "☆",
                            fontSize = 13.sp,
                            color = if (favNow) Kv.Red else Kv.Ink3,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { onToggleFav(c.url) }
                                .padding(horizontal = 5.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
