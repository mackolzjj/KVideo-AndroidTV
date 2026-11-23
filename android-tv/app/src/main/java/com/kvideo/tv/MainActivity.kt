package com.kvideo.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.kvideo.tv.ui.KvApp

/**
 * 2.0 起这里是纯原生 App（Compose + Media3），不再有 WebView 注入层。
 * 数据全部来自 KVideo 服务端的 api 接口，界面按 Netflix 那套语言重画。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent { KvApp() }
    }
}
