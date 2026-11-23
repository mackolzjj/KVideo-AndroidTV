package com.kvideo.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：服务器地址、访问密码、直播源、收藏、历史。
 * 用 SharedPreferences 存 JSON，2.0 这点体量不值得上 Room。
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kvideo_v2", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v.trim()).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    /**
     * 直播源。空 = 用 App 里打包的那份（assets/live_default.m3u）。
     * 注意：这里不许再放任何带口令的默认地址 —— 仓库是公开的，之前那版把
     * 服务器口令写死在 DEFAULT_LIVE_URL 里，等于公开泄露。
     */
    var liveUrl: String
        get() = sp.getString("live_url", "") ?: ""
        set(v) = sp.edit().putString("live_url", v.trim()).apply()

    /**
     * 屏蔽分类关键词（官方 settings.blockedCategories）：命中 type_name / vod_class 就不进搜索结果。
     * 官方默认空，这里默认带上「伦理」，因为普通用户的搜索不该翻出这批。
     */
    var blockedCategories: Set<String>
        get() = sp.getStringSet("blocked_categories", setOf("伦理"))?.toSet() ?: setOf("伦理")
        set(v) = sp.edit().putStringSet("blocked_categories", v).apply()

    /** 搜索是否只留片名真的含关键词的条目（源站会串味，默认开） */
    var onlyRelevant: Boolean
        get() = sp.getBoolean("only_relevant", true)
        set(v) = sp.edit().putBoolean("only_relevant", v).apply()

    /** 搜索结果是否合并同名（对齐上游 settings.searchDisplayMode） */
    var groupedSearch: Boolean
        get() = sp.getBoolean("grouped_search", false)
        set(v) = sp.edit().putBoolean("grouped_search", v).apply()

    /** 播放代理：0 自动（先直连、失败再走服务器代理），1 始终直连，2 始终代理 */
    var proxyMode: Int
        get() = sp.getInt("proxy_mode", 0)
        set(v) = sp.edit().putInt("proxy_mode", v).apply()

    /** 高级内容密码解锁标记（每台设备记住） */
    var premiumUnlocked: Boolean
        get() = sp.getBoolean("premium_unlocked", false)
        set(v) = sp.edit().putBoolean("premium_unlocked", v).apply()

    /**
     * 设置页「高级」子页的访问 PIN。存的是加盐哈希，不存明文 ——
     * 本地 PIN 只是防误改，不是账号安全，别给用户「已经加密了」的错觉。
     */
    var pinHash: String
        get() = sp.getString("pin_hash", "") ?: ""
        set(v) = sp.edit().putString("pin_hash", v).apply()

    var lastUser: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v).apply()

    fun favorites(): List<SavedItem> = read("favorites")

    fun toggleFavorite(item: SavedItem): Boolean {
        val list = favorites().toMutableList()
        val hit = list.indexOfFirst { it.id == item.id && it.sourceId == item.sourceId }
        val added = hit < 0
        if (added) list.add(0, item.copy(updatedAt = System.currentTimeMillis()))
        else list.removeAt(hit)
        write("favorites", list.take(200))
        return added
    }

    fun isFavorite(id: String, sourceId: String): Boolean =
        favorites().any { it.id == id && it.sourceId == sourceId }

    fun history(): List<SavedItem> = read("history")

    /** 播放中定期回写进度，收藏页据此显示「看到 xx:xx」并支持续播 */
    fun updateProgress(id: String, sourceId: String, positionMs: Long, durationMs: Long = 0L) {
        val list = history().toMutableList()
        val i = list.indexOfFirst { it.id == id && it.sourceId == sourceId }
        if (i < 0) return
        list[i] = list[i].copy(
            positionMs = positionMs,
            durationMs = if (durationMs > 0) durationMs else list[i].durationMs,
            updatedAt = System.currentTimeMillis()
        )
        write("history", list)
    }

    fun progressOf(id: String, sourceId: String): Long =
        history().firstOrNull { it.id == id && it.sourceId == sourceId }?.positionMs ?: 0L

    fun pushHistory(item: SavedItem) {
        val prev = history().firstOrNull { it.id == item.id && it.sourceId == item.sourceId }
        val list = history().filterNot { it.id == item.id && it.sourceId == item.sourceId }.toMutableList()
        // 换集时别把已看的进度清零
        list.add(
            0,
            item.copy(
                positionMs = if (item.positionMs > 0) item.positionMs else (prev?.positionMs ?: 0L),
                updatedAt = System.currentTimeMillis()
            )
        )
        write("history", list.take(60))
    }

    fun clearHistory() = write("history", emptyList())

    private fun read(key: String): List<SavedItem> {
        val raw = sp.getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedItem(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    poster = o.optString("poster"),
                    sourceId = o.optString("sourceId"),
                    episodeIndex = o.optInt("episodeIndex", 0),
                    episodeName = o.optString("episodeName"),
                    positionMs = o.optLong("positionMs", 0L),
                    durationMs = o.optLong("durationMs", 0L),
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, list: List<SavedItem>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("poster", s.poster)
                    .put("sourceId", s.sourceId)
                    .put("episodeIndex", s.episodeIndex)
                    .put("episodeName", s.episodeName)
                    .put("positionMs", s.positionMs)
                    .put("durationMs", s.durationMs)
                    .put("updatedAt", s.updatedAt)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
