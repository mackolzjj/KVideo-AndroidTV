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
    var group by remember { mutableStateOf("全部") }
    val groups = remember(vm.channels) {
        listOf("全部") + vm.channels.map { it.group }.distinct()
    }
    val shown = if (group == "全部") vm.channels else vm.channels.filter { it.group == group }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "直播")
        if (vm.liveUrl.isBlank()) {
            KvEmpty("设置里填一个 M3U 直播源地址，频道就会出现在这里。")
            return@Column
        }
        if (vm.channels.isEmpty()) {
            KvEmpty("没拿到频道。检查直播源地址，或点右上角刷新。")
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
                    Text(c.name, fontSize = 13.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text(c.group, style = Kv.Tiny)
                }
            }
        }
    }
}
