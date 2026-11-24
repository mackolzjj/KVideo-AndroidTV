package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.vm.AppVm

/**
 * 高级设置：服务器地址 / 访问密码 / 直播源 / 代理 / 探活 / 高级内容。
 *
 * 这些项都不该在普通设置页露着 —— 手滑改错服务器地址，整个 App 就废了。
 * 入口：设置页连点「版本」7 次 → 输 PIN（首次让你设一个）→ 进这里。
 */
@Composable
@OptIn(coil.annotation.ExperimentalCoilApi::class)
fun AdvancedScreen(vm: AppVm) {
    var server by remember { mutableStateOf(vm.serverUrl) }
    var pw by remember { mutableStateOf(vm.password) }
    var live by remember { mutableStateOf(vm.liveUrl) }
    var epgUrlText by remember { mutableStateOf(vm.epgUrl) }
    var premiumPw by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinMsg by remember { mutableStateOf<String?>(null) }
    var blockedText by remember { mutableStateOf(vm.blockedCategories.joinToString("、")) }
    var blockedMsg by remember { mutableStateOf<String?>(null) }
    var cacheMsg by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    var crash by remember { mutableStateOf(com.kvideo.tv.KvApplication.lastCrash(ctx)) }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "高级设置")

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))

            Field("服务器地址", server, "http://192.168.1.2:81", { server = it })
            Field("访问密码", pw, "每个账户各自一个密码，服务器按密码识别身份", { pw = it }, password = true)
            Field("直播源 M3U 地址（不填则直播页只显示提示）", live, "http://…/playlist.m3u", { live = it })
            Field(
                "节目单 EPG 地址（XMLTV，选填）",
                epgUrlText,
                "http://…/playback.xml",
                { epgUrlText = it; vm.saveEpgUrl(it) }
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.Ink)
                    .clickable { vm.saveServer(server, pw, live) },
                contentAlignment = Alignment.Center
            ) {
                Text("保存并重连", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }

            if (vm.busy) {
                Spacer(Modifier.height(10.dp))
                Text("正在连接…", style = Kv.Tiny)
            }
            vm.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = Kv.Red)
            }
            if (!vm.busy && vm.error == null && vm.serverUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                val srcLine = if (vm.probing) {
                    "正在探活 ${vm.sources.size} 个源…"
                } else {
                    "${vm.aliveSources.size}/${vm.visibleSources.size} 个源可搜"
                }
                Text(
                    "已连接 · ${vm.accountName}（${vm.accountRole}）· $srcLine",
                    style = Kv.Tiny.copy(color = Kv.Ink2)
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("播放代理", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(0 to "自动", 1 to "直连", 2 to "代理").forEach { (mode, label) ->
                    KvChip(label, selected = vm.proxyMode == mode) { vm.proxyMode = mode }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("自动 = 先直连，失败再走服务器的 HLS 代理", style = Kv.Tiny)

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(Kv.Surface2)
                    .clickable { vm.probeSources() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(if (vm.probing) "正在探活…" else "重新探活源", fontSize = 12.sp, color = Kv.Ink)
            }

            Spacer(Modifier.height(KvSpace.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(KvRadius.s))
                        .background(Kv.Surface2)
                        .clickable {
                            val loader = coil.Coil.imageLoader(ctx)
                            loader.memoryCache?.clear()
                            loader.diskCache?.clear()
                            cacheMsg = "已清除"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("清除图片缓存", fontSize = 12.sp, color = Kv.Ink)
                }
                cacheMsg?.let {
                    Spacer(Modifier.width(KvSpace.s))
                    Text(it, style = Kv.Tiny)
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("屏蔽分类", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(6.dp))
            Text("命中分类关键词的搜索结果直接隐藏（官方 blockedCategories，用「、」分隔）", style = Kv.Tiny)
            Spacer(Modifier.height(8.dp))
            Field("分类关键词", blockedText, "伦理、福利", { blockedText = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface2)
                        .clickable {
                            vm.blockedCategories = blockedText
                                .split("、", ",", " ", "，")
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toSet()
                            blockedMsg = "已保存（${vm.blockedCategories.size} 个词）"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("保存屏蔽词", fontSize = 12.sp, color = Kv.Ink)
                }
                blockedMsg?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = Kv.Tiny.copy(color = Kv.Ink2))
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("高级内容", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(8.dp))
            if (vm.canPremium) {
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clickable { vm.openPremium() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("进入高级内容", fontSize = 13.5.sp, color = Kv.Ink)
                    Spacer(Modifier.weight(1f))
                    Text("›", style = Kv.Tiny)
                }
            } else {
                Field("高级内容密码", premiumPw, "管理员账号可直接进", { premiumPw = it }, password = true)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface2)
                        .clickable { vm.unlockPremium(premiumPw) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("解锁", fontSize = 12.sp, color = Kv.Ink)
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("上次崩溃", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(6.dp))
            if (crash.isBlank()) {
                Text("没有崩溃记录", style = Kv.Tiny)
            } else {
                Text(
                    crash.trim().take(700),
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = Kv.Ink2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface)
                        .padding(10.dp)
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Kv.Surface2)
                            .clickable { crash = com.kvideo.tv.KvApplication.lastCrash(ctx) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("刷新", fontSize = 12.sp, color = Kv.Ink) }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(Kv.Surface2)
                            .clickable {
                                com.kvideo.tv.KvApplication.clearCrash(ctx)
                                crash = ""
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("清空", fontSize = 12.sp, color = Kv.Ink) }
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("访问 PIN", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.hasPin) "已设置。改一个就换新的（4–8 位数字）" else "还没设置：设了以后进这个页面要输它",
                style = Kv.Tiny
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (pin.isEmpty()) Text("4–8 位数字", fontSize = 13.sp, color = Kv.Ink3)
                    BasicTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = Kv.Ink),
                        cursorBrush = SolidColor(Kv.Red),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface2)
                        .clickable {
                            when {
                                pin.length < 4 -> pinMsg = "至少 4 位"
                                else -> {
                                    vm.setPin(pin)
                                    pin = ""
                                    pinMsg = "已更新"
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text("保存 PIN", fontSize = 12.sp, color = Kv.Ink)
                }
            }
            pinMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = Kv.Tiny.copy(color = if (it == "已更新") Kv.Ink2 else Kv.Red))
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
