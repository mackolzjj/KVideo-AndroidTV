package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.data.SavedItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

/**
 * 首页。
 *
 * 注意：这里刻意不碰三样东西 —— HorizontalPager（实验 API）、Modifier.blur（RenderEffect）、
 * 无限动画骨架屏。2.0.37/2.0.38 就是这三样凑在一起导致「打开即闪退」，
 * 现在横滑用 LazyRow + snap、背景只用渐变、骨架屏是静态灰块。
 */
@Composable
fun HomeScreen(vm: AppVm) {
    val recs = vm.recommend
    val heroItems = recs.take(3)
    val scroll = rememberScrollState()
    // 直接用 scroll.value 判断会在每个滚动像素触发整页重组，用 derivedStateOf 只在跨阈值时变
    val scrolled by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf { scroll.value > 14 }
    }

    Box(Modifier.fillMaxSize().background(Kv.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {

            HeroRow(heroItems) { title -> vm.searchTitle(title) }

            if (recs.isEmpty() && vm.busy) {
                StaticSkeleton()
            } else {
                if (recs.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    KvEmpty(vm.error ?: "服务器没返回内容。检查地址和访问密码。")
                }

                if (vm.history.isNotEmpty()) {
                    SectionHeader("继续观看", count = "${vm.history.size} 部", action = "全部 ›") {
                        vm.go(Route.Saved)
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(vm.history) { h: SavedItem ->
                            val done = h.durationMs > 0 && h.positionMs > 0
                            WideCard(
                                title = h.name,
                                subtitle = if (done) "已看到 ${clock(h.positionMs)} / ${clock(h.durationMs)}"
                                else h.episodeName,
                                poster = h.poster,
                                cornerTag = h.episodeName.takeIf { it.isNotBlank() },
                                remainText = if (done && h.durationMs > h.positionMs)
                                    "剩 " + clock(h.durationMs - h.positionMs) else null,
                                progress = if (h.durationMs > 0)
                                    (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f,
                                width = 224,
                                onClick = { vm.go(Route.Detail(h.id, h.sourceId)) }
                            )
                        }
                    }
                }

                if (recs.isNotEmpty()) {
                    SectionHeader(
                        "今日热播",
                        action = if (vm.refreshingHome) "换一批中…" else "换一批 ↻"
                    ) { vm.refreshRecommend() }

                    listOf(recs.take(3), recs.drop(3).take(3))
                        .filter { it.isNotEmpty() }
                        .forEach { row ->
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                items(row) { d: DoubanItem ->
                                    PosterCard(
                                        item = d,
                                        width = 104,
                                        meta = if (d.rate.isNotBlank()) "豆瓣 ${d.rate}" else ""
                                    ) { vm.searchTitle(d.title) }
                                }
                            }
                        }
                }
            }
            Spacer(Modifier.height(26.dp))
        }

        // 顶栏：吸在顶部，滚动后压一层渐变
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (scrolled) Color(0xF208090B) else Color(0x66000000),
                            if (scrolled) Color(0xD908090B) else Color(0x22000000),
                            Color.Transparent
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Kv.Red))
            Spacer(Modifier.width(8.dp))
            Text("KVideo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-0.4).sp)
            Spacer(Modifier.width(14.dp))
            Row(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x26FFFFFF))
                    .clickable { vm.openSearchBox() }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = Color(0xCCFFFFFF), modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
                Text("搜片名、演员", fontSize = 12.sp, color = Color(0x99FFFFFF))
            }
            Spacer(Modifier.width(4.dp))
            KvIconBtn(Icons.Filled.Settings, "设置") { vm.go(Route.Settings) }
        }
    }
}

/** 半屏 hero：LazyRow 横滑（不用实验版 Pager），空数据走单独分支，不用 early return */
@Composable
private fun HeroRow(items: List<DoubanItem>, onPlay: (String) -> Unit) {
    val state = rememberLazyListState()

    Box(Modifier.fillMaxWidth().height(380.dp)) {
        if (items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF141A1D), Kv.Bg))
                )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text("连接你的 KVideo", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("设置里填服务器地址与访问密码", style = Kv.Meta)
            }
        } else {
            LazyRow(state = state, modifier = Modifier.fillMaxSize()) {
                items(items) { h ->
                    Box(Modifier.fillParentMaxWidth().fillParentMaxHeight()) {
                        coil.compose.AsyncImage(
                            model = h.cover,
                            contentDescription = null,
                            placeholder = ColorPainter(Color(0xFF1B1D21)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            alpha = 0.34f
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color(0x99000000),
                                    0.26f to Color(0x00000000),
                                    0.60f to Color(0x66000000),
                                    1f to Kv.Bg
                                )
                            )
                        )
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 18.dp, end = 18.dp, bottom = 34.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "推荐",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Kv.Red)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                if (h.rate.isNotBlank()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "豆瓣 ${h.rate}",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(Color(0x33FFFFFF))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                h.title,
                                fontSize = 28.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("随时可看 · 多源可选", style = Kv.Meta)
                            Spacer(Modifier.height(13.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White)
                                    .clickable { onPlay(h.title) }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("播放", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                repeat(items.size) { i ->
                    val on = i == state.firstVisibleItemIndex
                    Box(
                        Modifier
                            .height(5.dp)
                            .width(if (on) 16.dp else 5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (on) Color.White else Color(0x42FFFFFF))
                    )
                }
            }
        }
    }
}

/** 静态骨架屏：只用灰块，不做动画（动画也是上一版闪退的嫌疑之一） */
@Composable
private fun StaticSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().height(110.dp).background(Color(0xFF14171A)))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) {
                Box(Modifier.width(200.dp).height(112.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF14171A)))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            repeat(3) {
                Box(Modifier.width(104.dp).height(156.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF14171A)))
            }
        }
    }
}
