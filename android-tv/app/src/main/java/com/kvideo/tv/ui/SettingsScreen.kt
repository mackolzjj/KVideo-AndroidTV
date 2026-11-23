package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.kvideo.tv.vm.Route

@Composable
internal fun Field(
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
    var versionTaps by remember { mutableStateOf(0) }
    var pinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinAgain by remember { mutableStateOf("") }
    var pinErr by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Kv.Bg)) {
        KvTopBar(title = "设置",
            trailing = {
                Text(
                    "完成",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Kv.Red,
                    modifier = Modifier
                        .clickable { vm.back() }
                        .padding(8.dp)
                )
            })

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))

            // 连接状态：只读。服务器地址 / 密码 / 直播源 / 代理都挪进「高级设置」了
            if (vm.serverUrl.isBlank()) {
                Text("还没连服务器（首启会引导填写）", style = Kv.Tiny.copy(color = Kv.Red))
            } else {
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
            vm.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Kv.Red)
            }

            Spacer(Modifier.height(22.dp))
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
            // 隐藏入口：连点「版本」7 次 → PIN → 高级设置（页面上不留任何入口）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable {
                        versionTaps++
                        if (versionTaps >= 7) {
                            versionTaps = 0
                            pinInput = ""
                            pinAgain = ""
                            pinErr = null
                            pinDialog = true
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("版本", fontSize = 13.5.sp, color = Kv.Ink)
                Spacer(Modifier.weight(1f))
                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Kv.Tiny)
            }
            if (vm.canPremium) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clickable { vm.openPremium() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("高级内容", fontSize = 13.5.sp, color = Kv.Ink)
                    Spacer(Modifier.weight(1f))
                    Text("已解锁 ›", style = Kv.Tiny)
                }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("数据来源", fontSize = 13.5.sp, color = Kv.Ink)
                Spacer(Modifier.weight(1f))
                Text("KVideo 服务端", style = Kv.Tiny)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    if (pinDialog) {
        val firstTime = !vm.hasPin
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = {
                Text(
                    if (firstTime) "设置访问 PIN" else "输入访问 PIN",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        if (firstTime) "4–8 位数字，进高级设置时要输它，设好别忘了。"
                        else "进高级设置需要它。忘了只能清 App 数据重设。",
                        style = Kv.Tiny
                    )
                    Spacer(Modifier.height(10.dp))
                    PinBox(pinInput) { pinInput = it.filter { c -> c.isDigit() }.take(8) }
                    if (firstTime) {
                        Spacer(Modifier.height(8.dp))
                        PinBox(pinAgain) { pinAgain = it.filter { c -> c.isDigit() }.take(8) }
                    }
                    pinErr?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 11.5.sp, color = Kv.Red)
                    }
                }
            },
            confirmButton = {
                Text(
                    "确定",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Kv.Red,
                    modifier = Modifier
                        .clickable {
                            when {
                                pinInput.length < 4 -> pinErr = "至少 4 位"
                                firstTime && pinInput != pinAgain -> pinErr = "两次输入不一致"
                                firstTime -> {
                                    vm.setPin(pinInput)
                                    pinDialog = false
                                    vm.go(Route.Advanced)
                                }
                                vm.verifyPin(pinInput) -> {
                                    pinDialog = false
                                    vm.go(Route.Advanced)
                                }
                                else -> pinErr = "PIN 不对"
                            }
                        }
                        .padding(8.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消",
                    fontSize = 13.sp,
                    color = Kv.Ink2,
                    modifier = Modifier.clickable { pinDialog = false }.padding(8.dp)
                )
            },
            containerColor = Kv.Surface
        )
    }
}

@Composable
private fun PinBox(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Kv.Surface2)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) Text("••••", fontSize = 15.sp, color = Kv.Ink3)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = Kv.Ink, fontWeight = FontWeight.Bold),
            cursorBrush = SolidColor(Kv.Red),
            modifier = Modifier.fillMaxWidth()
        )
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
