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
import androidx.compose.material.icons.filled.ArrowBack
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
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KvIconBtn(Icons.Filled.ArrowBack, "返回", size = 42) { vm.back() }
            Text("高级内容", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Kv.Ink)
            Spacer(Modifier.weight(1f))
            if (vm.canPremium) Text("${vm.premiumSources.size} 个源", style = Kv.Tiny)
        }

        if (!vm.canPremium) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                Text("这个页面需要高级内容密码", style = Kv.H2)
                Spacer(Modifier.height(8.dp))
                Text("密码只用于解锁 premium 源，与登录密码无关。", style = Kv.Body)
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface)
                        .padding(horizontal = 12.dp),
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
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(3.dp))
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
                return@Column
            }

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
                    if (vm.premiumSources.isEmpty()) "订阅里没有 group=premium 的源"
                    else "没有内容，换个分类试试"
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
                }
            }
        }
    }
}
