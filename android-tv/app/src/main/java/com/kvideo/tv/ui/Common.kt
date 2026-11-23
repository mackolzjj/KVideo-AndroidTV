package com.kvideo.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.data.VideoItem

@Composable
fun KvIconBtn(icon: ImageVector, label: String, size: Int = 38, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Kv.Ink, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
fun KvTopBar(
    title: String? = null,
    onSearch: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (title == null) {
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.dp)).background(Kv.Red))
            Spacer(Modifier.width(8.dp))
            Text("KVideo", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Kv.Ink, letterSpacing = (-0.4).sp)
        } else {
            Text(title, style = Kv.H2, fontSize = 17.sp)
        }
        Spacer(Modifier.weight(1f))
        if (onSearch != null) {
            KvIconBtn(Icons.Filled.Search, "搜索", onClick = onSearch)
        }
        trailing()
    }
}

@Composable
fun SectionHeader(title: String, count: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(title, style = Kv.H2)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = Kv.Tiny)
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(
                action,
                style = Kv.Tiny.copy(fontSize = 11.5.sp),
                modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() }
            )
        }
    }
}

/**
 * 海报卡。豆瓣条目自带 rate，右下角压一块 #e50914 评分角标（跟进度条同色，不新增颜色）；
 * meta 那行放「电影 · 2026」这类信息。
 */
@Composable
fun PosterCard(
    item: DoubanItem,
    width: Int = 104,
    meta: String = "",
    isNew: Boolean = false,
    onClick: () -> Unit
) {
    Column(Modifier.width(width.dp).clickable(onClick = onClick)) {
        Box(Modifier.width(width.dp)) {
            PosterArt(item.cover, ratio = 1.5f, modifier = Modifier.width(width.dp))
            if (item.rate.isNotBlank()) {
                Text(
                    item.rate,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Kv.Red)
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
            }
            if (isNew) {
                Text(
                    "新",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x99000000))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, style = Kv.Meta.copy(fontSize = 11.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(meta, style = Kv.Tiny.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PosterArt(url: String, ratio: Float = 1.5f, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.aspectRatio(1f / ratio).clip(shape).background(Kv.Surface2)) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Color(0xFF232B30), Color(0xFF0B0D0F)))
                )
            )
        }
    }
}

/** 继续观看 / 搜索结果用的横排大卡（16:9） */
@Composable
fun WideCard(
    title: String,
    subtitle: String,
    poster: String,
    badge: String? = null,
    cornerTag: String? = null,
    remainText: String? = null,
    progress: Float = 0f,
    width: Int = 196,
    onClick: () -> Unit
) {
    Column(Modifier.width(width.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(3.dp)).background(Kv.Surface2)) {
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (badge != null) {
                Text(
                    badge,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xCCFFFFFF),
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 7.dp)
                )
            }
            // 左上角：集数胶囊；右上角：剩余时长
            if (cornerTag != null) {
                Text(
                    cornerTag,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 7.dp)
                        .background(Color(0xA6000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (remainText != null) {
                Text(
                    remainText,
                    fontSize = 9.5.sp,
                    color = Color(0xE6FFFFFF),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 7.dp)
                        .background(Color(0xA6000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (progress > 0f) {
                Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter).background(Color(0x42FFFFFF))) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Kv.Red)
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Kv.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = Kv.Tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun VideoCard(item: VideoItem, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick)) {
        PosterArt(item.poster, ratio = 1.5f, modifier = Modifier.width(128.dp))
        Spacer(Modifier.height(6.dp))
        Text(item.name, fontSize = 11.5.sp, color = Kv.Ink, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            listOfNotNull(item.sourceName.ifBlank { null }, item.remarks.ifBlank { null }).joinToString(" · "),
            style = Kv.Tiny, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun KvChip(text: String, selected: Boolean = false, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (selected) Kv.Ink else Kv.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) Color.Black else Kv.Ink2
        )
    }
}

@Composable
fun KvEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Kv.Body.copy(color = Kv.Ink3), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** 横向滑动的内容行 */
@Composable
fun <T> KvRow(items: List<T>, content: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        items(items) { content(it) }
    }
}
