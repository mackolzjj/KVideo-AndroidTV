package com.kvideo.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kvideo.tv.vm.AppVm
import com.kvideo.tv.vm.Route

@Composable
fun KvApp(vm: AppVm = viewModel()) {
    KvTheme {
        Surface(Modifier.fillMaxSize(), color = Kv.Bg) {
            val route = vm.route

            if (vm.serverUrl.isBlank() && route !is Route.Settings) {
                SetupScreen(vm)
                return@Surface
            }

            BackHandler(enabled = vm.backStack.isNotEmpty()) { vm.back() }

            when (route) {
                is Route.Home -> MainScaffold(vm, 0) { HomeScreen(vm) }
                is Route.Live -> MainScaffold(vm, 1) { LiveScreen(vm) }
                is Route.Saved -> MainScaffold(vm, 2) { SavedScreen(vm) }
                is Route.Search -> SearchScreen(vm)
                is Route.Settings -> SettingsScreen(vm)
                is Route.Premium -> PremiumScreen(vm)
                is Route.Detail -> DetailScreen(vm, route)
                is Route.Player -> PlayerScreen(vm, route)
            }
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
                Icon(icon, label, tint = if (on) Color.White else Color(0xFF6B6B6B), modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(3.dp))
                Text(label, fontSize = 9.5.sp, color = if (on) Color.White else Color(0xFF6B6B6B))
            }
        }
    }
}
