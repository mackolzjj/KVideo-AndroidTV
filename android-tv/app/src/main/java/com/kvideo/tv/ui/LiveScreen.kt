package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.Channel
import com.kvideo.tv.vm.AppVm

@Composable
fun LiveScreen(vm: AppVm) {
    // 直播源完全由用户自己填（高级设置里），App 不再内置任何频道清单。
    // 地址没变时 loadChannels() 内部会跳过，避免每次切回来都清空重拉。
    LaunchedEffect(vm.liveUrl) {
        if (vm.liveUrl.isNotBlank()) vm.loadChannels()
    }
    // 频道加载完再拉节目单（EPG 地址是选填的，没填就什么都不做）
    LaunchedEffect(vm.channels.size, vm.epgUrl) {
        if (vm.channels.isNotEmpty() && vm.epgUrl.isNotBlank()) vm.loadEpg()
    }

    var group by remember { mutableStateOf("全部") }
    val groups = remember(vm.channels) {
        listOf("全部") + vm.channels.map { it.group }.distinct()
    }
    val shown = if (group == "全部") vm.channels else vm.channels.filter { it.group == group }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "直播")
        // 不能用 return@Column 提前返回（Compose 的 group 会不平衡，见 App.kt 里的注释）
        if (vm.liveUrl.isBlank()) {
            KvEmpty("还没填直播源。设置 → 连点「版本」7 次 → 高级设置里填 M3U 地址。")
        } else if (vm.channels.isEmpty()) {
            KvEmpty("没拿到频道。检查 M3U 地址是否可访问。")
        } else {
        // 上次看的台：直接给一个继续入口，不用在 800 个频道里翻回去
        val last = vm.channels.firstOrNull { it.url == vm.lastChannelUrl && it.url.isNotBlank() }
        if (last != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.Surface2)
                    .clickable { vm.playChannel(last) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("上次", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Kv.Red)
                Spacer(Modifier.width(8.dp))
                Text(
                    last.name,
                    fontSize = 13.sp,
                    color = Kv.Ink,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text("继续看 ›", fontSize = 11.5.sp, color = Kv.Ink3)
            }
            Spacer(Modifier.height(10.dp))
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(groups) { g ->
                KvChip(g, selected = g == group) { group = g }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(shown) { c: Channel ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface)
                        .clickable { vm.playChannel(c) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(2.dp))
                            .background(Kv.Red)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("LIVE", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 13.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, maxLines = 1)
                        val nowMs = System.currentTimeMillis()
                        val prog = vm.epg[c.tvgId.ifBlank { c.name }]
                            ?.firstOrNull { it.startMs <= nowMs && nowMs < it.stopMs }
                        if (prog != null) {
                            Text(
                                "正在播 · ${prog.title}",
                                style = Kv.Tiny.copy(fontSize = 10.sp),
                                maxLines = 1
                            )
                        }
                    }
                    Text(c.group, style = Kv.Tiny)
                }
            }
        }
        }
    }
}
