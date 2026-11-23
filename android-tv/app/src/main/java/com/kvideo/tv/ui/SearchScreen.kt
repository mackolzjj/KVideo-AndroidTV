package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
fun SearchScreen(vm: AppVm, autofocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autofocus) runCatching { focus.requestFocus() } }

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
                        onValueChange = { vm.search(it) },
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
                "已搜 ${vm.progressDone}/${vm.progressTotal} 个源 · 找到 ${vm.results.size} 条",
                style = Kv.Tiny,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        } else if (vm.results.isEmpty()) {
            KvEmpty("没有结果。换个片名，或检查服务器能不能连上资源站。")
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(vm.results) { v: VideoItem ->
                Column(Modifier.clickable { vm.playItem(v) }) {
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
                    Text(
                        listOfNotNull(v.sourceName.ifBlank { null }, v.remarks.ifBlank { null }).joinToString(" · "),
                        style = Kv.Tiny.copy(fontSize = 10.sp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
