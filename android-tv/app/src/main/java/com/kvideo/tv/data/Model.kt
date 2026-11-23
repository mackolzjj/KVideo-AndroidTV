package com.kvideo.tv.data

/** 一个资源站（来自 /api/config 的 subscriptionSources，或服务器自带的默认源） */
data class Source(
    val id: String,
    val name: String,
    val baseUrl: String,
    val group: String = "normal",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val tag: String = ""
)

/** 搜索结果条目（资源站 vod_* 的投影） */
data class VideoItem(
    val id: String,
    val name: String,
    val poster: String,
    val remarks: String,
    val year: String,
    val sourceId: String,
    val sourceName: String
)

data class Episode(val index: Int, val name: String, val url: String)

data class Detail(
    val id: String,
    val name: String,
    val poster: String,
    val remarks: String,
    val year: String,
    val area: String,
    val typeName: String,
    val actors: String,
    val intro: String,
    val episodes: List<Episode>,
    val sourceId: String
)

data class DoubanItem(
    val id: String,
    val title: String,
    val cover: String,
    val rate: String
)

data class Channel(val name: String, val url: String, val group: String)

/** 播放历史 / 收藏条目 */
data class SavedItem(
    val id: String,
    val name: String,
    val poster: String,
    val sourceId: String,
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val positionMs: Long = 0L,
    val updatedAt: Long = 0L
)
