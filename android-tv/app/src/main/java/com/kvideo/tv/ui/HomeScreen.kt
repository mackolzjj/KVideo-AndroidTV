package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.data.SavedItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

private fun mmss(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

@Composable
fun HomeScreen(vm: AppVm) {
    val recs = vm.recommend
    val heroItems = recs.take(3)
    val scroll = rememberScrollState()
    val scrolled = scroll.value > 14

    Box(Modifier.fillMaxSize().background(Kv.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {

            HeroPager(heroItems, onPlay = { t -> vm.searchTitle(t) })

            if (recs.isEmpty() && vm.busy) {
                HomeSkeleton()
            } else {
                if (recs.isEmpty()) {
                    Spacer(Modifier.height(14.dp))
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
                            val progress = if (h.durationMs > 0)
                                (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
                            val remain = if (h.durationMs > 0 && h.positionMs > 0)
                                "剩 " + mmss(h.durationMs - h.positionMs) else null
                            WideCard(
                                title = h.name,
                                subtitle = if (h.durationMs > 0 && h.positionMs > 0)
                                    "已看到 ${mmss(h.positionMs)} / ${mmss(h.durationMs)}"
                                else h.episodeName,
                                poster = h.poster,
                                cornerTag = h.episodeName.takeIf { it.isNotBlank() },
                                remainText = remain,
                                progress = progress,
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
                    // 两行六张：去掉「正在直播」后腾出来的高度拿来把它铺满
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
                                        meta = if (d.rate.isNotBlank()) "豆瓣 ${d.rate}" else "",
                                        isNew = false
                                    ) { vm.searchTitle(d.title) }
                                }
                            }
                        }
                }
            }
            Spacer(Modifier.height(26.dp))
        }

        // 顶栏：吸在顶部，滚动后压一层渐变，标题不再撞图
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

/** 半屏 hero：可横滑 3 张，角标 + 翻页圆点 */
@Composable
private fun HeroPager(items: List<DoubanItem>, onPlay: (String) -> Unit) {
    val pager = rememberPagerState(pageCount = { if (items.isEmpty()) 1 else items.size })
    Box(Modifier.fillMaxWidth().height(430.dp)) {
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
            return@Box
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val h = items[page]
            Box(Modifier.fillMaxSize()) {
                coil.compose.AsyncImage(
                    model = h.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(22.dp),
                    alpha = 0.32f
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x99000000),
                                0.26f to Color(0x00000000),
                                0.62f to Color(0x59000000),
                                1f to Kv.Bg
                            )
                        )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 34.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "TOP ${page + 1}",
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
                        fontSize = 30.sp,
                        lineHeight = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("随时可看 · 多源可选", style = Kv.Meta)
                    Spacer(Modifier.height(13.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "找同款",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0x33FFFFFF))
                                .clickable { onPlay(h.title) }
                                .padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            repeat(items.size) { i ->
                val on = i == pager.currentPage
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

/** 首屏骨架屏：接口没回来之前先占位，不再是一片空白 */
@Composable
private fun HomeSkeleton() {
    Column {
        Box(Modifier.fillMaxWidth().height(200.dp).shimmer())
        Spacer(Modifier.height(18.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(2) {
                Box(Modifier.width(200.dp).height(112.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            repeat(3) {
                Box(Modifier.width(104.dp).height(156.dp).clip(RoundedCornerShape(4.dp)).shimmer())
            }
        }
    }
}

/** 基础 shimmer：一条平移的高光，不引第三方库 */
@Composable
private fun Modifier.shimmer(): Modifier {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 800f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1400),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFF14171A), Color(0xFF1E2227), Color(0xFF14171A)),
        start = androidx.compose.ui.geometry.Offset(x, 0f),
        end = androidx.compose.ui.geometry.Offset(x + 400f, 400f)
    )
    return this.background(brush)
}
