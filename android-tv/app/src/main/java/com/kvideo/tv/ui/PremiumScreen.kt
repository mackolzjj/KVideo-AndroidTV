package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.data.VideoItem
import com.kvideo.tv.vm.AppVm

/**
 * 高级内容页 —— 对应上游 app/premium/page.tsx：
 * 先过 PremiumPasswordGate（POST /api/auth {type:'premium'}），
 * 分类走 POST /api/premium/types {sources}，列表走 POST /api/premium/category。
 * 这些源不进普通搜索，只有这里能进。
 */
@Composable
fun PremiumScreen(vm: AppVm) {
    var pw by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = KvSpace.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KvIconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 42) { vm.back() }
            Text("高级内容", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Kv.Ink)
            Spacer(Modifier.weight(1f))
            if (vm.canPremium) Text("${vm.premiumSources.size} 个源", style = Kv.Tiny)
        }

        if (!vm.canPremium) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                Text("这个页面需要高级内容密码", style = Kv.H2)
                Spacer(Modifier.height(KvSpace.s))
                Text("密码只用于解锁 premium 源，与登录密码无关。", style = Kv.Body)
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(KvRadius.s))
                        .background(Kv.Surface)
                        .padding(horizontal = KvSpace.m),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (pw.isEmpty()) Text("高级内容密码", fontSize = 13.sp, color = Kv.Ink3)
                    BasicTextField(
                        value = pw,
                        onValueChange = { pw = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Kv.Ink),
                        cursorBrush = SolidColor(Kv.Red),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(KvSpace.m))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(KvRadius.s))
                        .background(Kv.Ink)
                        .clickable { vm.unlockPremium(pw) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("解锁", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                }
                vm.error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, fontSize = 12.sp, color = Kv.Red)
                }
            }
        } else {
            LaunchedEffect(vm.premiumQuery) {
                val q = vm.premiumQuery.trim()
                if (q.isBlank()) {
                    if (vm.premiumResults.isNotEmpty() || vm.premiumSearching) vm.searchPremium("")
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(350L)
                vm.searchPremium(q)
            }

            // 官方 /premium 页同样有搜索框，且只用 premium 源搜（usePremiumHomePage → enabledPremiumSources）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KvSpace.l, vertical = 6.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(KvRadius.s))
                    .background(Kv.Surface)
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (vm.premiumQuery.isEmpty()) Text("在高级源里搜片名", fontSize = 13.sp, color = Kv.Ink3)
                BasicTextField(
                    value = vm.premiumQuery,
                    onValueChange = { vm.premiumQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Kv.Ink),
                    cursorBrush = SolidColor(Kv.Red),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))

            if (vm.premiumQuery.isNotBlank()) {
                when {
                    vm.premiumSearching && vm.premiumResults.isEmpty() -> KvEmpty("正在搜高级源…")
                    vm.premiumResults.isEmpty() -> KvEmpty("高级源里没有结果")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(vm.premiumResults) { v: VideoItem ->
                            Column(Modifier.clickable { vm.openVideo(v) }) {
                                PosterArt(v.poster, ratio = 1.5f, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                Text(v.name, fontSize = 11.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp)
                                Text(v.sourceName, style = Kv.Tiny.copy(fontSize = 10.sp), maxLines = 1)
                            }
                        }
                    }
                }
            } else {
            if (vm.premiumTags.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(vm.premiumTags) { (label, value) ->
                        KvChip(label, selected = vm.premiumTagValue == value) {
                            vm.loadPremiumCategory(value)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            when {
                vm.premiumLoading -> KvEmpty("正在取内容…")
                vm.premiumVideos.isEmpty() -> KvEmpty(
                    when {
                        vm.premiumSources.isEmpty() -> "订阅里没有 group=premium 的源"
                        !vm.premiumTagsOk -> "分类接口没返回：服务器这会儿连不上海外 premium 源，稍后再进"
                        // 官方 types 的第一条「今日推荐」value=''，代表所有 premium 源一起取
                        vm.premiumTagValue.isEmpty() -> "今日推荐没返回内容：这些源这会儿可能都不可达"
                        else -> "这个分类没返回内容，换个分类试试"
                    }
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(vm.premiumVideos) { v: VideoItem ->
                        Column(Modifier.clickable { vm.openVideo(v) }) {
                            PosterArt(v.poster, ratio = 1.5f, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(
                                v.name,
                                fontSize = 11.sp,
                                color = Kv.Ink,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                lineHeight = 15.sp
                            )
                            Text(v.sourceName, style = Kv.Tiny.copy(fontSize = 10.sp), maxLines = 1)
                        }
                    }
                    // 分类列表是分页的（limit=20），到底了就不再多调接口
                    if (vm.premiumHasMore) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(KvRadius.s))
                                    .background(Kv.Surface2)
                                    .clickable { vm.loadMorePremium() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (vm.premiumLoading) "加载中…" else "加载更多（第 ${vm.premiumPage + 1} 页）· 点这里",
                                    fontSize = 12.sp,
                                    color = Kv.Ink
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
