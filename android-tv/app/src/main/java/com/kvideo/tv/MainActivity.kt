package com.kvideo.tv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
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

    /**
     * 离开 App（回桌面 / 切到微信）时，如果播放页正在播，就缩成画中画继续看。
     * 是否允许由播放页控制（[canEnterPip]），这样在别的页面退后台不会莫名弹小窗。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!canEnterPip) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode) return
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Throwable) {
            // 系统不允许（设备禁用、被其它小窗占用等）就按普通退后台处理
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
    }

    companion object {
        /** 播放页在播时置 true：此时允许 onUserLeaveHint 自动进画中画 */
        var canEnterPip: Boolean = false

        /** 当前是否处于画中画，播放页据此收起控制层 */
        val inPipState = mutableStateOf(false)
    }
}
