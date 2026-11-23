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
    data object Premium : Route
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
        val fallbacks: List<String> = emptyList(),
        /** 播放器里的「剧集」面板用 */
        val episodes: List<com.kvideo.tv.data.Episode> = emptyList()
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

    /** 高级内容页（上游 /premium）：分类 tags 与列表都走 premium 的两个 POST 接口 */
    var premiumTags by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var premiumVideos by mutableStateOf<List<VideoItem>>(emptyList())
    var premiumLoading by mutableStateOf(false)
    var premiumTagValue by mutableStateOf("")

    val premiumSources: List<Source>
        get() = sources.filter { it.group == "premium" && it.enabled }

    fun openPremium() {
        go(Route.Premium)
        if (canPremium && premiumTags.isEmpty()) loadPremiumTags()
    }

    // 高级内容页自己的搜索（对应官方 usePremiumHomePage：只用 enabledPremiumSources 搜）
    var premiumQuery by mutableStateOf("")
    var premiumSearching by mutableStateOf(false)
    var premiumResults by mutableStateOf<List<VideoItem>>(emptyList())

    fun searchPremium(q: String) {
        premiumQuery = q
        premiumResults = emptyList()
        if (q.isBlank()) {
            premiumSearching = false
            return
        }
        premiumSearching = true
        viewModelScope.launch {
            api.search(
                q,
                premiumSources.filter { it.enabled },
                onVideos = { list ->
                    viewModelScope.launch {
                        val seen = premiumResults.map { it.id + "|" + it.sourceId }.toHashSet()
                        premiumResults = premiumResults + list.filter { (it.id + "|" + it.sourceId) !in seen }
                    }
                },
                onProgress = { _, _ -> }
            )
            premiumSearching = false
        }
    }

    fun loadPremiumTags() {
        premiumLoading = true
        viewModelScope.launch {
            premiumTags = api.premiumTags(premiumSources)
            if (premiumTags.isNotEmpty()) loadPremiumCategory(premiumTags.first().second)
            premiumLoading = false
        }
    }

    fun loadPremiumCategory(value: String) {
        premiumTagValue = value
        premiumLoading = true
        viewModelScope.launch {
            premiumVideos = api.premiumVideos(premiumSources, value)
            premiumLoading = false
        }
    }

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

    /**
     * 官方逻辑（lib/hooks/useSubscriptionSync.ts）：
     * 同步订阅时就把返回的源**分成两份** —— group==='premium' 进 premiumSources，
     * 其余进普通 sources；而搜索只用普通 sources（useSearchAction 读 settings.sources）。
     *
     * 所以成人 / 伦理那批源永远不进搜索，只能从高级内容页（密码门）里看。
     */
    val visibleSources: List<Source>
        get() = sources.filter { it.group != "premium" }

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
                loadPremiumTags()
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

    /** 屏蔽分类（官方 settings.blockedCategories），默认「伦理」 */
    private var blockedState by mutableStateOf(store.blockedCategories)
    var blockedCategories: Set<String>
        get() = blockedState
        set(v) {
            blockedState = v
            store.blockedCategories = v
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

    /** 官方 lib/utils/search.ts：标题里至少要能命中查询词里的一对连续两个字 */
    private fun hasMinimumMatch(title: String, query: String): Boolean {
        val t = title.lowercase()
        val q = query.lowercase().trim()
        if (q.length < 2) return t.contains(q)
        for (i in 0..q.length - 2) {
            if (t.contains(q.substring(i, i + 2))) return true
        }
        return false
    }

    /** 官方 isCategoryBlocked：type_name / vod_class 命中屏蔽词就整条丢掉 */
    private fun isCategoryBlocked(v: VideoItem): Boolean {
        val cats = listOf(v.typeName, v.className).joinToString("|").lowercase()
        if (cats.isBlank() || cats == "|") return false
        return blockedCategories.any { cats.contains(it.lowercase()) }
    }

    /** 官方 calculateRelevanceScore 的移植（只保留搜索结果里拿得到的字段） */
    private fun relevanceScore(v: VideoItem, query: String): Int {
        var score = 0
        val q = query.lowercase().trim()
        val t = v.name.lowercase()
        if (t == q) return 1000
        if (t.startsWith(q)) score += 500
        if (t.contains(q)) {
            score += 200
            score += maxOf(0, 50 - t.indexOf(q) * 2)
        }
        q.split(" ").forEach { w ->
            if (w.length >= 2 && t.contains(w)) {
                score += 30
                if (t.startsWith(w)) score += 20
            }
        }
        if (t.length > 50) score -= 5
        val remarks = v.remarks.lowercase()
        if (remarks.contains("hd") || remarks.contains("1080") || remarks.contains("4k")) score += 5
        if (remarks.contains("完结") || remarks.contains("全集")) score += 3
        return maxOf(0, score)
    }

    /** 结果集：官方那两层（最小匹配 + 屏蔽分类）之后，按相关度分排序 */
    private fun relevantList(): List<VideoItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return allResults
            .filter { !isCategoryBlocked(it) }
            .filter { !onlyRelevant || hasMinimumMatch(it.name, q) }
            .sortedByDescending { relevanceScore(it, q) }
    }

    /** 上游三个多选筛选：源 / 类型 / 语言（SourceBadges、TypeBadges、LanguageBadges） */
    var pickedSources by mutableStateOf<Set<String>>(emptySet())
    var pickedTypes by mutableStateOf<Set<String>>(emptySet())
    var pickedLangs by mutableStateOf<Set<String>>(emptySet())

    // 三排 chip 也从「相关」结果里统计：只返回垃圾的源（比如 new_建安资源）
    // 不会出现在筛选条上，你一眼就能看出这次到底哪几个源真的给了东西
    val typeBadges: List<String>
        get() = relevantList().map { it.typeName }.filter { it.isNotBlank() }.distinct().take(12)

    val langBadges: List<String>
        get() = relevantList().map { it.lang }.filter { it.isNotBlank() }.distinct().take(8)

    val sourceBadges: List<Pair<String, String>>
        get() = relevantList().map { it.sourceId to it.sourceName.ifBlank { it.sourceId } }
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

    /** 从首页点某部片（豆瓣条目不可直���）→ 用它的标题直接搜出片源，而不是丢到空搜索框 */
    var autoFocusSearch by mutableStateOf(true)

    fun searchTitle(title: String) {
        autoFocusSearch = false
        query = title.trim()
        go(Route.Search)
        search(title.trim())
    }

    fun openSearchBox() {
        autoFocusSearch = true
        go(Route.Search)
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
                    androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
                episodes = d.episodes
            )
        )
    }

    /** 播放器里换集：用同一部片的另一集替换当前播放 */
    fun switchEpisode(videoId: String, sourceId: String, ep: com.kvideo.tv.data.Episode, detail: Detail, title: String) {
        store.pushHistory(
            SavedItem(
                id = detail.id,
                name = detail.name,
                poster = detail.poster,
                sourceId = detail.sourceId,
                episodeIndex = ep.index,
                episodeName = ep.name
            )
        )
        history = store.history()
        backStack = backStack.dropLast(1)
        route = Route.Player(
            url = Net.abs(ep.url),
            title = title,
            subtitle = ep.name,
            videoId = videoId,
            sourceId = sourceId,
            epIndex = ep.index,
            episodeName = ep.name,
            mime = if (ep.url.contains(".m3u8", true))
                androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
            episodes = detail.episodes
        )
    }

    /** 从高级内容页直接进详情（不属于搜索分组） */
    fun openVideo(v: VideoItem) {
        currentGroup = null
        go(Route.Detail(v.id, v.sourceId))
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

    /** 没填直播源时用 App 里内置的那份（打包进去的 610 个频道） */
    fun loadBundledChannels(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { api.channelsFromText(text) }.getOrNull()?.let {
                if (it.isNotEmpty()) channels = it
            }
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
