package com.kvideo.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "kvideo_tv_settings"
        private const val PREF_SERVER_URL = "server_url"
        private const val TAG = "KVideoMainActivity"
        private const val SWIPE_THRESHOLD_DP = 48f

        /**
         * 注入到网页：统一控制贴顶 sticky 导航条与「观看历史」悬浮圆钮。
         * 1. 纯 CSS 按 nav/header 的 sticky + top-0 类名命中顶部条，Next.js 客户端
         *    渲染、SPA 换页、React 重建节点都能立刻生效，不依赖注入时机。
         * 2. 顶部条原生已有「我的收藏」Heart 入口（/favorites），所以同名的
         *    「打开收藏夹」悬浮圆钮是重复入口，常驻 display:none 隐藏。
         * 3. 观看历史没有其它入口（/history 不存在、收藏页无历史 tab），保留
         *    它的悬浮圆钮，但纳入手势：上滑随顶部条一起收起、下滑恢复。
         *    fixed 元素带 inline 坐标，用 opacity 隐藏而不用 transform 位移。
         * 4. mark() 给贴顶元素打 data-kvideo-topbar 兜底，几何判断放宽到 25%
         *    视口高，容纳状态栏留白、safe-area 与外层 padding。
         * 5. MutationObserver 用「节流立即扫 + 尾部补扫」，避免高频 DOM 变化把
         *    唯一的 debounce 无限推迟。sticky 元素仍占位，内容不会突然跳动。
         */
        private const val CHROME_INJECT_JS = """
(function () {
  if (window.KVideoChrome) { window.KVideoChrome.sync(); return; }
  var ATTR = 'data-kvideo-topbar';
  var CLS = 'kvideo-chrome-hidden';
  var HIDE = 'transform:translateY(-120%) !important;opacity:0 !important;' +
             'pointer-events:none !important;';
  var HIDE_FLOAT = 'opacity:0 !important;pointer-events:none !important;';

  var sels = [
    '[' + ATTR + ']',
    'nav[class*="sticky"][class*="top-0"]',
    'header[class*="sticky"][class*="top-0"]'
  ];
  // 顶部条原生已有「我的收藏」Heart 入口，这个悬浮圆钮是重复入口，常驻隐藏
  var favorites = [
    'button[aria-label="打开收藏夹"]',
    'button[title*="拖动可调整位置"][title*="收藏"]'
  ];
  // 观看历史没有其它入口，保留它并纳入同一套手势
  var history = [
    'button[aria-label="打开观看历史"]',
    'button[title*="拖动可调整位置"][title*="历史"]'
  ];
  var floats = favorites.concat(history);
  function prefixed(prefix, list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(prefix + list[i]);
    return out.join(',');
  }
  var style = document.createElement('style');
  style.id = 'kvideo-chrome-style';
  style.textContent =
    sels.concat(floats).join(',') +
      '{transition:transform .22s ease,opacity .22s ease !important;}' +
    prefixed('html.' + CLS + ' ', sels) + '{' + HIDE + '}' +
    prefixed('html.' + CLS + ' ', history) + '{' + HIDE_FLOAT + '}' +
    favorites.join(',') + '{display:none !important;}';
  (document.head || document.documentElement).appendChild(style);

  function mark() {
    var nodes = document.querySelectorAll(
      'nav,header,[class*="navbar"],[class*="topbar"],[class*="top-0"],[class*="sticky"]');
    var w = window.innerWidth || document.documentElement.clientWidth;
    var maxTop = Math.max(160, (window.innerHeight || 800) * 0.25);
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      if (r.width < w * 0.5 || r.height < 20 || r.height > 240) continue;
      if (r.top > maxTop || r.top < -r.height) continue;
      var cls = (typeof el.className === 'string') ? el.className : '';
      var cs = window.getComputedStyle(el);
      var pinned = cs.position === 'sticky' || cs.position === 'fixed' ||
                   cls.indexOf('sticky') >= 0 || cls.indexOf('top-0') >= 0 ||
                   cls.indexOf('navbar') >= 0 || cls.indexOf('topbar') >= 0;
      if (pinned) el.setAttribute(ATTR, '1');
    }
  }

  var lastRun = 0;
  var tail = null;
  function schedule() {
    var now = Date.now();
    if (now - lastRun >= 120) { lastRun = now; mark(); }
    if (tail) clearTimeout(tail);
    tail = setTimeout(function () { tail = null; lastRun = Date.now(); mark(); }, 200);
  }

  var hidden = false;
  window.KVideoChrome = {
    setHidden: function (v) {
      hidden = !!v;
      document.documentElement.classList.toggle(CLS, hidden);
      mark();
    },
    sync: function () {
      document.documentElement.classList.toggle(CLS, hidden);
      mark();
    },
    debug: function () {
      return { hidden: hidden,
               marked: document.querySelectorAll('[' + ATTR + ']').length,
               matched: document.querySelectorAll(sels[1] + ',' + sels[2]).length,
               floats: document.querySelectorAll(floats.join(',')).length };
    }
  };

  mark();
  new MutationObserver(function () { schedule(); })
    .observe(document.documentElement, { childList: true, subtree: true });
})();
"""
    }

    private lateinit var webView: WebView
    private lateinit var setupContainer: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var settingsButton: View
    private lateinit var openButton: Button
    private lateinit var saveButton: Button
    private lateinit var prefs: android.content.SharedPreferences
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var wasSetupVisibleBeforeFullscreen = false
    private var viewsReady = false
    private var chromeHiddenByGesture = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeThresholdPx = 0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContentView(R.layout.activity_main)
        applySystemBars(false)
        setupBackNavigation()
        webView = findViewById(R.id.webview)
        setupContainer = findViewById(R.id.setup_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        urlInput = findViewById(R.id.url_input)
        statusText = findViewById(R.id.status_text)
        openButton = findViewById(R.id.open_button)
        saveButton = findViewById(R.id.save_button)
        settingsButton = findViewById(R.id.settings_button)
        viewsReady = true
        settingsButton.setOnClickListener {
            showSetup(getString(R.string.status_settings_hint))
        }
        swipeThresholdPx = SWIPE_THRESHOLD_DP * resources.displayMetrics.density
        attachSwipeToHide(webView)

        saveButton.setOnClickListener {
            openConfiguredUrl()
        }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isKeyboardConfirmAction = actionId == EditorInfo.IME_NULL ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND ||
                actionId == EditorInfo.IME_ACTION_NEXT
            val isEnterKey = event?.action == KeyEvent.ACTION_DOWN && (
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                )

            if (isKeyboardConfirmAction || isEnterKey) {
                openConfiguredUrl()
                true
            } else {
                false
            }
        }

        webView.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                databaseEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectChromeScript()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null) {
                        callback?.onCustomViewHidden()
                        return
                    }

                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }

                    wasSetupVisibleBeforeFullscreen = isSetupVisible()
                    customView = view
                    customViewCallback = callback

                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    setupContainer.visibility = View.GONE
                    updateSettingsButton()
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    applySystemBars(true)
                }

                override fun onHideCustomView() {
                    exitCustomFullscreen()
                }
            }
            addJavascriptInterface(AndroidPlayerBridge(), "KVideoAndroid")
        }

        val configuredUrl = getConfiguredUrl()
        if (configuredUrl.isNotEmpty()) {
            urlInput.setText(configuredUrl)
            loadConfiguredUrl(configuredUrl)
        } else {
            showSetup(getString(R.string.status_first_launch))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 少数设备/外接键盘仍有菜单键，保留它作为设置页快捷入口
        if (!isSetupVisible() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
            showSetup(getString(R.string.status_settings_hint))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> exitCustomFullscreen()
                    isSetupVisible() -> finish()
                    webView.canGoBack() -> webView.goBack()
                    else -> showSetup(getString(R.string.status_settings_hint))
                }
            }
        })
    }

    /**
     * 旋转不重建 Activity（configChanges 已声明）。显隐只由上滑/下滑手势决定，
     * 不再跟随横竖屏或播放画面；这里只同步当前状态与系统栏。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (viewsReady) {
            updateSettingsButton()
            applyWebChromeState()
            applySystemBars(customView != null)
        }
    }

    override fun onResume() {
        super.onResume()
        applySystemBars(customView != null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBars(customView != null)
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        dispatchPictureInPictureChange(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            applySystemBars(customView != null)
        }
    }

    override fun onDestroy() {
        exitCustomFullscreen()
        webView.destroy()
        super.onDestroy()
    }

    private fun openConfiguredUrl() {
        val normalizedUrl = normalizeUrl(urlInput.text.toString())
        if (!isValidUrl(normalizedUrl)) {
            statusText.text = getString(R.string.status_invalid_url)
            urlInput.requestFocus()
            return
        }

        prefs.edit().putString(PREF_SERVER_URL, normalizedUrl).apply()
        loadConfiguredUrl(normalizedUrl)
    }

    private fun loadConfiguredUrl(url: String) {
        chromeHiddenByGesture = false
        setupContainer.visibility = View.GONE
        updateSettingsButton()
        statusText.text = getString(R.string.status_ready)
        webView.loadUrl(url)
    }

    private fun showSetup(message: String) {
        // 用户主动进设置页说明要操作，返回网页时把工具条和齿轮都还给他
        chromeHiddenByGesture = false
        applyWebChromeState()
        val currentUrl = getConfiguredUrl()
        if (currentUrl.isNotEmpty() && urlInput.text.toString().isBlank()) {
            urlInput.setText(currentUrl)
        }

        if (currentUrl.isNotEmpty()) {
            openButton.text = getString(R.string.button_open_saved)
            openButton.setOnClickListener {
                urlInput.setText(currentUrl)
                loadConfiguredUrl(currentUrl)
            }
        } else {
            openButton.text = getString(R.string.button_exit)
            openButton.setOnClickListener {
                finish()
            }
        }

        statusText.text = message
        setupContainer.visibility = View.VISIBLE
        updateSettingsButton()
        urlInput.requestFocus()
    }

    private fun getConfiguredUrl(): String {
        val savedUrl = prefs.getString(PREF_SERVER_URL, null)?.trim().orEmpty()
        if (isValidUrl(savedUrl)) {
            return savedUrl
        }

        val defaultUrl = normalizeUrl(BuildConfig.DEFAULT_KVIDEO_URL)
        return if (isValidUrl(defaultUrl)) defaultUrl else ""
    }

    private fun isSetupVisible(): Boolean = setupContainer.visibility == View.VISIBLE

    /**
     * 手机习惯：像浏览器地址栏，上滑内容时一起收起「网页顶部工具条 + 右下角齿轮」，
     * 下滑再展开。显隐只由手势决定，不跟随横竖屏或播放画面；全屏播放与设置页仍
     * 强制隐藏（这两种场景留悬浮按钮只会挡画面）。
     */
    private fun shouldShowSettingsButton(): Boolean =
        viewsReady &&
            !isSetupVisible() &&
            customView == null &&
            webView.visibility == View.VISIBLE &&
            !chromeHiddenByGesture

    private fun updateSettingsButton() {
        settingsButton.visibility =
            if (shouldShowSettingsButton()) View.VISIBLE else View.GONE
    }

    /** 只观察手势、不消费事件，网页自身的滚动/点击/横滑/拖动进度条都不受影响。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToHide(target: WebView) {
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (viewsReady && !isSetupVisible() && customView == null &&
                        webView.visibility == View.VISIBLE) {
                        val dx = event.x - swipeStartX
                        val dy = event.y - swipeStartY
                        // 只认明显垂直、且超过 48dp 的滑动；横滑切换/拖进度条不误触发
                        if (abs(dx) < abs(dy) * 0.8f) {
                            if (dy <= -swipeThresholdPx) {
                                setChromeHidden(true)
                            } else if (dy >= swipeThresholdPx) {
                                setChromeHidden(false)
                            }
                        }
                    }
                }
            }
            false
        }
    }

    private fun setChromeHidden(hidden: Boolean) {
        if (chromeHiddenByGesture == hidden) return
        chromeHiddenByGesture = hidden
        updateSettingsButton()
        applyWebChromeState()
    }

    /** 页面（重新）加载完成后注入样式与识别逻辑，并立即同步当前手势状态。 */
    private fun injectChromeScript() {
        val js = CHROME_INJECT_JS +
            "\nif(window.KVideoChrome){window.KVideoChrome.setHidden(" +
            chromeHiddenByGesture + ");}"
        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to inject toolbar auto-hide script", error)
            }
        }
    }

    private fun applyWebChromeState() {
        val js = "if(window.KVideoChrome){window.KVideoChrome.setHidden(" +
            chromeHiddenByGesture + ");}"
        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to sync toolbar visibility", error)
            }
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        return withScheme.removeSuffix("/")
    }

    private fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /**
     * 手机端：平时显示状态栏与导航栏（可下拉通知、使用手势返回），
     * 只在网页视频进入全屏时隐藏系统栏并保持屏幕常亮。
     */
    private fun applySystemBars(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.setAppearanceLightStatusBars(false)
        controller.setAppearanceLightNavigationBars(false)

        if (immersive) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun exitCustomFullscreen() {
        val currentCustomView = customView ?: return
        fullscreenContainer.removeView(currentCustomView)
        fullscreenContainer.visibility = View.GONE
        customView = null
        webView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val restoreSetup = wasSetupVisibleBeforeFullscreen
        if (restoreSetup) {
            setupContainer.visibility = View.VISIBLE
        }
        updateSettingsButton()
        applyWebChromeState()
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        wasSetupVisibleBeforeFullscreen = false
        applySystemBars(false)
    }

    private fun isPictureInPictureSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }

        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun dispatchPictureInPictureChange(isInPictureInPictureMode: Boolean) {
        val js = """
            window.dispatchEvent(new CustomEvent('kvideo-android-pip-change', {
                detail: { inPictureInPicture: ${if (isInPictureInPictureMode) "true" else "false"} }
            }));
        """.trimIndent()

        webView.post {
            try {
                webView.evaluateJavascript(js, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to dispatch Picture-in-Picture change event", error)
            }
        }
    }

    private fun createSourceRectHint(left: Int, top: Int, right: Int, bottom: Int): Rect? {
        if (right <= left || bottom <= top) {
            return null
        }

        val density = resources.displayMetrics.density
        return Rect(
            (left * density).roundToInt(),
            (top * density).roundToInt(),
            (right * density).roundToInt(),
            (bottom * density).roundToInt()
        )
    }

    private inner class AndroidPlayerBridge {
        @JavascriptInterface
        fun isPictureInPictureSupported(): Boolean = this@MainActivity.isPictureInPictureSupported()

        @JavascriptInterface
        fun enterPictureInPicture(
            width: Int,
            height: Int,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int
        ): Boolean {
            if (!this@MainActivity.isPictureInPictureSupported()) {
                return false
            }

            val didEnterPiP = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            runOnUiThread {
                try {
                    val builder = android.app.PictureInPictureParams.Builder()
                    if (width > 0 && height > 0) {
                        builder.setAspectRatio(Rational(width, height))
                    }
                    createSourceRectHint(left, top, right, bottom)?.let(builder::setSourceRectHint)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setSeamlessResizeEnabled(true)
                    }
                    didEnterPiP.set(enterPictureInPictureMode(builder.build()))
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Failed to enter Picture-in-Picture mode", error)
                } finally {
                    latch.countDown()
                }
            }

            return try {
                latch.await(1500, TimeUnit.MILLISECONDS)
                didEnterPiP.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "Interrupted while waiting for Picture-in-Picture result", error)
                false
            }
        }
    }
}
