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
         * 注入到网页：统一控制贴顶 sticky 导航条、悬浮圆钮与壳层设置入口。
         * 1. 纯 CSS 按 nav/header 的 sticky + top-0 类名命中顶部条，Next.js 客户端
         *    渲染、SPA 换页、React 重建节点都能立刻生效，不依赖注入时机。
         * 2. 顶部条原生已有「我的收藏」Heart 入口（/favorites），所以同名的
         *    「打开收藏夹」悬浮圆钮是重复入口，常驻 display:none 隐藏。
         * 3. 观看历史没有其它入口（/history 不存在、收藏页无历史 tab）。dock()
         *    在顶部条右侧注入一个观看历史代理圆钮，点击转发给原按钮的 click()；
         *    不搬动 React 管理的节点，避免重排时 removeChild 报错。
         * 4. 原历史悬浮圆钮改成 CSS 默认 display:none，不依赖任何 class 开关、
         *    inline style 或 dock() 的成功状态；display:none 的元素仍能响应
         *    程序化 click()，代理按钮的功能转发照常工作。只有 1.5s 内始终
         *    插不上代理按钮时，才加 kvideo-dock-failed 恢复原入口。
         * 5. 代理按钮直接复制同排原生圆钮（「设置」或「我的收藏」）的 className，
         *    尺寸、圆框、玻璃底、hover 与它们完全一致；插在「设置」之后、主题
         *    切换胶囊之前，由父级 gap 统一间距。拿不到原生样式时用 32px 兜底，
         *    图标统一 18px（宽屏 20px），不再出现没框、偏小、贴最右的孤立图标。
         * 6. 代理按钮是 nav 的后代，天然随顶部条一起上滑收起、下滑恢复。
         *    fixed 悬浮钮带 inline 坐标，用 opacity 隐藏而不用 transform 位移。
         * 7. mark() 给贴顶元素打 data-kvideo-topbar 兜底，几何判断放宽到 25%
         *    视口高；MutationObserver 用「节流立即扫 + 尾部补扫」避免高频 DOM
         *    变化把唯一的 debounce 无限推迟。sticky 元素仍占位，内容不跳动。
         * 8. 窄屏（<=520px）紧凑排版：上游左侧 logo 组是 min-w-0、右侧按钮组
         *    是 flex-shrink-0，直播入口按账号权限回来再叠加两个代理圆钮后，
         *    右侧会向左溢出压住 logo。这里不再写死圆钮尺寸，交回上游原生的
         *    w-8 h-8（32px），只把图标放大到 20px、间距收到 3px，并隐藏已被
         *    truncate 得看不见的站点标题；<=380px 的超窄屏回落到 28px/18px。
         * 9. 壳层右下角 FAB 的功能是设置 App 连接的服务器地址，与网页自带的
         *    「设置 ⚙」(/settings) 不同。这里同样停靠一个代理按钮到顶部条，
         *    并按功能生成链环/连接图标（lucide Link）以免两个齿轮混淆；点击经
         *    KVideoAndroid.openAppSettings() 打开壳层设置页。右下角 FAB 默认
         *    隐藏，不再随 dock() 的中间态来回闪；只有 1.5s 内两个代理按钮都
         *    插不上（如网页加载失败、顶部条不存在）才回落显示，保证入口不丢。
         */
        private const val CHROME_INJECT_JS = """
(function () {
  if (window.KVideoChrome) { window.KVideoChrome.sync(); return; }
  var ATTR = 'data-kvideo-topbar';
  var CLS = 'kvideo-chrome-hidden';
  var FAIL_CLS = 'kvideo-dock-failed';
  var DOCK_ATTR = 'data-kvideo-history-dock';
  var APP_DOCK_ATTR = 'data-kvideo-app-settings-dock';
  var HIDE = 'transform:translateY(-120%) !important;opacity:0 !important;' +
             'pointer-events:none !important;';
  var HIDE_FLOAT = 'opacity:0 !important;pointer-events:none !important;';

  var NAV = 'nav[class*="sticky"][class*="top-0"]';
  var sels = [
    '[' + ATTR + ']',
    NAV,
    'header[class*="sticky"][class*="top-0"]'
  ];
  // 顶部条原生已有「我的收藏」Heart 入口，这个悬浮圆钮是重复入口，常驻隐藏
  var favorites = [
    'button[aria-label="打开收藏夹"]',
    'button[title*="拖动可调整位置"][title*="收藏"]'
  ];
  // 观看历史没有其它入口，顶部条里注入代理按钮后，原悬浮圆钮默认隐藏
  var history = [
    'button[aria-label="打开观看历史"]',
    'button[title*="拖动可调整位置"][title*="历史"]'
  ];
  var floats = favorites.concat(history);
  var histSel = 'button[' + DOCK_ATTR + ']';
  var appSel = 'button[' + APP_DOCK_ATTR + ']';
  var anyDockSel = histSel + ',' + appSel;
  var dockSels = [histSel, appSel];
  var dockIconSel = histSel + ' svg,' + appSel + ' svg';
  var fallbackSel = histSel + '.kvideo-dock-fallback,' +
                    appSel + '.kvideo-dock-fallback';
  function floatNodes() {
    return document.querySelectorAll(history.join(','));
  }
  // 同排原生圆钮：优先「设置」，其次「我的收藏」，用于复制尺寸与玻璃圆框样式
  var refSels = [
    'a[aria-label="设置"]',
    'a[aria-label="我的收藏"]',
    'button[aria-label="设置"]',
    'button[aria-label="我的收藏"]'
  ];
  function prefixed(prefix, list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(prefix + list[i]);
    return out.join(',');
  }

  // 右下角 FAB 的功能是「设置 App 连接的服务器地址」，和网页自带的「设置 ⚙」
  // （/settings）不是一回事。这里按功能生成一枚链环/连接图标，避免两个齿轮混淆。
  // lucide Link 路径，线性描边风格与顶部条其它图标一致，颜色继承 currentColor。
  var APP_ICON =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true">' +
      '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>' +
      '<path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>' +
    '</svg>';

  // 窄屏一行塞不下：上游左侧 logo 组是 min-w-0，右侧按钮组是 flex-shrink-0。
  // 直播入口按账号权限回来后，再叠加两个代理圆钮，右侧会向左溢出，
  // 把「直播」圆框压到紫色 logo 上。上游圆钮本来就是 w-8 h-8（32px），
  // 上一版强行压到 28px 只为省空间，代价是整排图标偏小。现在不再覆盖圆钮
  // 尺寸，只放大内部 svg、收紧 gap 并隐藏看不见的站点标题：按钮仍由上游
  // class 统一控制，图标明显变大，整行宽度也不比 28px 版本多出多少。
  var ROW = NAV + ' div[class*="justify-between"]';
  var RIGHT = ROW + ' > div[class*="flex-shrink-0"]';
  var ROUND = RIGHT + ' a[aria-label],' +
              RIGHT + ' button[aria-label],' +
              prefixed(NAV + ' ', dockSels);
  var ROUND_ICON = RIGHT + ' svg,' + dockIconSel;
  var COMPACT =
    '@media (max-width:520px){' +
      ROW + '{gap:3px !important;}' +
      ROW + ' > a h1{display:none !important;}' +
      RIGHT + '{gap:3px !important;min-width:0;flex-shrink:1 !important;}' +
      RIGHT + ' > div{gap:2px !important;}' +
      ROUND_ICON + '{width:20px !important;height:20px !important;}' +
    '}' +
    '@media (max-width:380px){' +
      ROUND + '{' +
        'width:28px !important;height:28px !important;' +
        'min-width:28px;min-height:28px;}' +
      ROUND_ICON + '{width:18px !important;height:18px !important;}' +
      RIGHT + '{gap:2px !important;}' +
    '}';

  var style = document.createElement('style');
  style.id = 'kvideo-chrome-style';
  style.textContent =
    sels.concat(floats).join(',') +
      '{transition:transform .22s ease,opacity .22s ease !important;}' +
    prefixed('html.' + CLS + ' ', sels) + '{' + HIDE + '}' +
    prefixed('html.' + CLS + ' ', history) + '{' + HIDE_FLOAT + '}' +
    // 默认就隐藏，不依赖任何 class 开关或 dock() 的成功状态；display:none 的
    // 按钮仍可被程序化 click() 触发，代理按钮的功能转发不受影响。
    favorites.join(',') + ',' + history.join(',') + '{display:none !important;}' +
    // 只有代理按钮确实一直插不上（1.5s 后仍不存在）才恢复原入口，避免功能丢失。
    prefixed('html.' + FAIL_CLS + ' ', history) + '{display:block !important;}' +
    anyDockSel + '{' +
      'flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;' +
      'padding:0;margin:0;cursor:pointer;-webkit-tap-highlight-color:transparent;' +
    '}' +
    fallbackSel + '{' +
      'width:32px;height:32px;border-radius:999px;' +
      'border:1px solid var(--glass-border,rgba(127,127,127,.28));' +
      'background:var(--glass-bg,rgba(127,127,127,.12));' +
      'color:var(--text-color,inherit);' +
    '}' +
    dockIconSel + '{width:20px;height:20px;flex:0 0 auto;}' +
    '@media (min-width:640px){' + dockIconSel + '{width:22px;height:22px;}}' +
    COMPACT;
  (document.head || document.documentElement).appendChild(style);

  var failTimer = null;
  function armFallback() {
    if (failTimer) return;
    failTimer = setTimeout(function () {
      failTimer = null;
      if (!document.querySelector(appSel) && !document.querySelector(histSel)) {
        document.documentElement.classList.add(FAIL_CLS);
        notifyAppDock(false);
      }
    }, 1500);
  }
  function disarmFallback() {
    if (failTimer) { clearTimeout(failTimer); failTimer = null; }
    document.documentElement.classList.remove(FAIL_CLS);
    notifyAppDock(true);
  }

  // 右下角 FAB 默认隐藏。只在「确实插不上代理按钮」时才通知壳层恢复它，
  // 不再让 SPA 换页时顶部条的瞬时缺失把 FAB 放回来；通知失败允许下次重试。
  var lastDockState = null;
  function notifyAppDock(ok) {
    if (lastDockState === ok) return;
    lastDockState = ok;
    try {
      var bridge = window.KVideoAndroid;
      if (bridge && bridge.setAppSettingsDocked) bridge.setAppSettingsDocked(ok);
    } catch (e) { lastDockState = null; }
  }

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

  // 通用停靠：不搬 React 管理的节点，只在顶部条右侧新建一个代理按钮。
  function ensureButton(spec, slot, styleRef) {
    var existing = document.querySelector(spec.sel);
    if (!existing || existing.parentNode !== slot) {
      if (existing) existing.parentNode.removeChild(existing);
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute(spec.attr, '1');
      b.setAttribute('aria-label', spec.label);
      b.title = spec.title;
      b.className = styleRef ? styleRef.className : 'kvideo-dock-fallback';
      b.innerHTML = spec.html;
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        spec.onClick();
      });
      slot.appendChild(b);
      existing = b;
    } else if (styleRef && existing.className !== styleRef.className) {
      existing.className = styleRef.className;
    }
    return existing;
  }

  var appSpec = {
    sel: appSel,
    attr: APP_DOCK_ATTR,
    label: '应用设置',
    title: '应用设置 · 服务器地址',
    html: APP_ICON,
    onClick: function () {
      try {
        if (window.KVideoAndroid && window.KVideoAndroid.openAppSettings) {
          window.KVideoAndroid.openAppSettings();
        }
      } catch (e) {}
    }
  };
  var histSpec = {
    sel: histSel,
    attr: DOCK_ATTR,
    label: '观看历史',
    title: '观看历史',
    html: '',
    onClick: function () {
      var target = document.querySelector(history[0]);
      if (target) target.click();
    }
  };

  function dock() {
    var bar = null;
    var navs = document.querySelectorAll(sels[1] + ',' + sels[2]);
    for (var i = 0; i < navs.length; i++) {
      var r = navs[i].getBoundingClientRect();
      if (r.width > 100 && r.height > 20 && r.top < 200 && r.bottom > 0) {
        bar = navs[i];
        break;
      }
    }
    if (!bar) {
      // 页面切换的中间态常见，不要立刻恢复悬浮钮或 FAB；交给兜底计时器判断。
      armFallback();
      var orphans = document.querySelectorAll(anyDockSel);
      for (var j = 0; j < orphans.length; j++) {
        var orphan = orphans[j];
        if (!orphan.closest || !orphan.closest('nav,header')) {
          orphan.parentNode.removeChild(orphan);
        }
      }
      return;
    }
    var row = bar.querySelector('[class*="justify-between"]');
    var slot = row ? row.lastElementChild : null;
    if (!slot || !slot.appendChild) slot = bar.querySelector('[class*="flex"]') || bar;

    // styleRef 提供原生圆钮 class；anchor 决定插在「设置」之后、主题胶囊之前
    var styleRef = null, anchor = null;
    for (var k = 0; k < refSels.length; k++) {
      var cand = slot.querySelector(refSels[k]) || bar.querySelector(refSels[k]);
      if (!cand) continue;
      if (!styleRef) styleRef = cand;
      if (!anchor && cand.parentNode === slot) anchor = cand;
      if (styleRef && anchor) break;
    }

    var app = ensureButton(appSpec, slot, styleRef);
    var src = document.querySelector(history[0]);
    var hist = null;
    if (src) {
      histSpec.html = src.innerHTML;
      hist = ensureButton(histSpec, slot, styleRef);
    }

    // 固定顺序：设置 -> 应用设置 -> 观看历史 -> 主题切换胶囊。
    // 只在位置不对时搬运，稳态下不产生 DOM 变更，不会让观察器无限循环。
    if (anchor && anchor.nextSibling !== app) slot.insertBefore(app, anchor.nextSibling);
    if (app && hist && app.nextSibling !== hist) slot.insertBefore(hist, app.nextSibling);
    if (app && !hist && !anchor && app !== slot.lastElementChild) slot.appendChild(app);

    if (hist) disarmFallback();
    else armFallback();
  }

  var lastRun = 0;
  var tail = null;
  function schedule() {
    var now = Date.now();
    if (now - lastRun >= 120) { lastRun = now; mark(); dock(); }
    if (tail) clearTimeout(tail);
    tail = setTimeout(function () {
      tail = null; lastRun = Date.now(); mark(); dock();
    }, 200);
  }

  var hidden = false;
  window.KVideoChrome = {
    setHidden: function (v) {
      hidden = !!v;
      document.documentElement.classList.toggle(CLS, hidden);
      mark();
      dock();
    },
    sync: function () {
      document.documentElement.classList.toggle(CLS, hidden);
      mark();
      dock();
    },
    debug: function () {
      return { hidden: hidden,
               marked: document.querySelectorAll('[' + ATTR + ']').length,
               matched: document.querySelectorAll(sels[1] + ',' + sels[2]).length,
               floats: document.querySelectorAll(floats.join(',')).length,
               dockRef: document.querySelectorAll(
                 'a[aria-label="设置"],a[aria-label="我的收藏"]').length,
               floatsHidden: (function () {
                 var n = floatNodes(), count = 0;
                 for (var i = 0; i < n.length; i++) {
                   if (window.getComputedStyle(n[i]).display === 'none') count++;
                 }
                 return count;
               })(),
               fallback: document.documentElement.classList.contains(FAIL_CLS),
               appDocked: document.querySelectorAll(appSel).length,
               docked: document.querySelectorAll(histSel).length };
    }
  };

  mark();
  dock();
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
    // 右下角 FAB 默认隐藏；只有页面顶部条持续渲染不出来时才回落显示
    private var appSettingsDocked = true
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
     * 手机习惯：像浏览器地址栏，上滑内容时一起收起「网页顶部工具条」，下滑再展开。
     * 显隐只由手势决定，不跟随横竖屏或播放画面；全屏播放与设置页仍强制隐藏。
     * 右下角 FAB 默认隐藏，只在网页顶部条渲染失败时才作为回落入口显示。
     */
    private fun shouldShowSettingsButton(): Boolean =
        viewsReady &&
            !isSetupVisible() &&
            customView == null &&
            webView.visibility == View.VISIBLE &&
            !chromeHiddenByGesture &&
            !appSettingsDocked

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
        /** 顶部条代理按钮在位时隐藏右下角 FAB；持续插不上时恢复，入口不丢。 */
        @JavascriptInterface
        fun setAppSettingsDocked(docked: Boolean) {
            runOnUiThread {
                appSettingsDocked = docked
                updateSettingsButton()
            }
        }

        /** 顶部条「应用设置」代理按钮：打开壳层的服务器地址设置页。 */
        @JavascriptInterface
        fun openAppSettings() {
            runOnUiThread { showSetup(getString(R.string.status_settings_hint)) }
        }

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
