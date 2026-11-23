package com.kvideo.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网络底座：
 * - 服务器地址运行时可改（设置页），统一走 [abs] 拼绝对地址；
 * - KVideo 的登录态是 cookie，这里手动收 Set-Cookie 再逐条回带。
 *   没用 OkHttp 的 CookieJar：局域网里是 IP 直连，Cookie 的 domain 匹配对 IP 不可靠。
 */
object Net {
    @Volatile
    var base: String = ""

    private val cookies = LinkedHashMap<String, String>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun abs(url: String?): String {
        if (url.isNullOrBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val root = base.trimEnd('/')
        return if (url.startsWith("/")) root + url else "$root/$url"
    }

    fun builder(pathOrUrl: String): Request.Builder {
        val b = Request.Builder()
            .url(abs(pathOrUrl))
            .header("User-Agent", "KVideo-Android/2.0")
            .header("Accept", "application/json, text/plain, */*")
        if (cookies.isNotEmpty()) {
            b.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return b
    }

    fun eatCookies(list: List<String>) {
        list.forEach { raw ->
            val first = raw.substringBefore(';').trim()
            val eq = first.indexOf('=')
            if (eq > 0) cookies[first.substring(0, eq).trim()] = first.substring(eq + 1).trim()
        }
    }

    fun clearCookies() = cookies.clear()

    fun hasSession(): Boolean = cookies.isNotEmpty()

    fun jsonBody(obj: JSONObject) = obj.toString().toRequestBody("application/json".toMediaType())
}

class Api {

    /** 用访问密码登录，成功返回 true（服务端会下发会话 cookie） */
    suspend fun login(password: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = Net.jsonBody(JSONObject().put("password", password))
            Net.client.newCall(Net.builder("/api/auth").post(body).build()).execute().use { r ->
                Net.eatCookies(r.headers("Set-Cookie"))
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext false
                JSONObject(text).optBoolean("valid", false)
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 订阅源列表：/api/config 把 JSON 塞在 subscriptionSources 字符串字段里 */
    suspend fun sources(): List<Source> = withContext(Dispatchers.IO) {
        try {
            Net.client.newCall(Net.builder("/api/config").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext emptyList()
                val root = JSONObject(text)
                val raw = root.optString("subscriptionSources", "")
                if (raw.isBlank()) return@withContext emptyList()
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i -> sourceOf(arr.optJSONObject(i)) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sourceOf(o: JSONObject?): Source? {
        if (o == null) return null
        val baseUrl = o.optString("baseUrl", "")
        if (baseUrl.isBlank()) return null
        return Source(
            id = o.optString("id", baseUrl),
            name = o.optString("name", o.optString("id", "源")),
            baseUrl = baseUrl,
            group = o.optString("group", "normal"),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 0),
            tag = o.optString("source", ""),
            searchPath = o.optString("searchPath", ""),
            detailPath = o.optString("detailPath", "")
        )
    }

    /** 首页内容流：豆瓣推荐（封面走 /api/douban/image 代理） */
    suspend fun recommend(): List<DoubanItem> = withContext(Dispatchers.IO) {
        try {
            Net.client.newCall(Net.builder("/api/douban/recommend").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext emptyList()
                val arr = JSONObject(text).optJSONArray("subjects") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    DoubanItem(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        cover = Net.abs(o.optString("cover")),
                        rate = o.optString("rate")
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun search(
        query: String,
        sources: List<Source>,
        onVideos: (List<VideoItem>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (query.isBlank() || sources.isEmpty()) return@withContext
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("baseUrl", s.baseUrl)
                    .put("group", s.group)
                    .put("enabled", s.enabled)
                    .put("priority", s.priority)
                    .put("searchPath", s.searchPath)
                    .put("detailPath", s.detailPath)
                    .put("headers", JSONObject())
            )
        }
        val payload = JSONObject().put("query", query).put("sources", arr)
        try {
            Net.client.newCall(Net.builder("/api/search-parallel").post(Net.jsonBody(payload)).build())
                .execute().use { r ->
                    val source = r.body?.source() ?: return@withContext
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val json = line.removePrefix("data:").trim()
                        if (json.isEmpty()) continue
                        val obj = try { JSONObject(json) } catch (e: Exception) { continue }
                        when (obj.optString("type")) {
                            "videos" -> onVideos(parseVideos(obj.optJSONArray("videos")))
                            "progress" -> onProgress(
                                obj.optInt("completedSources", 0),
                                obj.optInt("totalSources", sources.size)
                            )
                        }
                    }
                }
        } catch (e: IOException) {
            // 单源失败不影响其它源，界面按已收到的结果展示
        }
    }

    private fun parseVideos(arr: JSONArray?): List<VideoItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VideoItem(
                id = o.optString("vod_id"),
                name = o.optString("vod_name"),
                poster = Net.abs(o.optString("vod_pic")),
                remarks = o.optString("vod_remarks"),
                year = o.optString("vod_year"),
                sourceId = o.optString("source"),
                sourceName = o.optString("sourceDisplayName", o.optString("source"))
            )
        }
    }

    suspend fun detail(id: String, source: Source): Detail? = withContext(Dispatchers.IO) {
        try {
            val src = JSONObject()
                .put("id", source.id)
                .put("name", source.name)
                .put("baseUrl", source.baseUrl)
                .put("group", source.group)
                .put("enabled", source.enabled)
                .put("priority", source.priority)
                .put("searchPath", source.searchPath)
                .put("detailPath", source.detailPath)
                .put("headers", JSONObject())
            val payload = JSONObject().put("id", id).put("source", src)
            Net.client.newCall(Net.builder("/api/detail").post(Net.jsonBody(payload)).build())
                .execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (text.isEmpty()) return@withContext null
                    val root = JSONObject(text)
                    if (!root.optBoolean("success", false)) return@withContext null
                    val d = root.optJSONObject("data") ?: return@withContext null
                    val epsArr = d.optJSONArray("episodes") ?: JSONArray()
                    val eps = (0 until epsArr.length()).mapNotNull { i ->
                        val o = epsArr.optJSONObject(i) ?: return@mapNotNull null
                        Episode(
                            index = o.optInt("index", i),
                            name = o.optString("name", "第 ${i + 1} 集"),
                            url = o.optString("url")
                        )
                    }
                    Detail(
                        id = d.optString("vod_id", id),
                        name = d.optString("vod_name"),
                        poster = Net.abs(d.optString("vod_pic")),
                        remarks = d.optString("vod_remarks"),
                        year = d.optString("vod_year"),
                        area = d.optString("vod_area"),
                        typeName = d.optString("type_name"),
                        actors = d.optString("vod_actor"),
                        intro = d.optString("vod_content"),
                        episodes = eps,
                        sourceId = d.optString("source", source.id)
                    )
                }
        } catch (e: Exception) {
            null
        }
    }

    /** 直播源：服务端把 M3U 文本代理回来 */
    suspend fun channels(m3uUrl: String): List<Channel> = withContext(Dispatchers.IO) {
        if (m3uUrl.isBlank()) return@withContext emptyList()
        try {
            val url = "/api/iptv?url=" + java.net.URLEncoder.encode(m3uUrl, "UTF-8")
            Net.client.newCall(Net.builder(url).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                parseM3u(text)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseM3u(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""
        var group = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(',').trim()
                    group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                }
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> {
                    if (name.isNotEmpty()) {
                        out.add(Channel(name = name, url = line, group = group.ifBlank { "其他" }))
                        name = ""
                    }
                }
            }
        }
        return out
    }

    /** 直播播放地址走服务端代理，绕开 CORS / 防盗链 */
    fun streamProxy(url: String): String {
        return Net.abs("/api/iptv/stream?url=" + java.net.URLEncoder.encode(url, "UTF-8"))
    }
}
