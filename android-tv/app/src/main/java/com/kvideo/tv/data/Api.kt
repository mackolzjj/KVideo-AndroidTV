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

data class Account(val username: String = "", val name: String = "", val role: String = "")

class Api {

    /**
     * 登录。**服务器是按密码识别账户的**（ACCOUNTS 里每个账户各带一个密码），
     * 不需要也不应该传 username —— 传错用户名反而会登不上。
     * 登录成功后再从 session 里读回「你其实是哪个账户、什么角色」。
     */
    suspend fun login(password: String): Account? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().put("password", password)
            Net.client.newCall(Net.builder("/api/auth").post(Net.jsonBody(payload)).build()).execute().use { r ->
                Net.eatCookies(r.headers("Set-Cookie"))
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext null
                val root = JSONObject(text)
                if (!root.optBoolean("valid", false)) return@withContext null
                accountOf(root.optJSONObject("session"))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 当前会话的账户（页面重开时确认自己是谁） */
    suspend fun session(): Account? = withContext(Dispatchers.IO) {
        try {
            Net.client.newCall(Net.builder("/api/auth/session").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext null
                accountOf(JSONObject(text).optJSONObject("session"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun accountOf(s: JSONObject?): Account? {
        if (s == null) return null
        return Account(
            username = s.optString("username"),
            name = s.optString("name"),
            role = s.optString("role")
        )
    }

    /**
     * 源列表。注意 /api/config 里的 subscriptionSources **不是源本身**，是一串订阅地址
     * （形如 {name, url}），真正的源要再去 GET 那个 url 取数组 —— 老的网页客户端也是这么两级拉的。
     * 两种形状都要认：带 baseUrl 的直接用，带 url 的当订阅继续展开（只展开一层，防环）。
     */
    suspend fun sources(): List<Source> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Source>()
        fun addAll(list: List<Source>) {
            list.forEach { s -> out[s.id + "@" + s.baseUrl] = s }
        }
        try {
            Net.client.newCall(Net.builder("/api/config").build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isNotEmpty()) {
                    val raw = JSONObject(text).optString("subscriptionSources", "")
                    if (raw.isNotBlank()) {
                        val arr = JSONArray(raw)
                        val direct = mutableListOf<Source>()
                        val subUrls = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val s = sourceOf(o)
                            if (s != null) direct.add(s) else {
                                val u = o.optString("url", "")
                                if (u.isNotBlank()) subUrls.add(u)
                            }
                        }
                        addAll(direct)
                        subUrls.forEach { u -> addAll(fetchSubscription(u)) }
                    }
                }
            }
        } catch (e: Exception) {
            // 拉不到就返回已解析到的部分
        }
        out.values.toList()
    }

    /** 订阅文件就是一串源对象，直接 GET（局域网地址，不带服务器 cookie） */
    private fun fetchSubscription(url: String): List<Source> {
        return try {
            Net.client.newCall(
                Request.Builder().url(url).header("User-Agent", "KVideo-Android/2.0").build()
            ).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isBlank()) return emptyList()
                val arr = JSONArray(text)
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

    /** 源延迟探测（上游 SourceSelector 就是按这个给线路排顺序的） */
    suspend fun ping(baseUrl: String): Long? = withContext(Dispatchers.IO) {
        try {
            val body = Net.jsonBody(JSONObject().put("url", baseUrl))
            Net.client.newCall(Net.builder("/api/ping").post(body).build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (text.isEmpty()) return@withContext null
                val o = JSONObject(text)
                // 上游返回 {latency, success, timeout}；失败也会带一个 latency，别当成测速结果
                if (!o.optBoolean("success", false)) null
                else o.optLong("latency", -1L).takeIf { it >= 0 }
            }
        } catch (e: Exception) {
            null
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
