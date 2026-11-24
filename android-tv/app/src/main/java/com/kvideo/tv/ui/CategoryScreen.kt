package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

/**
 * 分类浏览页（首页「电影 / 剧集 / 综艺 / 动漫 / 纪录片」入口）。
 * 数据就是 /api/douban/recommend 的 tag + type 组合，点卡片用片名去搜索出片源，
 * 和首页「今日热播」同一套行为，不多造接口。
 */
@Composable
fun CategoryScreen(vm: AppVm, route: Route.Category) {
    val state = rememberLazyGridState()

    // 滚到倒数第二行就自动加载下一页（和 premium 的「加载更多」同一个节奏，但不用点）
    val nearEnd by remember {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= vm.categoryItems.size - 4
        }
    }
    LaunchedEffect(nearEnd, vm.categoryItems.size) {
        if (nearEnd && vm.categoryItems.isNotEmpty() && !vm.categoryLoading) vm.loadMoreCategory()
    }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KvIconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 40) { vm.back() }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    route.kind,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Kv.Ink
                )
                Text(
                    if (vm.categoryItems.isEmpty()) "正在取片单…"
                    else "共 ${vm.categoryItems.size} 部 · 点海报用片名搜片源",
                    style = Kv.Tiny
                )
            }
            Text(
                if (vm.categoryLoading) "取片中…" else "换一批 ↻",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Kv.Ink2,
                modifier = Modifier
                    .clickable { if (!vm.categoryLoading) vm.refreshCategory() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(vm.categoryItems, key = { it.id }) { d: DoubanItem ->
                PosterCard(
                    item = d,
                    width = 104,
                    meta = if (d.rate.isNotBlank()) "豆瓣 ${d.rate}" else ""
                ) { vm.searchTitle(d.title) }
            }

            if (vm.categoryLoading) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        "加载中…",
                        style = Kv.Tiny,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)
                    )
                }
            } else if (vm.categoryItems.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    KvState(
                        title = "这个分类暂时没内容",
                        detail = "换个分类，或者点右上角「换一批」再试。",
                        actionLabel = "换一批",
                        onAction = { vm.refreshCategory() }
                    )
                }
            } else {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        "加载更多",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Kv.Ink3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.loadMoreCategory() }
                            .padding(vertical = 14.dp)
                    )
                }
            }
        }
    }
}
