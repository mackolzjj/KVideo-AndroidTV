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
        val episodeName: String = ""
    ) : Route
}

class AppVm(app: Application) : AndroidViewModel(app) {

    val store = Store(app)
    private val api = Api()

    var route by mutableStateOf<Route>(Route.Home)
    var backStack by mutableStateOf<List<Route>>(emptyList())

    var serverUrl by mutableStateOf(store.serverUrl)
    var password by mutableStateOf(store.password)
    var liveUrl by mutableStateOf(store.liveUrl)

    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var sources by mutableStateOf<List<Source>>(emptyList())
    var recommend by mutableStateOf<List<DoubanItem>>(emptyList())
    var history by mutableStateOf(store.history())
    var favorites by mutableStateOf(store.favorites())

    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var results by mutableStateOf<List<VideoItem>>(emptyList())
    var progressDone by mutableStateOf(0)
    var progressTotal by mutableStateOf(0)

    var detail by mutableStateOf<Detail?>(null)
    var detailLoading by mutableStateOf(false)
    var detailSource by mutableStateOf<Source?>(null)
    var altSources by mutableStateOf<List<Source>>(emptyList())

    var channels by mutableStateOf<List<Channel>>(emptyList())
    var channelGroup by mutableStateOf("全部")

    private var searchJob: Job? = null

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
                val ok = api.login(store.password)
                if (!ok) error = "登录失败：访问密码不对，或服务器没开鉴权"
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
            results = emptyList()
            searching = false
            return
        }
        results = emptyList()
        progressDone = 0
        progressTotal = sources.size
        searching = true
        searchJob = viewModelScope.launch {
            api.search(
                q,
                sources,
                // 回调来自 IO 线程，状态更新统一回主线程
                onVideos = { list ->
                    viewModelScope.launch {
                        val seen = results.map { it.id + "|" + it.sourceId }.toHashSet()
                        results = results + list.filter { (it.id + "|" + it.sourceId) !in seen }
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
                url = Net.abs(ep.url),
                title = d.name,
                subtitle = ep.name,
                videoId = d.id,
                sourceId = d.sourceId,
                epIndex = ep.index,
                episodeName = ep.name
            )
        )
    }

    fun playItem(item: VideoItem) {
        go(Route.Detail(item.id, item.sourceId))
    }

    fun playChannel(c: Channel) {
        go(
            Route.Player(
                url = api.streamProxy(c.url),
                title = c.name,
                subtitle = "直播"
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
