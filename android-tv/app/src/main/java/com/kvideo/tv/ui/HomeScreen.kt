package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun HomeScreen(vm: AppVm) {
    val recs = vm.recommend
    val hero = recs.firstOrNull()

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(
            onSearch = { vm.openSearchBox() },
            trailing = {
                KvIconBtn(Icons.Filled.Settings, "设置") { vm.go(Route.Settings) }
            }
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            HeroBlock(
                hero = hero,
                onPlay = { hero?.let { vm.searchTitle(it.title) } ?: vm.openSearchBox() },
                onSearch = { vm.openSearchBox() }
            )

            if (vm.history.isNotEmpty()) {
                SectionHeader("继续观看", count = "${vm.history.size} 部")
                KvRow(vm.history) { h: SavedItem ->
                    WideCard(
                        title = h.name,
                        subtitle = if (h.episodeName.isNotBlank()) h.episodeName else "上次看到这里",
                        poster = h.poster,
                        progress = 0.25f,
                        onClick = {
                            vm.go(Route.Detail(h.id, h.sourceId))
                        }
                    )
                }
            }

            SectionHeader("今日热播", action = "全部 ›") { vm.go(Route.Search) }
            if (recs.isEmpty()) {
                if (vm.busy) {
                    KvEmpty("正在从服务器取内容…")
                } else {
                    KvEmpty(vm.error ?: "服务器没返回内容。检查地址和访问密码。")
                }
            } else {
                KvRow(recs) { d: DoubanItem ->
                    PosterCard(d) { vm.searchTitle(d.title) }
                }
            }

            SectionHeader("正在直播", action = "去直播 ›") { vm.go(Route.Live) }
            if (vm.channels.isEmpty()) {
                KvEmpty("设置里填一下直播源（M3U 地址）就能看直播。")
            } else {
                KvRow(vm.channels.take(12)) { c ->
                    WideCard(
                        title = c.name,
                        subtitle = c.group,
                        poster = "",
                        badge = "LIVE",
                        onClick = { vm.playChannel(c) }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HeroBlock(hero: DoubanItem?, onPlay: () -> Unit, onSearch: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(268.dp).background(
            Brush.verticalGradient(
                listOf(Color(0xFF141A1D), Color(0xFF08090B))
            )
        )
    ) {
        if (hero != null) {
            coil.compose.AsyncImage(
                model = hero.cover,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.55f
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0.35f to Color.Transparent,
                    1f to Kv.Bg
                )
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
            Text("本周新上", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Kv.Red, letterSpacing = 2.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                hero?.title ?: "连接你的 KVideo",
                style = Kv.Title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (hero != null) listOfNotNull(hero.rate.takeIf { it.isNotBlank() }?.let { "豆瓣 $it" }, "随时可看").joinToString("  ·  ")
                else "设置里填服务器地址与访问密码",
                style = Kv.Meta
            )
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Kv.Ink)
                        .clickable(onClick = onSearch)
                        .padding(horizontal = 20.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.PlayArrow, null,
                            tint = Color.Black,
                            modifier = Modifier.size(19.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("播放", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                Spacer(Modifier.width(9.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x29FFFFFF))
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 15.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Search, null,
                            tint = Kv.Ink,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("搜片名", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Kv.Ink)
                    }
                }
            }
        }
    }
}
