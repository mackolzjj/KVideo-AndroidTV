package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import com.kvideo.tv.data.ResultGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.VideoItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

@Composable
private fun BadgeRow(items: List<Pair<String, String>>, picked: Set<String>, onChange: (Set<String>) -> Unit) {
    if (items.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(bottom = 6.dp)
    ) {
        items(items, key = { it.first }) { (id, label) ->
            val on = id in picked
            KvChip(label, selected = on) {
                onChange(if (on) picked - id else picked + id)
            }
        }
    }
}

@Composable
fun SearchScreen(vm: AppVm, autofocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    var pick by remember { mutableStateOf<ResultGroup?>(null) }
    LaunchedEffect(Unit) { if (autofocus && vm.autoFocusSearch) runCatching { focus.requestFocus() } }

    // 防抖：以前每敲一个字就对全部源发一次并行搜索，结果还会串味
    LaunchedEffect(vm.query) {
        val q = vm.query.trim()
        if (q.isBlank()) {
            vm.search("")
            return@LaunchedEffect
        }
        if (q == vm.lastSearched) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        vm.search(q)
    }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.Surface)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, null, tint = Kv.Ink3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (vm.query.isEmpty()) {
                        Text("剧集、电影、频道", fontSize = 13.sp, color = Kv.Ink3)
                    }
                    BasicTextField(
                        value = vm.query,
                        onValueChange = { vm.query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Kv.Ink),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Kv.Red),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                }
                if (vm.query.isNotEmpty()) {
                    Text(
                        "清除",
                        fontSize = 11.5.sp,
                        color = Kv.Ink3,
                        modifier = Modifier.clickable { vm.search("") }
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "取消",
                fontSize = 13.sp,
                color = Kv.Ink2,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { vm.back() }
            )
        }

        if (vm.query.isBlank()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("试试搜片名，例如「庆余年」", style = Kv.Body)
            }
        } else if (vm.searching && vm.progressTotal > 0) {
            Text(
                "已搜 ${vm.progressDone}/${vm.progressTotal} 个源 · 找到 ${vm.allResults.size} 条",
                style = Kv.Tiny,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else if (vm.results.isEmpty()) {
            KvEmpty(
                when {
                    vm.sources.isEmpty() -> "没有可用片源：订阅没拉到（设置页「已连接」后面应该是 122 个源，不是 0）"
                    vm.allResults.isNotEmpty() -> "没有一条片名真的含「${vm.query.trim()}」。点下面「显示全部」能看到服务器返回的 ${vm.allResults.size} 条。"
                    else -> "没有结果。换个片名试试。"
                }
            )
        }

        // 上游那套：显示模式开关 + 源/类型/语言三排多选筛选，不做相关度过滤
        if (vm.allResults.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (vm.hiddenUnrelated > 0)
                        "找到 ${vm.allResults.size} 条 · 已挡掉 ${vm.hiddenUnrelated} 条不相干"
                    else "找到 ${vm.allResults.size} 条 · 当前 ${vm.results.size} 项",
                    style = Kv.Tiny
                )
                Spacer(Modifier.weight(1f))
                KvChip("只看相关", selected = vm.onlyRelevant) { vm.onlyRelevant = !vm.onlyRelevant }
                Spacer(Modifier.width(6.dp))
                KvChip("合并同名", selected = vm.grouped) { vm.grouped = !vm.grouped }
            }
            BadgeRow(
                items = vm.sourceBadges.map { it.first to it.second },
                picked = vm.pickedSources
            ) { vm.pickedSources = it }
            if (vm.typeBadges.isNotEmpty()) {
                BadgeRow(
                    items = vm.typeBadges.map { it to it },
                    picked = vm.pickedTypes
                ) { vm.pickedTypes = it }
            }
            if (vm.langBadges.isNotEmpty()) {
                BadgeRow(
                    items = vm.langBadges.map { it to it },
                    picked = vm.pickedLangs
                ) { vm.pickedLangs = it }
            }
        }

        val picked = pick
        if (picked != null) {
            val g = picked
            AlertDialog(
                onDismissRequest = { pick = null },
                title = { Text(g.title, style = Kv.H2) },
                text = {
                    Column {
                        Text("${g.items.size} 个源，按实测延迟排序", style = Kv.Tiny)
                        Spacer(Modifier.height(8.dp))
                        val ordered = g.items.sortedBy { vm.lineLatency[it.sourceId] ?: Long.MAX_VALUE }
                        ordered.forEach { item ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vm.openGroup(g, item)
                                        pick = null
                                    }
                                    .padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.sourceName, fontSize = 13.sp, color = Kv.Ink)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    vm.lineLatency[item.sourceId]?.let { "${it}ms" }
                                        ?: item.remarks.ifBlank { "测速中…" },
                                    style = Kv.Tiny
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = Kv.Ink2,
                        modifier = Modifier.clickable { pick = null }.padding(8.dp)
                    )
                },
                containerColor = Kv.Surface
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(vm.results) { g: ResultGroup ->
                // 空组的代表条目拿不到就没法点开，直接不画（正常数据流不会出现，属于防御）
                val only = g.items.firstOrNull()
                if (only != null) {
                Column(
                    Modifier.clickable {
                        if (g.items.size == 1) {
                            vm.openGroup(g, only)
                        } else {
                            pick = g
                            vm.pingGroup(g)
                        }
                    }
                ) {
                    PosterArt(g.poster, ratio = 1.5f, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        g.title,
                        fontSize = 11.sp,
                        color = Kv.Ink,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        lineHeight = 15.sp
                    )
                    Text(
                        if (g.items.size > 1) "${g.items.size} 个源 · 点选线路"
                        else listOfNotNull(
                            only.sourceName.ifBlank { null },
                            only.remarks.ifBlank { null }
                        ).joinToString(" · "),
                        style = Kv.Tiny.copy(fontSize = 10.sp),
                        maxLines = 1
                    )
                }
                }
            }
        }
    }
}
