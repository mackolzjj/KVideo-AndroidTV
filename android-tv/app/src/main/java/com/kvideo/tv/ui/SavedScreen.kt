package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.kvideo.tv.data.SavedItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}

/** 一部剧 + 它在几个源里出现过（同一部片换过源不再占两格） */
private data class SavedGroup(val head: SavedItem, val sources: Int)

private fun groupByTitle(list: List<SavedItem>): List<SavedGroup> =
    list.groupBy { it.name.trim().lowercase() }
        .values
        .mapNotNull { rows ->
            val head = rows.maxByOrNull { it.updatedAt } ?: rows.firstOrNull() ?: return@mapNotNull null
            SavedGroup(head, rows.size)
        }
        .sortedByDescending { it.head.updatedAt }

@Composable
fun SavedScreen(vm: AppVm) {
    var tab by remember { mutableStateOf(0) }
    val raw = if (tab == 0) vm.favorites else vm.history
    val groups = remember(raw, tab) { groupByTitle(raw) }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "片单")
        Row(Modifier.padding(horizontal = KvSpace.l), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            KvChip("收藏 ${if (tab == 0) groups.size else groupByTitle(vm.favorites).size}", selected = tab == 0) { tab = 0 }
            KvChip("历史 ${if (tab == 1) groups.size else groupByTitle(vm.history).size}", selected = tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(10.dp))
        if (raw.isEmpty()) {
            KvEmpty(if (tab == 0) "还没有收藏。详情页右上角那个 + 就是收藏。" else "还没有播放记录。")
            Spacer(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(groups) { g: SavedGroup ->
                    val s = g.head
                    Column(Modifier.clickable { vm.go(Route.Detail(s.id, s.sourceId)) }) {
                        Box {
                            PosterArt(s.poster, ratio = 1.5f, modifier = Modifier.fillMaxWidth())
                            if (g.sources > 1) {
                                Text(
                                    "${g.sources} 个源",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(KvSpace.xs)
                                        .clip(RoundedCornerShape(KvRadius.s))
                                        .background(Color(0xCC000000))
                                        .padding(horizontal = KvSpace.xs, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(s.name, fontSize = 11.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp)
                        if (s.episodeName.isNotBlank()) {
                            val seen = if (s.positionMs > 1000) " · 看到 ${clock(s.positionMs)}" else ""
                            Text(s.episodeName + seen, style = Kv.Tiny.copy(fontSize = 10.sp), maxLines = 1)
                        }
                    }
                }
            }
            // 清空历史：历史 tab 且有记录时一直在底部（之前错放进了「列表为空」的分支，
            // 结果这个按钮只在收藏为空时才会出现）
            if (tab == 1) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.clearHistory() }.padding(vertical = KvSpace.m),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("清空观看历史", fontSize = 12.sp, color = Kv.Ink3)
                }
            }
        }
    }
}
