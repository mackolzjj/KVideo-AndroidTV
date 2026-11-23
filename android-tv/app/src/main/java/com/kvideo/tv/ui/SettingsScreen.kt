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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kvideo.tv.BuildConfig
import com.kvideo.tv.vm.AppVm

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    password: Boolean = false
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(label, style = Kv.Tiny.copy(fontSize = 11.sp))
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Kv.Surface)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 13.sp, color = Kv.Ink3)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = Kv.Ink,
                    // 只做遮蔽显示，不做输入法层面的 password 语义，够用
                    fontWeight = if (password) FontWeight.Medium else FontWeight.Normal
                ),
                cursorBrush = SolidColor(Kv.Red),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SettingsScreen(vm: AppVm) {
    var server by remember { mutableStateOf(vm.serverUrl) }
    var pw by remember { mutableStateOf(vm.password) }
    var live by remember { mutableStateOf(vm.liveUrl) }
    var premiumPw by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "设置",
            trailing = {
                Text(
                    "完成",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Kv.Red,
                    modifier = Modifier
                        .clickable { vm.saveServer(server, pw, live); vm.back() }
                        .padding(8.dp)
                )
            })

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))
            Field("服务器地址", server, "http://192.168.1.250:81", { server = it })
            Field("访问密码", pw, "每个账户各自一个密码，服务器按密码识别身份", { pw = it }, password = true)
            Field("直播源（M3U）", live, "http://…/playlist.m3u", { live = it })

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

            if (vm.aliveSources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "死源已自动跳过（实测一批订阅里只有少数真能返回内容）",
                    style = Kv.Tiny
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface2)
                        .clickable { vm.probeSources() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("重新探活", fontSize = 12.sp, color = Kv.Ink)
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("高级内容", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.canPremium)
                    "已解锁 · 订阅里 group=premium 的成人源会参与搜索"
                else "未解锁 · 订阅里 59 个 premium 源默认不参与搜索",
                style = Kv.Body.copy(fontSize = 12.sp)
            )
            if (!vm.canPremium) {
                Spacer(Modifier.height(8.dp))
                Field("高级内容密码", premiumPw, "留空不解锁", { premiumPw = it }, password = true)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Kv.Surface2)
                        .clickable { vm.unlockPremium(premiumPw) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("解锁高级内容", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Kv.Ink)
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("其他", style = Kv.Tiny.copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable { vm.clearHistory() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("清除观看历史", fontSize = 13.5.sp, color = Kv.Ink)
                Spacer(Modifier.weight(1f))
                Text("${vm.history.size} 条", style = Kv.Tiny)
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("版本", fontSize = 13.5.sp, color = Kv.Ink)
                Spacer(Modifier.weight(1f))
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Kv.Tiny)
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("数据来源", fontSize = 13.5.sp, color = Kv.Ink)
                Spacer(Modifier.weight(1f))
                Text("KVideo 服务端", style = Kv.Tiny)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun SetupScreen(vm: AppVm) {
    var server by remember { mutableStateOf(vm.serverUrl) }
    var pw by remember { mutableStateOf(vm.password) }

    Column(
        Modifier.fillMaxSize().background(Kv.Bg).padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.size(13.dp).clip(RoundedCornerShape(2.dp)).background(Kv.Red))
        Spacer(Modifier.height(14.dp))
        Text("连接你的 KVideo", style = Kv.Title.copy(fontSize = 26.sp))
        Spacer(Modifier.height(8.dp))
        Text("填写服务器地址和访问密码，App 会直接读服务端的接口，不再套网页。", style = Kv.Body)
        Spacer(Modifier.height(24.dp))
        Field("服务器地址", server, "http://192.168.1.250:81", { server = it })
        Field("访问密码", pw, "每个账户一个密码，服务器按密码识别身份", { pw = it }, password = true)
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Kv.Ink)
                .clickable { vm.saveServer(server, pw, vm.liveUrl) },
            contentAlignment = Alignment.Center
        ) {
            Text("连接", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        }
        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 12.sp, color = Kv.Red)
        }
        if (vm.busy) {
            Spacer(Modifier.height(12.dp))
            Text("正在连接…", style = Kv.Tiny)
        }
    }
}
