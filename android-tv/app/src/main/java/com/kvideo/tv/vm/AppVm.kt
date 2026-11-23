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
        val mime: String? = null,
        /** 直播流 */
        val isLive: Boolean = false,
        /** 备用线路：主线路出错后依次重试（上游 IPTVPlayer 是「代理失败再试直连」） */
        val fallbacks: List<String> = emptyList()
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

    /** 高级内容（成人源）是否解锁；管理员角色默认就有权限 */
    private var premiumState by mutableStateOf(store.premiumUnlocked)
    var premiumUnlocked: Boolean
        get() = premiumState
        set(v) {
            premiumState = v
            store.premiumUnlocked = v
        }

    /** 管理员本来就有 premium 权限，普通用户（viewer）必须另输高级密码 */
    val canPremium: Boolean
        get() = premiumUnlocked || accountRole == "super_admin" || accountRole == "admin"

    /** 搜索/详情只可见这些源：group=premium 的那 59 个成人源没解锁就不出现 */
    val visibleSources: List<Source>
        get() = sources.filter { canPremium || it.group != "premium" }

    /** 探活结果：实测 63 个普通源里只有十来个真的能返回内容，其余是死源 */
    var aliveSources by mutableStateOf<Set<String>>(emptySet())
    var probing by mutableStateOf(false)

    /** 真正参与搜索的源：可见 + 启用 + 探活通过（还没探过就先用全部可见的） */
    val searchableSources: List<Source>
        get() {
            val base = visibleSources.filter { it.enabled }.sortedBy { it.priority }
            return if (aliveSources.isEmpty()) base else base.filter { it.id in aliveSources }
        }

    fun probeSources() {
        if (probing) return
        val list = visibleSources.filter { it.enabled }
        if (list.isEmpty()) return
        probing = true
        viewModelScope.launch {
            val alive = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val lat = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
            list.chunked(8).forEach { chunk ->
                chunk.map { src ->
                    launch {
                        val ms = api.ping(src.baseUrl)
                        if (ms != null) {
                            alive.add(src.id)
                            lat[src.id] = ms
                        }
                    }
                }.forEach { it.join() }
            }
            aliveSources = alive.toSet()
            lineLatency = lineLatency + lat
            probing = false
        }
    }

    fun unlockPremium(pw: String) {
        viewModelScope.launch {
            if (api.unlockPremium(pw)) {
                premiumUnlocked = true
                error = null
            } else {
                error = "高级内容密码不对"
            }
        }
    }
    var recommend by mutableStateOf<List<DoubanItem>>(emptyList())
    var history by mutableStateOf(store.history())
    var favorites by mutableStateOf(store.favorites())

    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    /** 服务端回来的全部条目，按到达顺序追加（上游就是不排序、不筛） */
    var allResults by mutableStateOf<List<VideoItem>>(emptyList())

    /** 上游 settings.searchDisplayMode：普通 / 同名合并 */
    private var groupedState by mutableStateOf(store.groupedSearch)
    var grouped: Boolean
        get() = groupedState
        set(v) {
            groupedState = v
            store.groupedSearch = v
        }

    /** 只看相关：有些源（如 zuida）根本不认关键词，直接把最新更新丢回来 */
    private var relevantState by mutableStateOf(store.onlyRelevant)
    var onlyRelevant: Boolean
        get() = relevantState
        set(v) {
            relevantState = v
            store.onlyRelevant = v
        }

    /** 被「只看相关」挡掉的条数，界面要讲清楚挡了什么 */
    val hiddenUnrelated: Int
        get() = if (!onlyRelevant) 0 else allResults.size - relevantList().size

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** 片名归一化后含关键词就算相关：「庆余年 第二季」保留，「吞噬星空」丢掉 */
    private fun relevantList(): List<VideoItem> {
        val q = norm(query)
        if (q.isEmpty() || !onlyRelevant) return allResults
        return allResults.filter { norm(it.name).contains(q) }
    }

    /** 上游三个多选筛选：源 / 类型 / 语言（SourceBadges、TypeBadges、LanguageBadges） */
    var pickedSources by mutableStateOf<Set<String>>(emptySet())
    var pickedTypes by mutableStateOf<Set<String>>(emptySet())
    var pickedLangs by mutableStateOf<Set<String>>(emptySet())

    val typeBadges: List<String>
        get() = allResults.map { it.typeName }.filter { it.isNotBlank() }.distinct().take(12)

    val langBadges: List<String>
        get() = allResults.map { it.lang }.filter { it.isNotBlank() }.distinct().take(8)

    val sourceBadges: List<Pair<String, String>>
        get() = allResults.map { it.sourceId to it.sourceName.ifBlank { it.sourceId } }
            .distinctBy { it.first }.take(20)

    /** 当前点开的那一组（详情页据此给出「换源」） */
    var currentGroup by mutableStateOf<ResultGroup?>(null)

    /** sourceId → 实测延迟(ms)，组内按它排序 —— 上游 SourceSelector / VideoGrid 都是这个口径 */
    var lineLatency by mutableStateOf<Map<String, Long>>(emptyMap())

    fun pingGroup(g: ResultGroup) {
        viewModelScope.launch {
            g.items.forEach { item ->
                if (lineLatency.containsKey(item.sourceId)) return@forEach
                val base = sources.firstOrNull { it.id == item.sourceId }?.baseUrl ?: return@forEach
                launch {
                    api.ping(base)?.let { ms -> lineLatency = lineLatency + (item.sourceId to ms) }
                }
            }
        }
    }

    private fun filtered(): List<VideoItem> {
        var list = relevantList()
        if (pickedSources.isNotEmpty()) list = list.filter { it.sourceId in pickedSources }
        if (pickedTypes.isNotEmpty()) list = list.filter { it.typeName in pickedTypes }
        if (pickedLangs.isNotEmpty()) list = list.filter { it.lang in pickedLangs }
        return list
    }

    /**
     * 界面用的列表。合并开关打开时按 `vod_name.lowercase().trim()` 归并 —— 上游 VideoGrid
     * 用的就是这个 key，组内按实测延迟升序、取延迟最低的那条当代表。
     * 不做相关度过滤、不剔任何标题：那是上游没有的行为，要不要筛交给用户自己用下面的筛选条。
     * 单条分组在界面上就表现成普通卡片。
     */
    val results: List<ResultGroup>
        get() {
            val list = filtered()
            if (!grouped) return list.map { ResultGroup(it.name, it.poster, listOf(it)) }
            val map = LinkedHashMap<String, MutableList<VideoItem>>()
            list.forEach { v -> map.getOrPut(v.name.lowercase().trim()) { mutableListOf() }.add(v) }
            return map.map { (_, items) ->
                val sorted = items.sortedBy { lineLatency[it.sourceId] ?: Long.MAX_VALUE }
                ResultGroup(
                    title = sorted.first().name,
                    poster = sorted.firstOrNull { it.poster.isNotBlank() }?.poster ?: "",
                    items = sorted
                )
            }
        }

    var progressDone by mutableStateOf(0)
    var progressTotal by mutableStateOf(0)

    /** 0 自动（先直连、失败再代理）· 1 始终直连 · 2 始终代理（上游 proxyMode 的等价物） */
    private var proxyState by mutableStateOf(store.proxyMode)
    var proxyMode: Int
        get() = proxyState
        set(v) {
            proxyState = v
            store.proxyMode = v
        }

    var detail by mutableStateOf<Detail?>(null)
    var detailLoading by mutableStateOf(false)
    var detailSource by mutableStateOf<Source?>(null)
    var altSources by mutableStateOf<List<Source>>(emptyList())

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var channelGroup by mutableStateOf("全部")

    private var searchJob: Job? = null


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
                if (sources.isNotEmpty()) probeSources()
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
        pickedSources = emptySet()
        pickedTypes = emptySet()
        pickedLangs = emptySet()
        lineLatency = emptyMap()
        progressDone = 0
        progressTotal = searchableSources.size
        searching = true
        searchJob = viewModelScope.launch {
            api.search(
                q,
                // 只搜「可见 + 启用 + 探活通过」的源；成人源未解锁不参与
                searchableSources,
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
                // 先直连；播不了由播放器按 proxyMode=auto 自动切服务器代理（同上游 VideoPlayer）
                url = Net.abs(ep.url),
                title = d.name,
                subtitle = ep.name,
                videoId = d.id,
                sourceId = d.sourceId,
                epIndex = ep.index,
                episodeName = ep.name,
                mime = if (ep.url.contains(".m3u8", true))
                    androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null
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
                // 上游 IPTVPlayer 的顺序：先 /api/iptv/stream（带该频道的 UA/Referer），
                // 失败再直连 —— fallbacks 里放直连地址。
                url = api.streamProxy(c.url, c.userAgent, c.referrer),
                title = c.name,
                subtitle = "直播",
                mime = androidx.media3.common.MimeTypes.APPLICATION_M3U8,
                isLive = true,
                fallbacks = listOf(c.url) + c.routes.filter { it != c.url }
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
