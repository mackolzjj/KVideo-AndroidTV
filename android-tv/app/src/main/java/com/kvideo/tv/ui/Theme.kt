package com.kvideo.tv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Netflix 语言：纯黑底、唯一强调色 #e50914 只给进度；文字靠字号与字重分层 */
object Kv {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF141414)
    val Surface2 = Color(0xFF1B1B1B)
    val Line = Color(0x24FFFFFF)
    val Ink = Color(0xFFFFFFFF)
    val Ink2 = Color(0xFFB3B3B3)
    val Ink3 = Color(0xFF7A7A7A)
    val Red = Color(0xFFE50914)

    val Title = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, color = Ink)
    val H2 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
    val Body = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, color = Ink2, lineHeight = 21.sp)
    val Meta = TextStyle(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = Ink2)
    val Tiny = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = Ink3)
    val Mono = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
}

private val scheme = darkColorScheme(
    primary = Kv.Red,
    onPrimary = Color.White,
    background = Kv.Bg,
    onBackground = Kv.Ink,
    surface = Kv.Surface,
    onSurface = Kv.Ink,
    surfaceVariant = Kv.Surface2,
    onSurfaceVariant = Kv.Ink2,
    outline = Kv.Line
)

@Composable
fun KvTheme(content: @Composable () -> Unit) {
    // 2.0 只做深色：Netflix 语言里浅色是另一套设计，不做半吊子适配
    @Suppress("UNUSED_VARIABLE")
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
