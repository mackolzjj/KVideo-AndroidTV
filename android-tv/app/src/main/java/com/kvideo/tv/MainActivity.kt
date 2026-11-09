package com.kvideo.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
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
         * 注入到网页：把顶部菜单重排为「内容组 + 分隔线 + 工具组 + 更多」。
         * 1. 方案 A：一行只留 5 个元素。内容组＝直播 / 我的收藏 / 观看历史，
         *    工具组＝更多 / 主题；更多里收拢退出登录、网站设置、应用设置。
         *    主题由三钮并列改为单钮循环切换，省下的空间用于放大圆钮与图标。
         * 2. 不搬动 React 管理的节点：用 CSS 隐藏 nav 里 justify-between 行的
         *    原生右侧按钮组，再往同一行插入自建菜单 DOM；所有功能通过
         *    click() 转发给原按钮，应用设置走 KVideoAndroid.openAppSettings()。
         *    display:none 的节点仍能响应程序化 click()，功能一个不删。
         * 3. 原生右侧组只在 html.kvideo-menu-ready 时隐藏；菜单插不上时移除
         *    该 class，原生按钮自动回落。1.5s 兜底再加 kvideo-dock-failed
         *    恢复历史悬浮钮与右下角 FAB，保证入口永不丢失。
         * 4. 收藏悬浮圆钮是重复入口（顶部条已有 /favorites），常驻隐藏；
         *    历史悬浮钮在菜单就位时隐藏，由顶部「观看历史」代理点击转发。
         * 5. 直播受账号 iptv_access 权限控制，原生入口不存在时菜单里也不显示。
         *    主题当前状态取原生按钮 className 的 scale-105，点击切到下一档。
         * 6. 菜单是 nav 的后代，天然随顶部条一起上滑收起、下滑恢复；收起时
         *    自动关闭「更多」弹出层。圆钮 36px / 图标 24px，宽屏 40px，
         *    380px 以下超窄屏回落 32px / 20px，避免再次挤到紫色 logo 上。
         * 7. mark() 给贴顶元素打 data-kvideo-topbar 兜底；MutationObserver 用
         *    「节流立即扫 + 尾部补扫」，syncMenu() 稳态不产生 DOM 变更。
         */
        private const val CHROME_INJECT_JS = """
(function () {
  if (window.KVideoChrome) { window.KVideoChrome.sync(); return; }

  var ATTR = 'data-kvideo-topbar';
  var NAV = 'nav[class*="sticky"][class*="top-0"]';
  var HEADER = 'header[class*="sticky"][class*="top-0"]';
  var NATIVE_NAV = NAV + ' div[class*="justify-between"] > ' +
                   'div[class*="flex-shrink-0"]:not([data-kvideo-menu])';
  var NATIVE_HEADER = HEADER + ' div[class*="justify-between"] > ' +
                      'div[class*="flex-shrink-0"]:not([data-kvideo-menu])';
  var CLS = 'kvideo-chrome-hidden';
  var READY_CLS = 'kvideo-menu-ready';
  var FAIL_CLS = 'kvideo-dock-failed';
  var NATIVE_ATTR = 'data-kvideo-native';
  var BRAND_ATTR = 'data-kvideo-brand';
  var BRAND_TEXT_ATTR = 'data-kvideo-brand-text';
  var HIDE = 'transform:translateY(-120%) !important;opacity:0 !important;' +
             'pointer-events:none !important;';

  // 两个可拖动的全局悬浮圆钮：收藏是重复入口，历史由顶部菜单代理
  var favorites = [
    'button[aria-label="打开收藏夹"]',
    'button[title*="拖动可调整位置"][title*="收藏"]'
  ];
  var history = [
    'button[aria-label="打开观看历史"]',
    'button[title*="拖动可调整位置"][title*="历史"]'
  ];
  var themeSels = [
    'button[aria-label="设为浅色主题"]',
    'button[aria-label="设为深色主题"]',
    'button[aria-label="设为系统主题"]'
  ];

  function q(sel) { return document.querySelector(sel); }
  function prefixed(prefix, list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(prefix + list[i]);
    return out.join(',');
  }
  function svg(paths) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
      'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true">' + paths + '</svg>';
  }

  // 全部按功能现画的 lucide 风格线性图标，颜色继承 currentColor
  var ICON = {
    tv: svg('<rect x="2" y="7" width="20" height="15" rx="2"/>' +
            '<polyline points="17 2 12 7 7 2"/>'),
    heart: svg('<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1' +
               'a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z"/>'),
    history: svg('<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/>' +
                 '<path d="M3 3v5h5"/><path d="M12 7v5l4 2"/>'),
    more: svg('<circle cx="5" cy="12" r="1.6" fill="currentColor" stroke="none"/>' +
              '<circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none"/>' +
              '<circle cx="19" cy="12" r="1.6" fill="currentColor" stroke="none"/>'),
    sun: svg('<circle cx="12" cy="12" r="4"/>' +
             '<path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4' +
             'M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>'),
    moon: svg('<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>'),
    monitor: svg('<rect x="2" y="3" width="20" height="14" rx="2"/>' +
                 '<path d="M8 21h8M12 17v4"/>'),
    logout: svg('<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>' +
                '<polyline points="16 17 21 12 16 7"/>' +
                '<line x1="21" y1="12" x2="9" y2="12"/>'),
    gear: svg('<circle cx="12" cy="12" r="3"/>' +
              '<path d="M12 2.5v2.3M12 19.2v2.3M2.5 12h2.3M19.2 12h2.3' +
              'M5.2 5.2l1.6 1.6M17.2 17.2l1.6 1.6M18.8 5.2l-1.6 1.6' +
              'M6.8 17.2l-1.6 1.6"/>'),
    link: svg('<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7.1-7.1l-1.7 1.7"/>' +
              '<path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7.1 7.1l1.7-1.7"/>')
  };

  var BTN = '[data-kvideo-menu] button';
  var MENU_CSS =
    // 6 个圆钮与 5 条竖线作为同级 flex 项整条等距铺开；
    // 不再分组给不同权重，首页与播放页的每个间隔完全一致。
    '[data-kvideo-menu]{display:flex;align-items:center;gap:8px;flex:1 1 auto;' +
      'position:relative;margin-left:8px;min-width:0;' +
      'justify-content:space-evenly;}' +
    // 播放页会把 --glass-border 设成透明，竖线颜色必须固定，否则两页不一致。
    '[data-kvideo-menu] .kvideo-menu-sep{width:1px;height:22px;flex:0 0 auto;' +
      'background:rgba(127,127,127,.45) !important;margin:0 2px;}' +
    BTN + '{width:36px;height:36px;min-width:36px;min-height:36px;' +
      'display:inline-flex;align-items:center;justify-content:center;' +
      'border-radius:999px !important;padding:0;margin:0;cursor:pointer;' +
      // 播放页会把 --glass-bg/--glass-border 设成透明，圆钮会退化成裸图标，
      // 和原生按钮糊在同一行；这里固定中性底色与描边，不依赖网页变量。
      'border:1px solid rgba(127,127,127,.34) !important;' +
      'background:rgba(127,127,127,.14) !important;' +
      // 不再吃网页的 --text-color：圆钮颜色随网页前景（等同 inherit），
      // 弹层的文字另给固定浅色，避免深底配深字。
      'color:inherit;font:inherit;' +
      '-webkit-tap-highlight-color:transparent;' +
      'transition:transform .16s ease,border-color .16s ease,color .16s ease;}' +
    BTN + ':active{transform:scale(.9);}' +
    BTN + '[data-kvideo-active="1"]{border-color:#3b82f6;' +
      'color:#3b82f6;}' +
    BTN + ' svg{width:24px;height:24px;flex:0 0 auto;}' +
    // 播放页对 button 有全局重置（border-radius:0），用更高特异性兜底
    '[data-kvideo-menu] button[data-kvideo-act]{' +
      'border-radius:999px !important;' +
      'border:1px solid rgba(127,127,127,.34) !important;' +
      'background:rgba(127,127,127,.14) !important;}' +
    // 当前主题档位（也用于「更多」展开态）：特异性要高于上面的兜底规则
    '[data-kvideo-menu] button[data-kvideo-act][data-kvideo-active="1"]{' +
      'border-color:#3b82f6 !important;' +
      'color:#3b82f6 !important;' +
      'background:rgba(59,130,246,.16) !important;}' +
    '[data-kvideo-menu] [hidden]{display:none !important;}' +
    // 弹层自带不透明深色底与固定文字色：播放页会把 --glass-bg 设成透明，
    // 上游浅色主题也可能只定义了 --text-color，靠网页变量必然糊字。
    '[data-kvideo-menu] .kvideo-menu-popover{' +
      'position:absolute;top:calc(100% + 10px);right:0;z-index:3000;' +
      'min-width:172px;padding:6px;display:flex;flex-direction:column;gap:2px;' +
      'border-radius:16px;' +
      'border:1px solid rgba(127,127,127,.42) !important;' +
      'background:rgba(26,27,31,.97) !important;' +
      'color:#F2F3F5 !important;' +
      'backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);' +
      'box-shadow:0 12px 32px rgba(0,0,0,.42);}' +
    '[data-kvideo-menu] .kvideo-menu-popover button{' +
      'width:100%;height:40px;min-height:40px;border:0;border-radius:10px;' +
      'background:transparent !important;color:#F2F3F5 !important;' +
      'justify-content:flex-start;gap:10px;padding:0 10px;' +
      'font-size:14px;white-space:nowrap;}' +
    '[data-kvideo-menu] .kvideo-menu-popover button:active{' +
      'background:rgba(255,255,255,.12) !important;}' +
    '[data-kvideo-menu] .kvideo-menu-popover button svg{width:18px;height:18px;}' +
    '@media (min-width:640px){' +
      BTN + '{width:40px;height:40px;min-width:40px;min-height:40px;}}' +
    // 6 个圆钮是固定 32px 不会缩，原先 5 条竖线各带 4px margin、10 个
    // 5px gap，合计约 267px，320dp 设备会把最右钮挤出去或反压 logo。
    // 压成 2px gap + 无 margin 后约 217px；超窄屏再降一档到约 199px。
    '@media (max-width:430px){' +
      '[data-kvideo-menu]{gap:2px;margin-left:4px;}' +
      '[data-kvideo-menu] .kvideo-menu-sep{margin:0;height:18px;}' +
      BTN + '{width:32px;height:32px;min-width:32px;min-height:32px;}' +
      BTN + ' svg{width:20px;height:20px;}}' +
    '@media (max-width:360px){' +
      '[data-kvideo-menu]{gap:1px;}' +
      BTN + '{width:30px;height:30px;min-width:30px;min-height:30px;}}';

  var sels = ['[' + ATTR + ']', NAV, HEADER];
  var CSS =
    sels.join(',') + '{transition:transform .22s ease,opacity .22s ease !important;}' +
    prefixed('html.' + CLS + ' ', sels) + '{' + HIDE + '}' +
    prefixed('html.' + READY_CLS + ' ', [NATIVE_NAV, NATIVE_HEADER]) +
      '{display:none !important;}' +
    prefixed('html.' + READY_CLS + ' ', ['[' + NATIVE_ATTR + ']']) +
      '{display:none !important;}' +
    // 站名文字：只藏文字元素，不碰 img/svg；font-size:0 处理容器里的裸文本节点
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_TEXT_ATTR + ']']) +
      '{display:none !important;}' +
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_ATTR + ']']) +
      '{font-size:0 !important;}' +
    favorites.join(',') + ',' + history.join(',') + '{display:none !important;}' +
    prefixed('html.' + FAIL_CLS + ' ', history) + '{display:block !important;}' +
    MENU_CSS;

  function btn(act, label, icon) {
    return '<button type="button" data-kvideo-act="' + act + '" ' +
      'aria-label="' + label + '" title="' + label + '">' + icon + '</button>';
  }
  function item(act, label, icon) {
    return '<button type="button" data-kvideo-act="' + act + '">' + icon +
      '<span>' + label + '</span></button>';
  }
  // 「直播」收进「更多」弹层：顶栏按钮过多会挤压左侧 logo。
  // 6 个圆钮之间各有一条竖线，不再分组，视觉与间距完全统一。
  function menuHtml() {
    var sep = '<span class="kvideo-menu-sep"></span>';
    return btn('favorites', '我的收藏', ICON.heart) + sep +
        btn('history', '观看历史', ICON.history) + sep +
        btn('more', '更多', ICON.more) + sep +
        btn('theme0', '浅色主题', ICON.sun) + sep +
        btn('theme1', '深色主题', ICON.moon) + sep +
        btn('theme2', '跟随系统', ICON.monitor) +
      '<div class="kvideo-menu-popover" data-kvideo-popover hidden>' +
        item('live', '直播', ICON.tv) +
        item('logout', '退出登录', ICON.logout) +
        item('websettings', '网站设置', ICON.gear) +
        item('appsettings', '应用设置', ICON.link) +
      '</div>';
  }

  var root = null;
  function ensureRoot() {
    root = q('[data-kvideo-menu]');
    if (root) return root;
    root = document.createElement('div');
    root.setAttribute('data-kvideo-menu', '1');
    root.className = 'kvideo-menu';
    root.innerHTML = menuHtml();
    root.addEventListener('click', onClick);
    return root;
  }

  function clickNative(sel) {
    var el = q(sel);
    if (!el) return false;
    try { el.click(); } catch (e) {}
    return true;
  }
  // 播放页没有「我的收藏」导航链接，但有「收藏这个视频」按钮；
  // 按可见文案兜底，避免顶栏心形变成点了没反应的死钮。
  function clickByText(txt) {
    var nodes = document.querySelectorAll('button,[role="button"],a');
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.closest && n.closest('[data-kvideo-menu]')) continue;
      var t = String(n.textContent || '').trim();
      if (t && t.length <= 20 && t.indexOf(txt) >= 0) {
        try { n.click(); return true; } catch (e) {}
      }
    }
    return false;
  }
  function themeNodes() {
    var out = [];
    for (var i = 0; i < themeSels.length; i++) out.push(q(themeSels[i]));
    return out;
  }
  function themeBg(el) {
    try {
      var bg = String(window.getComputedStyle(el).backgroundColor || '');
      var p = bg.indexOf('(');
      if (p < 0) return null;
      var parts = bg.slice(p + 1, bg.indexOf(')', p)).split(',');
      if (parts.length < 3) return null;
      return { r: parseInt(parts[0], 10) || 0, g: parseInt(parts[1], 10) || 0,
               b: parseInt(parts[2], 10) || 0,
               a: parts.length > 3 ? (parseFloat(parts[3]) || 0) : 1 };
    } catch (e) { return null; }
  }
  function isActiveTheme(el) {
    if (!el) return false;
    var cls = String(el.className || '');
    if (cls.indexOf('scale-105') >= 0) return true;
    if (el.getAttribute('aria-pressed') === 'true' ||
        el.getAttribute('aria-current') === 'true') return true;
    if (el.hasAttribute('data-active') || el.hasAttribute('data-selected')) return true;
    // 不用裸 'active'：Tailwind 的 active:xxx 变体会让三个按钮全部误判
    if (cls.indexOf('is-active') >= 0 || cls.indexOf('is-selected') >= 0 ||
        cls.indexOf('bg-blue') >= 0 || cls.indexOf('text-blue') >= 0 ||
        cls.indexOf('border-blue') >= 0 || cls.indexOf('bg-primary') >= 0 ||
        cls.indexOf('text-primary') >= 0) return true;
    return false;
  }
  function themeIndex() {
    var nodes = themeNodes();
    var flags = [], i, c, sc, best = -1, bestScore = 0;
    for (i = 0; i < nodes.length; i++) {
      flags.push(isActiveTheme(nodes[i]));
      c = themeBg(nodes[i]);
      if (c) {
        // 播放页用蓝色实心圆标当前档；按 alpha x 蓝色强度选，不依赖 class 命名
        sc = c.a * Math.max(0, c.b - Math.max(c.r, c.g));
        if (sc > bestScore) { bestScore = sc; best = i; }
      }
    }
    var hits = 0, only = -1;
    for (i = 0; i < flags.length; i++) { if (flags[i]) { hits++; only = i; } }
    if (hits === 1) return only;
    if (bestScore > 40) return best;
    return -1;
  }
  function applyTheme(i) {
    var nodes = themeNodes();
    if (nodes[i]) { try { nodes[i].click(); } catch (e) {} }
  }

  function popEl() { return root ? root.querySelector('[data-kvideo-popover]') : null; }
  function moreEl() { return root ? root.querySelector('[data-kvideo-act="more"]') : null; }
  function setPopover(open) {
    var p = popEl(), m = moreEl();
    if (p) p.hidden = !open;
    if (m) {
      m.setAttribute('aria-expanded', open ? 'true' : 'false');
      if (open) m.setAttribute('data-kvideo-active', '1');
      else m.removeAttribute('data-kvideo-active');
    }
  }
  function closePopover() { setPopover(false); }
  function togglePopover() {
    var p = popEl();
    setPopover(!!(p && p.hidden));
  }

  function onClick(ev) {
    var t = ev.target;
    var b = (t && t.closest) ? t.closest('[data-kvideo-act]') : null;
    if (!b || !root.contains(b)) return;
    ev.preventDefault();
    ev.stopPropagation();
    var act = b.getAttribute('data-kvideo-act');
    if (act === 'more') { togglePopover(); return; }
    closePopover();
    if (act === 'live') clickNative('a[aria-label="直播"]');
    else if (act === 'favorites') {
      if (!clickNative('a[aria-label="我的收藏"]') &&
          !clickNative('button[aria-label*="收藏"]') &&
          !clickByText('收藏这个视频')) clickByText('收藏');
    }
    else if (act === 'history') clickNative(history[0]);
    else if (act === 'logout') clickNative('button[aria-label="退出登录"]');
    else if (act === 'websettings') clickNative('a[aria-label="设置"]');
    else if (act === 'appsettings') {
      try {
        if (window.KVideoAndroid && window.KVideoAndroid.openAppSettings) {
          window.KVideoAndroid.openAppSettings();
        }
      } catch (e) {}
    } else if (act === 'theme0' || act === 'theme1' || act === 'theme2') {
      applyTheme(Number(act.slice(5)));
    }
  }

  // 稳态不产生 DOM 变更：只在值真的变化时才写，避免观察器自激循环
  function syncAvailability() {
    if (!root) return;
    var checks = {
      live: 'a[aria-label="直播"]',
      history: history[0],
      logout: 'button[aria-label="退出登录"]',
      websettings: 'a[aria-label="设置"]',
      theme0: themeSels[0],
      theme1: themeSels[1],
      theme2: themeSels[2]
    };
    for (var act in checks) {
      if (!Object.prototype.hasOwnProperty.call(checks, act)) continue;
      var b = root.querySelector('[data-kvideo-act="' + act + '"]');
      if (!b) continue;
      var want = q(checks[act]) ? '' : 'none';
      if (b.style.display !== want) b.style.display = want;
    }
  }
  // 功能不可用时按钮会被隐藏，同步收掉它两侧多余的竖线，
  // 避免出现落单分隔线或两条竖线连在一起。
  function syncSeparators() {
    if (!root) return;
    var kids = root.children;
    for (var i = 0; i < kids.length; i++) {
      var s = kids[i];
      if (s.className !== 'kvideo-menu-sep') continue;
      var prev = s.previousElementSibling;
      var next = s.nextElementSibling;
      var ok = prev && next &&
        prev.tagName === 'BUTTON' && next.tagName === 'BUTTON' &&
        prev.style.display !== 'none' && next.style.display !== 'none';
      var want = ok ? '' : 'none';
      if (s.style.display !== want) s.style.display = want;
    }
  }

  function syncThemeState() {
    if (!root) return;
    var idx = themeIndex();
    for (var i = 0; i < 3; i++) {
      var b = root.querySelector('[data-kvideo-act="theme' + i + '"]');
      if (!b) continue;
      var has = b.hasAttribute('data-kvideo-active');
      if (i === idx && !has) b.setAttribute('data-kvideo-active', '1');
      else if (i !== idx && has) b.removeAttribute('data-kvideo-active');
    }
  }

  var failTimer = null;
  var lastDockState = null;
  function notifyAppDock(ok) {
    if (lastDockState === ok) return;
    lastDockState = ok;
    try {
      var bridge = window.KVideoAndroid;
      if (bridge && bridge.setAppSettingsDocked) bridge.setAppSettingsDocked(ok);
    } catch (e) { lastDockState = null; }
  }
  function armFallback() {
    if (failTimer) return;
    failTimer = setTimeout(function () {
      failTimer = null;
      var m = q('[data-kvideo-menu]');
      if (!m || !m.parentNode) {
        document.documentElement.classList.remove(READY_CLS);
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

  // 播放页顶栏结构与首页不同：右侧控件组不一定带 flex-shrink-0，
  // 纯 class 选择器会漏掉它们，原生主题三档、设置齿轮和注入菜单会挤在一行。
  function pickRow(bar) {
    var rows = bar.querySelectorAll('[class*="justify-between"]');
    var best = null, score = -1;
    for (var i = 0; i < rows.length; i++) {
      var el = rows[i];
      var r = el.getBoundingClientRect();
      if (r.height < 16 || r.width < 100) continue;
      var s = r.width + el.children.length * 40;
      if (s > score) { score = s; best = el; }
    }
    return best || bar.querySelector('div[class*="flex"]') || bar;
  }
  function hideNativeControls(row, m) {
    var kids = row.children;
    var first = kids.length ? kids[0] : null;
    var i, j, el, b, all, brandBtns, hasCtl;
    for (i = 0; i < kids.length; i++) {
      el = kids[i];
      if (!el || el.nodeType !== 1 || el === m ||
          el.hasAttribute('data-kvideo-menu') || i === 0) continue;
      // 品牌 / logo 容器：含图片、首页链接或 logo class 时保留，哪怕不在行首
      if (el.querySelector && el.querySelector('img,a[href="/"]')) continue;
      if (String(el.className || '').indexOf('logo') >= 0) continue;
      hasCtl = (el.matches && el.matches('button,a[aria-label]')) ||
               (el.querySelector && el.querySelector('button,a[aria-label]'));
      if (hasCtl && !el.hasAttribute(NATIVE_ATTR)) el.setAttribute(NATIVE_ATTR, '1');
    }
    // 兜底：逐个隐藏行内原生按钮，但保住 logo / 返回按钮。
    // 1.1.5 在这里无差别打标，把本身是 button 的 logo 一起藏了。
    all = row.querySelectorAll('button');
    brandBtns = (first && first.querySelectorAll) ? first.querySelectorAll('button') : [];
    for (j = 0; j < all.length; j++) {
      b = all[j];
      if (m.contains(b) || b.hasAttribute(NATIVE_ATTR)) continue;
      if (brandBtns.length && b === brandBtns[0]) continue;
      if (b.querySelector && b.querySelector('img')) continue;
      b.setAttribute(NATIVE_ATTR, '1');
    }
  }
  // 顶栏左侧是「图片 logo + 站名文字」，窄屏下站名被截断成 "KVi…"。
  // 不写死上游 class：按「含图片的品牌容器 + 其中的纯文本叶子」识别，
  // 只隐藏文字、保住图片；没有图片的纯文字 logo 一律不动。
  function hideBrandText(row, m) {
    var kids = row.children;
    var brand = null, brandIdx = -1, i, j, el, n, txt;
    for (i = 0; i < kids.length; i++) {
      el = kids[i];
      if (!el || el.nodeType !== 1 || el === m ||
          el.hasAttribute('data-kvideo-menu')) continue;
      if (el.querySelector && el.querySelector('img,svg,picture,canvas')) {
        brand = el; brandIdx = i; break;
      }
    }
    if (!brand) return;
    if (!brand.hasAttribute(BRAND_ATTR)) brand.setAttribute(BRAND_ATTR, '1');
    var nodes = brand.querySelectorAll('*');
    for (j = 0; j < nodes.length; j++) {
      n = nodes[j];
      if (n.closest && n.closest('svg')) continue;
      if (n.children.length) continue;
      txt = String(n.textContent || '').trim();
      if (!txt || txt.length > 24) continue;
      if (!n.hasAttribute(BRAND_TEXT_ATTR)) n.setAttribute(BRAND_TEXT_ATTR, '1');
    }
    // 上游也可能把站名放在图片容器的紧邻兄弟里，而不在同一个容器内
    for (i = brandIdx + 1; i < kids.length; i++) {
      el = kids[i];
      if (!el || el.nodeType !== 1 || el === m ||
          el.hasAttribute('data-kvideo-menu')) break;
      if (el.hasAttribute(NATIVE_ATTR)) break;
      if (el.querySelector && el.querySelector('img,svg,picture,canvas')) break;
      if (el.querySelector && el.querySelector('button,a,input,select')) break;
      txt = String(el.textContent || '').trim();
      if (!txt || txt.length > 24) break;
      if (!el.hasAttribute(BRAND_TEXT_ATTR)) el.setAttribute(BRAND_TEXT_ATTR, '1');
      break;
    }
  }

  function syncMenu() {
    var bar = null;
    var navs = document.querySelectorAll(NAV + ',' + HEADER);
    for (var i = 0; i < navs.length; i++) {
      var r = navs[i].getBoundingClientRect();
      if (r.width > 100 && r.height > 20 && r.top < 200 && r.bottom > 0) {
        bar = navs[i];
        break;
      }
    }
    if (!bar) { armFallback(); return; }
    var row = pickRow(bar);
    var m = ensureRoot();
    if (m.parentNode !== row) row.appendChild(m);
    hideNativeControls(row, m);
    hideBrandText(row, m);
    document.documentElement.classList.add(READY_CLS);
    syncAvailability();
    syncSeparators();
    syncThemeState();
    disarmFallback();
  }

  var lastRun = 0;
  var tail = null;
  function schedule() {
    var now = Date.now();
    if (now - lastRun >= 120) { lastRun = now; mark(); syncMenu(); }
    if (tail) clearTimeout(tail);
    tail = setTimeout(function () {
      tail = null; lastRun = Date.now(); mark(); syncMenu();
    }, 200);
  }

  var hidden = false;
  window.KVideoChrome = {
    setHidden: function (v) {
      hidden = !!v;
      document.documentElement.classList.toggle(CLS, hidden);
      if (hidden) closePopover();
      mark();
      syncMenu();
    },
    sync: function () {
      document.documentElement.classList.toggle(CLS, hidden);
      mark();
      syncMenu();
    },
    debug: function () {
      var floats = document.querySelectorAll(history.join(','));
      var hiddenCount = 0;
      for (var i = 0; i < floats.length; i++) {
        if (window.getComputedStyle(floats[i]).display === 'none') hiddenCount++;
      }
      return {
        hidden: hidden,
        marked: document.querySelectorAll('[' + ATTR + ']').length,
        matched: document.querySelectorAll(NAV + ',' + HEADER).length,
        menu: document.querySelectorAll('[data-kvideo-menu]').length,
        menuReady: document.documentElement.classList.contains(READY_CLS),
        buttons: document.querySelectorAll('[data-kvideo-act]').length,
        floats: document.querySelectorAll(
          favorites.join(',') + ',' + history.join(',')).length,
        floatsHidden: hiddenCount,
        theme: themeIndex(),
        fallback: document.documentElement.classList.contains(FAIL_CLS),
        nativeHidden: document.querySelectorAll('[' + NATIVE_ATTR + ']').length,
        brandHidden: document.querySelectorAll('[' + BRAND_ATTR + ']').length,
        brandTextHidden: document.querySelectorAll('[' + BRAND_TEXT_ATTR + ']').length,
        favoritesDisplay: (function () {
          var b = document.querySelector('[data-kvideo-act="favorites"]');
          return b ? (b.style.display || 'shown') : 'missing';
        })(),
        seps: document.querySelectorAll('[data-kvideo-menu] > .kvideo-menu-sep').length,
        sepsVisible: (function () {
          var n = 0;
          var list = document.querySelectorAll('[data-kvideo-menu] > .kvideo-menu-sep');
          for (var i = 0; i < list.length; i++) {
            if (list[i].style.display !== 'none') n++;
          }
          return n;
        })(),
        themeButtons: document.querySelectorAll('[data-kvideo-act^="theme"]').length,
        appDocked: document.querySelectorAll('[data-kvideo-act="appsettings"]').length,
        docked: document.querySelectorAll('[data-kvideo-menu]').length
      };
    }
  };

  var style = document.createElement('style');
  style.id = 'kvideo-chrome-style';
  style.textContent = CSS;
  (document.head || document.documentElement).appendChild(style);

  document.addEventListener('click', function (ev) {
    if (root && root.contains(ev.target)) return;
    closePopover();
  }, true);

  mark();
  syncMenu();
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
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var errorDetail: TextView
    private lateinit var retryButton: Button
    private lateinit var changeUrlButton: Button
    private lateinit var prefs: android.content.SharedPreferences
    private var currentLoadUrl: String = ""
    private var pageLoadFailed = false
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var wasSetupVisibleBeforeFullscreen = false
    private var viewsReady = false
    private var chromeHiddenByGesture = false
    // 右下角 FAB 已停用；保留桥接状态供调试与后续恢复使用
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
        loadProgress = findViewById(R.id.load_progress)
        errorContainer = findViewById(R.id.error_container)
        errorDetail = findViewById(R.id.error_detail)
        retryButton = findViewById(R.id.retry_button)
        changeUrlButton = findViewById(R.id.change_url_button)
        viewsReady = true
        settingsButton.setOnClickListener {
            showSetup(getString(R.string.status_settings_hint))
        }
        retryButton.setOnClickListener {
            val url = currentLoadUrl
            if (url.isNotEmpty()) {
                loadConfiguredUrl(url)
            } else {
                showSetup(getString(R.string.status_settings_hint))
            }
        }
        changeUrlButton.setOnClickListener {
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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    pageLoadFailed = false
                    errorContainer.visibility = View.GONE
                    loadProgress.progress = 0
                    loadProgress.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    loadProgress.visibility = View.GONE
                    injectChromeScript()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != true) return
                    val reason = error?.description?.toString().orEmpty()
                    val codeText = if (reason.isBlank()) {
                        error?.errorCode?.toString().orEmpty()
                    } else {
                        reason
                    }
                    showLoadError(
                        getString(R.string.load_error_detail, codeText.ifBlank { "unknown" }),
                        request.url?.toString().orEmpty()
                    )
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame != true) return
                    val code = errorResponse?.statusCode ?: return
                    if (code < 400) return
                    showLoadError(
                        getString(R.string.load_error_http, code),
                        request.url?.toString().orEmpty()
                    )
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (!viewsReady) return
                    if (newProgress >= 100 || pageLoadFailed) {
                        loadProgress.visibility = View.GONE
                    } else {
                        loadProgress.progress = newProgress
                    }
                }

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
                    loadProgress.visibility = View.GONE
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
        currentLoadUrl = url
        pageLoadFailed = false
        errorContainer.visibility = View.GONE
        loadProgress.progress = 0
        loadProgress.visibility = View.VISIBLE
        setupContainer.visibility = View.GONE
        updateSettingsButton()
        statusText.text = getString(R.string.status_ready)
        webView.loadUrl(url)
    }

    /** 主框架加载失败时盖住黑屏，给出原因、重试入口和改地址入口。 */
    private fun showLoadError(detail: String, url: String) {
        pageLoadFailed = true
        loadProgress.visibility = View.GONE
        val text = StringBuilder(detail)
        if (url.isNotBlank()) {
            text.append('\n').append(getString(R.string.load_error_url, url))
        }
        errorDetail.text = text.toString()
        errorContainer.visibility = View.VISIBLE
    }

    private fun showSetup(message: String) {
        // 设置页盖住 WebView，进度条和错误层一并收起，避免叠在表单上
        loadProgress.visibility = View.GONE
        errorContainer.visibility = View.GONE
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
     * 右下角 FAB 已停用：无顶栏的页面（如网页设置页）会触发菜单回落，
     * 把它放出来挡住内容；应用设置入口由「更多 → 应用设置」和返回键兜底。
     */
    private fun shouldShowSettingsButton(): Boolean = false

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
        /** 记录顶部条是否停靠；右下角 FAB 已停用，此状态仅用于调试。 */
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
