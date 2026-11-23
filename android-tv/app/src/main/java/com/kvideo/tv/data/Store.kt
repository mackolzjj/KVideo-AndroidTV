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

    var liveUrl: String
        get() = sp.getString("live_url", "") ?: ""
        set(v) = sp.edit().putString("live_url", v.trim()).apply()

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

    fun pushHistory(item: SavedItem) {
        val list = history().filterNot { it.id == item.id && it.sourceId == item.sourceId }.toMutableList()
        list.add(0, item.copy(updatedAt = System.currentTimeMillis()))
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
                    .put("updatedAt", s.updatedAt)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
