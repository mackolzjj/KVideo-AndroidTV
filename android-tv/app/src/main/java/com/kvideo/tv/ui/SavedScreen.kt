package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.SavedItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

@Composable
fun SavedScreen(vm: AppVm) {
    var tab by remember { mutableStateOf(0) }
    val list = if (tab == 0) vm.favorites else vm.history

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "片单")
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            KvChip("收藏 ${vm.favorites.size}", selected = tab == 0) { tab = 0 }
            KvChip("历史 ${vm.history.size}", selected = tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(10.dp))
        if (list.isEmpty()) {
            KvEmpty(if (tab == 0) "还没有收藏。详情页右上角那个 + 就是收藏。" else "还没有播放记录。")
            Spacer(Modifier.weight(1f))
            if (tab == 1 && vm.history.isNotEmpty()) {
                Text(
                    "清空历史",
                    fontSize = 12.sp,
                    color = Kv.Ink3,
                    modifier = Modifier
                        .clickable { vm.clearHistory() }
                        .padding(16.dp)
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(list) { s: SavedItem ->
                    Column(Modifier.clickable { vm.go(Route.Detail(s.id, s.sourceId)) }) {
                        PosterArt(s.poster, ratio = 1.5f, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(s.name, fontSize = 11.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp)
                        if (s.episodeName.isNotBlank()) {
                            val seen = if (s.positionMs > 1000) " · 看到 ${clock(s.positionMs)}" else ""
                            Text(s.episodeName + seen, style = Kv.Tiny.copy(fontSize = 10.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
