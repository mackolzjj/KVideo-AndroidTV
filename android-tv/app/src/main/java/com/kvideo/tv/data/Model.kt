package com.kvideo.tv.data

/** 一个资源站（来自 /api/config 的 subscriptionSources，或服务器自带的默认源） */
data class Source(
    val id: String,
    val name: String,
    val baseUrl: String,
    val group: String = "normal",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val tag: String = "",
    // 服务端拼 URL 用的是 baseUrl + searchPath / detailPath。订阅清单里的 baseUrl
    // 已经带完整路径（…/api.php/provide/vod），所以这两项正常是空串；
    // 但字段必须存在 —— 缺了服务端会拼成 `${baseUrl}undefined`，搜索直接 0 条、详情 500。
    val searchPath: String = "",
    val detailPath: String = ""
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

/** 同名合并后的一组结果 = 一部片，items 是各资源站给的同一条资源 */
data class ResultGroup(
    val title: String,
    val poster: String,
    val items: List<VideoItem>
) {
    val sourceCount: Int get() = items.size
}

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
