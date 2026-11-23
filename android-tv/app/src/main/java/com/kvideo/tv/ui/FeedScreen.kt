package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.vm.AppVm

/**
 * 热播列表页：首页「全部 ›」的去处。
 * 电影/剧集 × 热门/最新/经典，接口按 page_start 分页，滚到底自动续 20 部。
 */
@Composable
fun FeedScreen(vm: AppVm) {
    val grid = rememberLazyGridState()

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (last >= vm.feedItems.size - 6) vm.loadMoreFeed()
            }
    }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KvIconBtn(Icons.Filled.ArrowBack, "返回", size = 40) { vm.back() }
            Text("热播", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Kv.Ink)
            Spacer(Modifier.weight(1f))
            Text(
                if (vm.feedLoading) "加载中…" else "下拉换一批",
                fontSize = 11.5.sp,
                color = Kv.Ink3,
                modifier = Modifier.padding(end = 12.dp)
            )
        }

        // 类型
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            listOf("movie" to "电影", "tv" to "剧集").forEach { (v, label) ->
                KvChip(label, selected = vm.feedType == v) {
                    vm.setFeedFilter(type = v, tag = vm.feedTag)
                }
            }
            Spacer(Modifier.width(8.dp))
            listOf("热门", "最新", "经典").forEach { t ->
                KvChip(t, selected = vm.feedTag == t) {
                    vm.setFeedFilter(type = vm.feedType, tag = t)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            vm.feedItems.isEmpty() && vm.feedLoading -> KvEmpty("正在取内容…")
            vm.feedItems.isEmpty() -> KvEmpty("这个组合没有内容，换个类型或标签试试。")
            else -> LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(vm.feedItems) { d: DoubanItem ->
                    PosterCard(
                        item = d,
                        width = 116,
                        meta = if (d.rate.isNotBlank()) "豆瓣 ${d.rate}" else ""
                    ) { vm.searchTitle(d.title) }
                }
                if (vm.feedLoading) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("正在加载更多…", style = Kv.Tiny)
                        }
                    }
                }
            }
        }
    }
}
