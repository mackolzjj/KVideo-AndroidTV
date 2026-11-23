package com.kvideo.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

@Composable
fun KvApp(vm: AppVm = viewModel()) {
    val ctx = LocalContext.current
    // 上次如果崩过，直接开在最前面，不用进高级设置才能看到
    var crash by remember { mutableStateOf(com.kvideo.tv.KvApplication.lastCrash(ctx)) }

    // 注意：这里不能用 return@KvTheme / return@Surface 提前返回。
    // @Composable 的 content lambda 里提前 return 会让 Compose 的 group 进/出栈不平衡，
    // 运行到 exitGroup 时抛 IndexOutOfBoundsException: Index -1 out of bounds for length 0
    // —— 2.0.40 的「打开就闪退」就是这个（崩溃屏一出现必走 early return）。
    KvTheme {
        if (crash.isNotBlank()) {
            CrashScreen(crash) {
                com.kvideo.tv.KvApplication.clearCrash(ctx)
                crash = ""
            }
        } else {
            Surface(Modifier.fillMaxSize(), color = Kv.Bg) {
                val route = vm.route

                if (vm.serverUrl.isBlank() && route !is Route.Settings) {
                    SetupScreen(vm)
                } else {
                    BackHandler(enabled = vm.backStack.isNotEmpty()) { vm.back() }

                    when (route) {
                        is Route.Home -> MainScaffold(vm, 0) { HomeScreen(vm) }
                        is Route.Live -> MainScaffold(vm, 1) { LiveScreen(vm) }
                        is Route.Saved -> MainScaffold(vm, 2) { SavedScreen(vm) }
                        is Route.Search -> SearchScreen(vm)
                        is Route.Settings -> SettingsScreen(vm)
                        is Route.Premium -> PremiumScreen(vm)
                        is Route.Advanced -> AdvancedScreen(vm)
                        is Route.Detail -> DetailScreen(vm, route)
                        is Route.Player -> PlayerScreen(vm, route)
                    }
                }
            }
        }
    }
}

/** 崩溃日志屏：把最后一段堆栈直接摆在第一屏 */
@Composable
private fun CrashScreen(text: String, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Kv.Bg).padding(18.dp)) {
        Text("上次运行时崩了", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Kv.Ink)
        Spacer(Modifier.height(6.dp))
        Text("下面是堆栈（也可以从 /Android/data/com.kvideo.tv/files/last_crash.txt 拷出来）", fontSize = 11.5.sp, color = Kv.Ink3)
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Kv.Surface)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text(text.trim(), fontSize = 10.5.sp, lineHeight = 15.sp, color = Kv.Ink2)
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Kv.Ink)
                .clickable { onContinue() },
            contentAlignment = Alignment.Center
        ) {
            Text("继续使用（并清空这条日志）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

@Composable
private fun MainScaffold(vm: AppVm, tab: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        Box(Modifier.weight(1f)) { content() }
        BottomBar(tab) { idx ->
            when (idx) {
                0 -> vm.go(Route.Home, keepStack = false)
                1 -> vm.go(Route.Live, keepStack = false)
                2 -> vm.go(Route.Saved, keepStack = false)
                else -> vm.go(Route.Settings)
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Int, onPick: (Int) -> Unit) {
    val items = listOf(
        Triple("首页", Icons.Filled.Home, 0),
        Triple("直播", Icons.Filled.LiveTv, 1),
        Triple("片单", Icons.Filled.Bookmark, 2),
        Triple("我的", Icons.Filled.Person, 3)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(Kv.Bg)
            .height(60.dp)
    ) {
        items.forEach { (label, icon: ImageVector, idx) ->
            val on = idx == tab
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onPick(idx) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    icon,
                    label,
                    tint = if (on) Color.White else Color(0xFF666B71),
                    modifier = Modifier.size(21.dp).offset(y = if (on) (-1.5).dp else 0.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    fontSize = 9.5.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) Color.White else Color(0xFF666B71)
                )
            }
        }
    }
}
