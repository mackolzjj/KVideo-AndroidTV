@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 播放页的「外壳」：顶栏、底栏、倍速弹层。
 *
 * 从 PlayerScreen.kt 里拆出来的，只做展示 + 回调，不碰播放器状态；
 * 这样播放页主体只剩「播放器 + 手势 + 状态」，改外观不用翻 900 行。
 */

/** 控制条尺寸：横竖屏只差这几个数 */
internal data class PlayerBarSpec(val icon: Int, val play: Int)

/** 顶栏：返回键贴画面左缘（有黑边时跟着内缩），标题居中 */
@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String,
    startPad: Float,
    endPad: Float,
    topPad: Float,
    onBack: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xC7000000), Color.Transparent)))
            .padding(start = startPad.dp, end = endPad.dp, top = topPad.dp, bottom = 6.dp)
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            KvIconBtn(Icons.AutoMirrored.Filled.ArrowBack, "返回", size = 40) { onBack() }
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 11.sp, color = Kv.Ink2, maxLines = 1)
            }
        }
    }
}

/**
 * 底栏：进度条 + 一排按钮。横竖屏共用同一段布局，只有尺寸不同（[PlayerBarSpec]）。
 * 进度条拖动只写 preview，松手才 seek —— 每动一像素 seek 一次会卡。
 */
@Composable
internal fun PlayerBottomBar(
    compact: Boolean,
    barDp: Float,
    position: Long,
    duration: Long,
    seekPreview: Long?,
    playing: Boolean,
    speed: Float,
    speedOpen: Boolean,
    hasEpisodes: Boolean,
    onPreview: (Long?) -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTogglePlay: () -> Unit,
    onEpisodes: () -> Unit,
    onToggleSpeed: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    onFullscreen: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = barDp.dp + KvSpace.m, end = barDp.dp + KvSpace.m, top = 40.dp, bottom = 10.dp)
    ) {
        Slider(
            value = if (duration > 0) {
                ((seekPreview ?: position).toFloat() / duration).coerceIn(0f, 1f)
            } else 0f,
            onValueChange = { v -> if (duration > 0) onPreview((v * duration).toLong()) },
            onValueChangeFinished = { onPreview(null) },
            colors = SliderDefaults.colors(
                thumbColor = Kv.Red,
                activeTrackColor = Kv.Red,
                inactiveTrackColor = Color(0x47FFFFFF)
            ),
            modifier = Modifier.fillMaxWidth().height(24.dp)
        )

        val bar = if (compact) PlayerBarSpec(icon = 34, play = 38) else PlayerBarSpec(icon = 42, play = 46)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KvIconBtn(Icons.Filled.FastRewind, "后退10秒", size = bar.icon) { onSeekBack() }
            KvIconBtn(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (playing) "暂停" else "播放",
                size = bar.play
            ) { onTogglePlay() }
            KvIconBtn(Icons.Filled.FastForward, "前进10秒", size = bar.icon) { onSeekForward() }
            Spacer(Modifier.width(6.dp))
            Text(
                "${fmtTime(position)} / ${fmtTime(duration)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            if (hasEpisodes) {
                KvIconBtn(Icons.AutoMirrored.Filled.PlaylistPlay, "剧集", size = bar.icon) { onEpisodes() }
            }
            SpeedChip(speed, compact, speedOpen, { onToggleSpeed() }) { s -> onPickSpeed(s) }
            KvIconBtn(Icons.Filled.Fullscreen, "全屏", size = bar.icon) { onFullscreen() }
        }
    }
}

/** 倍速按钮 + 弹出的倍速列表（横竖屏共用） */
@Composable
internal fun SpeedChip(
    speed: Float,
    compact: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (Float) -> Unit
) {
    Box {
        Text(
            if (compact) "${"%.1f".format(speed)}X" else "倍速 ${"%.1f".format(speed)}X",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(KvRadius.s))
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 8.dp)
        )
        if (open) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(y = (-42).dp)
            ) {
                SpeedList(speed = speed, onPick = onPick)
            }
        }
    }
}

/** 倍速列表：竖屏弹在控制条上方，横屏弹在按钮上方 */
@Composable
private fun SpeedList(speed: Float, onPick: (Float) -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(KvRadius.s))
            .background(Kv.Surface)
            .padding(6.dp)
    ) {
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
            Text(
                "${s}X",
                fontSize = 12.sp,
                fontWeight = if (s == speed) FontWeight.Bold else FontWeight.Normal,
                color = if (s == speed) Kv.Red else Color.White,
                modifier = Modifier
                    .clickable { onPick(s) }
                    .padding(horizontal = KvSpace.m, vertical = 7.dp)
            )
        }
    }
}

/** mm:ss / h:mm:ss */
internal fun fmtTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    else "%02d:%02d".format(total / 60, total % 60)
}
