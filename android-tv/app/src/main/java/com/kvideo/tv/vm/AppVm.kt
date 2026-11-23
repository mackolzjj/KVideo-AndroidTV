package com.kvideo.tv.vm

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kvideo.tv.data.Api
import com.kvideo.tv.data.Channel
import com.kvideo.tv.data.Detail
import com.kvideo.tv.data.DoubanItem
import com.kvideo.tv.data.Net
import com.kvideo.tv.data.ResultGroup
import com.kvideo.tv.data.SavedItem
import com.kvideo.tv.data.Source
import com.kvideo.tv.data.Store
import com.kvideo.tv.data.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Live : Route
    data object Saved : Route
    data object Settings : Route
    data class Detail(val id: String, val sourceId: String) : Route
    data class Player(
        val url: String,
        val title: String,
        val subtitle: String,
        val videoId: String = "",
        val sourceId: String = "",
        val epIndex: Int = 0,
        val episodeName: String = "",
        // 显式声明媒体类型：直播流走 /api/iptv/stream 代理，URI 路径里没有 .m3u8
        // 后缀，ExoPlayer 会猜成普通渐进媒体 → 直接 Source error。
        val mime: String? = null
    ) : Route
}

class AppVm(app: Application) : AndroidViewModel(app) {

    val store = Store(app)
    private val api = Api()

    var route by mutableStateOf<Route>(Route.Home)
    var backStack by mutableStateOf<List<Route>>(emptyList())

    var serverUrl by mutableStateOf(store.serverUrl)
    var password by mutableStateOf(store.password)
    /** 登录后的账户：服务器是多账户（admin / family），权限由账号决定，界面上要能看见 */
    var accountName by mutableStateOf("")
    var accountRole by mutableStateOf("")
    var liveUrl by mutableStateOf(store.liveUrl)

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var sources by mutableStateOf<List<Source>>(emptyList())
    var recommend by mutableStateOf<List<DoubanItem>>(emptyList())
    var history by mutableStateOf(store.history())
    var favorites by mutableStateOf(store.favorites())

    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    /** 服务端回来的全部条目（可能几百条，混着挂羊头卖狗肉的黑产源） */
    var allResults by mutableStateOf<List<VideoItem>>(emptyList())
    /** 默认只说人话：片名要真的含搜索词，否则 122 个源能刷出几百条垃圾 */
    var strict by mutableStateOf(true)

    /** 当前点开的那一组（详情页据此给出「换源」） */
    var currentGroup by mutableStateOf<ResultGroup?>(null)

