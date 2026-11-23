package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

@Composable
fun DetailScreen(vm: AppVm, route: Route.Detail) {
    val d = vm.detail
    Column(Modifier.fillMaxSize().background(Kv.Bg).verticalScroll(rememberScrollState())) {

        Box(Modifier.fillMaxWidth().height(236.dp)) {
            if (d != null && d.poster.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = d.poster,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    alpha = 0.5f,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.3f to Color.Transparent, 1f to Kv.Bg)
                )
            )
            KvIconBtn(Icons.Filled.ArrowBack, "返回", size = 44) { vm.back() }
        }

        Column(Modifier.offset(y = (-40).dp).padding(horizontal = 18.dp)) {
            if (vm.detailLoading) {
                Text("正在取详情…", style = Kv.Body)
            } else if (d == null) {
                Text("没取到详情", style = Kv.Title.copy(fontSize = 22.sp))
                Spacer(Modifier.height(8.dp))
                Text("这个源可能挂了，回搜索结果换一条线路。", style = Kv.Body)
            } else {
                Text(d.name, style = Kv.Title.copy(fontSize = 28.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(9.dp))
                Text(
                    listOfNotNull(
                        d.year.takeIf { it.isNotBlank() },
                        d.typeName.takeIf { it.isNotBlank() },
                        d.area.takeIf { it.isNotBlank() },
                        "共 ${d.episodes.size} 集"
                    ).joinToString("  ·  "),
                    style = Kv.Meta
                )
                // 换源：同一部片在别的资源站的线路（搜索里同名合并的那一组）
                val group = vm.currentGroup
                if (group != null && group.items.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        group.items.forEach { item ->
                            val on = item.sourceId == d.sourceId
                            KvChip(
                                item.sourceName.ifBlank { "源" },
                                selected = on
                            ) { if (!on) vm.switchSource(item) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Kv.Ink)
                            .clickable { vm.playDetail(0) },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("播放", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                    Spacer(Modifier.width(9.dp))
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Kv.Surface2)
                            .clickable {
                                vm.toggleFavorite(d.id, d.name, d.poster, d.sourceId)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add, "收藏",
                            tint = if (vm.isFav(d.id, d.sourceId)) Kv.Red else Kv.Ink,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (d.intro.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        d.intro.replace(Regex("<[^>]+>"), "").trim(),
                        style = Kv.Body,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(22.dp))
                Text("选集 · 共 ${d.episodes.size} 集", style = Kv.H2)
                Spacer(Modifier.height(10.dp))
                d.episodes.chunked(6).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, ep ->
                            val idx = d.episodes.indexOf(ep)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Kv.Surface)
                                    .clickable { vm.playDetail(idx) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    ep.name.take(6),
                                    fontSize = 11.5.sp,
                                    color = Kv.Ink,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                        }
                        repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