    /**
     * 界面用这个：
     * 1. 严格模式下先剔掉片名不含搜索词的，再剔掉魔改/AI换脸这类黑产标题；
     * 2. 同名合并成一组 —— 122 个源对同一部片会给十几条，铺成网格就是一屏重复；
     * 3. 按「片名开头命中 > 中间命中 > 源多的 > 名字短的」排序。
     */
    val results: List<ResultGroup>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            val base = if (strict) allResults.filter { !isJunk(it.name) && relevance(it.name, q) >= 0 } else allResults
            val grouped = LinkedHashMap<String, MutableList<VideoItem>>()
            base.forEach { v -> grouped.getOrPut(groupKey(v.name)) { mutableListOf() }.add(v) }
            return grouped.map { (_, list) ->
                ResultGroup(
                    title = list.first().name,
                    poster = list.firstOrNull { it.poster.isNotBlank() }?.poster ?: "",
                    items = list.sortedBy { relevance(it.name, q) }
                )
            }.sortedWith(
                compareBy<ResultGroup> { relevance(it.title, q) }
                    .thenByDescending { it.items.size }
                    .thenBy { it.title.length }
            )
        }

    /** 魔改 / 换脸 / 擦边这类标题：片名里可能真带「庆余年」，但根本不是那部剧 */
    private fun isJunk(name: String): Boolean {
        val n = name.lowercase()
        return JUNK.any { n.contains(it) }
    }

    /** 同名归并用的 key：去掉「全46集 / 更新至40集」这类后缀与标点，但保留「第X季」 */
    private fun groupKey(name: String): String {
        var s = name.lowercase()
        s = Regex("(全|共|更新至)\\s*\\d+\\s*集?").replace(s, "")
        s = Regex("\\d+\\s*集").replace(s, "")
        s = Regex("[\\s\\p{Punct}【】（）()·、]+").replace(s, "")
        return s
    }
    var progressDone by mutableStateOf(0)
    var progressTotal by mutableStateOf(0)

    var detail by mutableStateOf<Detail?>(null)
    var detailLoading by mutableStateOf(false)
    var detailSource by mutableStateOf<Source?>(null)
    var altSources by mutableStateOf<List<Source>>(emptyList())

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var channelGroup by mutableStateOf("全部")

    private var searchJob: Job? = null

    private val JUNK = listOf(
        "魔改", "换脸", "ai换", "无删减", "未删减", "激情", "网红", "不雅", "流出",
        "舔", "裸", "艳照", "私拍", "福利", "成人", "三级", "黄片", "约炮", "嫩妹"
    )

    /**
     * 相关度：0 = 片名以搜索词开头，1 = 含搜索词，-1 = 不相关（严格模式下丢掉）。
     * 归一化只保留中英文数字，去掉「【】·」这类装饰，避免「庆余年 第一季」被判不匹配。
     */
    private fun relevance(name: String, query: String): Int {
        val n = normalize(name)
        val q = normalize(query)
        if (q.isEmpty()) return 1
        return when {
            n.startsWith(q) -> 0
            n.contains(q) -> 1
            else -> -1
        }
    }

    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    val loggedIn: Boolean get() = Net.hasSession()

    init {
        if (serverUrl.isNotBlank()) {
            Net.base = serverUrl
            boot()
        }
    }

    fun go(r: Route, keepStack: Boolean = true) {
        if (r is Route.Player) {
            backStack = backStack + route
            route = r
            return
        }
        if (keepStack) backStack = backStack + route
        route = r
        if (r is Route.Detail) loadDetail(r.id, r.sourceId)
        if (r is Route.Live && channels.isEmpty()) loadChannels()
    }

    fun back(): Boolean {
        val prev = backStack.lastOrNull() ?: return false
        backStack = backStack.dropLast(1)
        route = prev
        return true
    }

    fun saveServer(url: String, pw: String, live: String) {
        serverUrl = url.trim().trimEnd('/')
        password = pw
        liveUrl = live.trim()
        store.serverUrl = serverUrl
        store.password = password
        store.liveUrl = liveUrl
        Net.base = serverUrl
        Net.clearCookies()
        boot()
    }

    fun boot() {
        if (serverUrl.isBlank()) return
        Net.base = serverUrl
        viewModelScope.launch {
            busy = true
            error = null
            try {
                val acc = api.login(store.password)
                if (acc == null) {
                    error = "登录失败：密码不对（服务器按密码识别账户）"
                } else {
                    accountName = acc.name.ifBlank { acc.username }
                    accountRole = acc.role
                }
                sources = api.sources()
                recommend = api.recommend()
                if (sources.isEmpty() && recommend.isEmpty()) {
                    error = "连上了，但没拿到内容：检查服务器地址"
                }
            } catch (e: Exception) {
                error = e.message ?: "网络错误"
            } finally {
                busy = false
            }
        }
    }

    fun search(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) {
            allResults = emptyList()
            searching = false
            return
        }
        allResults = emptyList()
        progressDone = 0
        progressTotal = sources.size
        searching = true
        searchJob = viewModelScope.launch {
            api.search(
                q,
                // 只搜启用的源，按 priority 排前面先出结果
                sources.filter { it.enabled }.sortedBy { it.priority },
                // 回调来自 IO 线程，状态更新统一回主线程
                onVideos = { list ->
                    viewModelScope.launch {
                        val seen = allResults.map { it.id + "|" + it.sourceId }.toHashSet()
                        allResults = allResults + list.filter { (it.id + "|" + it.sourceId) !in seen }
                    }
                },
                onProgress = { d, t ->
                    viewModelScope.launch {
                        progressDone = d
                        progressTotal = t
                    }
                }
            )
            searching = false
        }
    }

    fun loadDetail(id: String, sourceId: String) {
        val src = sources.firstOrNull { it.id == sourceId } ?: return
        detailSource = src
        detail = null
        detailLoading = true
        viewModelScope.launch {
            detail = api.detail(id, src)
            detailLoading = false
        }
    }

    fun playDetail(epIndex: Int) {
        val d = detail ?: return
        val ep = d.episodes.getOrNull(epIndex) ?: return
        if (ep.url.isBlank()) return
        store.pushHistory(
            SavedItem(
                id = d.id,
                name = d.name,
                poster = d.poster,
                sourceId = d.sourceId,
                episodeIndex = ep.index,
                episodeName = ep.name
            )
        )
        history = store.history()
        go(
            Route.Player(
                // 片源 CDN 对手机端的裸请求回 403（同样地址服务器侧能拉），
                // 所以点播统一走服务器的 HLS 代理：/api/iptv/stream?url=
                // 它把清单里的子地址重写成 /api/iptv/stream?...，相对根路径，播放器能直接跟。
                url = api.streamProxy(ep.url),
                title = d.name,
                subtitle = ep.name,
                videoId = d.id,
                sourceId = d.sourceId,
                epIndex = ep.index,
                episodeName = ep.name,
                mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8
            )
        )
    }

    fun openGroup(g: ResultGroup, item: VideoItem) {
        currentGroup = g
        go(Route.Detail(item.id, item.sourceId))
    }

    /** 详情页换源：同一组的另一条资源 */
    fun switchSource(item: VideoItem) {
        detail = null
        loadDetail(item.id, item.sourceId)
    }

    fun playChannel(c: Channel) {
        go(
            Route.Player(
                // 直连：这些 relay 地址本身是 HLS（有的没有 .m3u8 后缀），
                // 走 /api/iptv/stream 代理反而会对无扩展名地址回 403。
                url = c.url,
                title = c.name,
                subtitle = "直播",
                mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8
            )
        )
    }

    fun loadChannels() {
        if (liveUrl.isBlank()) return
        viewModelScope.launch {
            channels = api.channels(liveUrl)
        }
    }

    fun toggleFavorite(id: String, name: String, poster: String, sourceId: String) {
        store.toggleFavorite(
            SavedItem(id = id, name = name, poster = poster, sourceId = sourceId)
        )
        favorites = store.favorites()
    }

    fun isFav(id: String, sourceId: String) = store.isFavorite(id, sourceId)

    fun clearHistory() {
        store.clearHistory()
        history = store.history()
    }
}
