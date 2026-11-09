package com.kvideo.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
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
import android.widget.ImageButton
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
        /** 锁按钮空闲多久后自动隐藏；触屏或按任意键重新计时并显示。 */
        private const val LOCK_TOGGLE_IDLE_MS = 5_000L

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
         * 8. 播放页左右快退/快进圆钮按 aria-label 打标后缩小，保留 44px 热区。
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

  // 播放页左右快退/快进圆钮：网页原生按钮 aria-label 是「后退 N 秒」/「前进 N 秒」。
  // 1.1.15 用 @media(min-width:768px) 分档，WebView 宽度不到 768px 时窄屏档
  // 按钮仍是 40px、图标只小 2px，观感上等于没生效。这里全部宽度统一缩到
  // 按钮 28px / 图标 16px（相对原生 64/32 缩小一半以上），不再依赖断点；
  // CSS + 内联 important 双保险。
  // 快退/快进圆钮保持隐藏：1.1.22 起水平滑动改为跟手拖进度，不再点这两个按钮。
  var SEEK_CSS =
    '[data-kvideo-seek="1"]{display:none !important;}' +
    '[data-kvideo-seek="1"][data-kvideo-seek-show="1"]{' +
      'display:flex !important;align-items:center !important;' +
      'justify-content:center !important;' +
      // 锁钮现在常驻画面左侧中部，闪现的快退/快进上移一档，避免两个圆钮重叠。
      'transform:translateY(-64px) !important;}' +
    '[data-kvideo-seek="1"]{width:28px !important;height:28px !important;' +
      'min-width:28px !important;min-height:28px !important;' +
      'max-width:28px !important;max-height:28px !important;' +
      'padding:0 !important;box-sizing:border-box !important;' +
      'background:rgba(0,0,0,.22) !important;' +
      'backdrop-filter:none !important;-webkit-backdrop-filter:none !important;}' +
    '[data-kvideo-seek="1"]:hover{transform:translateY(-64px) !important;}' +
    '[data-kvideo-seek="1"] svg{width:16px !important;height:16px !important;' +
      'min-width:16px !important;min-height:16px !important;' +
      'max-width:16px !important;max-height:16px !important;}';

  // 倍速圆钮从右上角挪到底栏后，外观必须与底栏 .btn-icon 完全一致：
  // 40px 圆、rgba(255,255,255,.10) 底、1px rgba(255,255,255,.20) 描边、白字。
  // 底色/描边/blur 压成固定值（不依赖网页变量），但 1.1.29 起把 hover/active
  // 的缩放动画补回来：三点按钮没被我们接管 transform，一直保留网页原生的
  // scale-110 反馈，倍速钮之前被 transform:none 压死，点起来是「死」的。
  var RATE_ATTR = 'data-kvideo-rate';
  var RATE_WRAP_ATTR = 'data-kvideo-rate-wrap';
  var RATE_CSS =
    '[' + RATE_ATTR + '="1"]{width:40px !important;height:40px !important;' +
      'min-width:40px !important;min-height:40px !important;' +
      'max-width:40px !important;max-height:40px !important;' +
      'padding:0 !important;margin:0 !important;box-sizing:border-box !important;' +
      'border-radius:999px !important;background:rgba(255,255,255,.10) !important;' +
      'border:1px solid rgba(255,255,255,.20) !important;color:#FFFFFF !important;' +
      'font-size:13px !important;font-weight:500 !important;line-height:1 !important;' +
      'backdrop-filter:none !important;-webkit-backdrop-filter:none !important;' +
      'box-shadow:none !important;pointer-events:auto !important;' +
      'display:flex !important;align-items:center !important;justify-content:center !important;' +
      // 常态仍锁死 transform（网页可能拿 translate 做定位），只加过渡曲线；
      // hover/active 选择器特异性更高，同为 important 时会覆盖这一条。
      'transform:none !important;' +
      'transition:transform .16s ease,background-color .16s ease,border-color .16s ease !important;}' +
    '[' + RATE_ATTR + '="1"]:hover{' +
      'transform:scale(1.1) !important;background:rgba(255,255,255,.20) !important;}' +
    '[' + RATE_ATTR + '="1"]:active{' +
      'transform:scale(.95) !important;background:rgba(255,255,255,.20) !important;}' +
    // 移动的是右上角那个 absolute 定位容器，left/top 由 moveRateButton 内联写入。
    // Tailwind 的 top-8 / right-6 必须先置 auto，否则两个方向会互相拉扯。
    '[' + RATE_WRAP_ATTR + '="1"]{top:auto !important;right:auto !important;' +
      'bottom:auto !important;width:40px !important;height:40px !important;' +
      'min-width:0 !important;overflow:visible !important;z-index:45 !important;}';

  // 网页自带的「向上还是向下展开」判定，在倍速钮被移到底栏后会算错：
  // 菜单从按钮下沿往下弹，被屏幕底边裁到只剩 0.5x / 0.75x。
  // 这里接管定位：固定在按钮正上方，右边缘对齐按钮，超高时可滚动。
  // position/top/right/transform/max-height 由样式表 important 兜底
  // （React 每次 w() 都会重设内联 style），left/bottom 由 JS 动态写入。
  var SPEED_MENU_ATTR = 'data-kvideo-speed-menu';
  // 网页给速度菜单一个很大的固有宽度（横屏下接近半屏），1.1.27 起一并接管尺寸：
  // 按内容收到 136px，按钮跟着容器铺满、字号同步缩小，避免挡住画面。
  var SPEED_MENU_CSS =
    '[' + SPEED_MENU_ATTR + '="1"]{position:fixed !important;' +
      'top:auto !important;right:auto !important;transform:none !important;' +
      'width:136px !important;min-width:0 !important;max-width:40vw !important;' +
      'box-sizing:border-box !important;padding:6px !important;' +
      'max-height:60vh !important;overflow-y:auto !important;' +
      'z-index:2147483647 !important;pointer-events:auto !important;}' +
    '[' + SPEED_MENU_ATTR + '="1"] > button{' +
      'width:100% !important;min-width:0 !important;max-width:100% !important;' +
      'box-sizing:border-box !important;padding:7px 4px !important;' +
      'font-size:15px !important;line-height:1.25 !important;' +
      'white-space:nowrap !important;}';

  // 倍速菜单的 transform 被接管成 none（left/bottom 定位需要），网页原生的
  // zoom-in 弹出动画因此跟着失效，只剩「啪」地瞬间出现；三点面板没被接管，
  // 一直是柔和淡入。这里补一套同款：容器只动 opacity（绝不碰 transform，
  // 否则菜单位置会跑偏），条目再叠一点 translateY + scale 做放大感。
  // 用 !important 的 animation 覆盖网页自带的 animate-in，避免两套叠播。
  var SPEED_MENU_ANIM =
    '@keyframes kvideoSpeedFade{from{opacity:0;}to{opacity:1;}}' +
    '@keyframes kvideoSpeedItem{' +
      'from{opacity:0;transform:translateY(6px) scale(.96);}' +
      'to{opacity:1;transform:none;}}' +
    '[' + SPEED_MENU_ATTR + '="1"]{' +
      'animation:kvideoSpeedFade .18s ease-out !important;}' +
    '[' + SPEED_MENU_ATTR + '="1"] > button{' +
      'animation:kvideoSpeedItem .2s cubic-bezier(.16,1,.3,1) backwards !important;}';

  // 播放页「更多」三点原本贴在左上角，和绿色 1080P 画质标签挤在一起。
  // 1.1.18 已把右上角 top-8 right-6 的倍速容器整体挪到底栏，右上角空出来。
  // 这里只翻转外层定位容器的左右方向（left-6 -> right-6），DOM 层级不动，
  // 网页菜单的三套定位算法会自动按「按钮在右半屏」算成 align:right，
  // 用 translateX(-100%) 向左展开，不会跑出屏幕，无需再接管弹层。
  var MORE_ATTR = 'data-kvideo-more';
  var MORE_WRAP_ATTR = 'data-kvideo-more-wrap';
  var MORE_CSS =
    '[' + MORE_WRAP_ATTR + '="1"]{' +
      'left:auto !important;right:24px !important;top:32px !important;}';

  // 三点弹出的设置面板原本卡片接近三分之一屏宽，挡画面。1.1.28 起接管尺寸：
  // max-content 让宽度贴合文字内容，zoom 等比缩小字号/间距/图标/内边距——
  // 比逐条改子元素的 padding、font-size 稳妥，也不会碰网页用来向左展开的
  // transform（那个一旦覆盖，菜单就会跑出屏幕或贴错位置）。
  var MORE_MENU_ATTR = 'data-kvideo-more-menu';
  var MORE_MENU_CSS =
    '[' + MORE_MENU_ATTR + '="1"]{' +
      'zoom:0.82 !important;width:max-content !important;min-width:0 !important;' +
      'max-width:300px !important;box-sizing:border-box !important;}';

  // 手势提示浮层：跟手拖进度时显示目标时间/偏移/进度条，音量亮度时显示百分比。
  // 自建 DOM 挂在 body 上，不受 React 管理，也不吃网页的 CSS 变量。
  var HUD_CSS =
    '[data-kvideo-hud]{position:fixed !important;left:50% !important;top:30% !important;' +
      'transform:translate(-50%,-50%) !important;min-width:176px !important;' +
      'padding:14px 20px 12px !important;box-sizing:border-box !important;' +
      'background:rgba(20,22,26,.92) !important;' +
      'border:1px solid rgba(255,255,255,.18) !important;border-radius:14px !important;' +
      'box-shadow:0 8px 28px rgba(0,0,0,.35) !important;color:#FFFFFF !important;' +
      'text-align:center !important;z-index:2147483647 !important;' +
      'pointer-events:none !important;display:none !important;font-family:sans-serif !important;}' +
    '.kv-hud-main{font-size:22px !important;font-weight:600 !important;' +
      'line-height:1.2 !important;font-variant-numeric:tabular-nums !important;' +
      'color:#FFFFFF !important;}' +
    '.kv-hud-sub{font-size:12px !important;color:rgba(255,255,255,.68) !important;' +
      'margin-top:4px !important;line-height:1.3 !important;}' +
    '.kv-hud-track{height:4px !important;border-radius:2px !important;' +
      'margin-top:10px !important;background:rgba(255,255,255,.20) !important;' +
      'overflow:hidden !important;}' +
    '.kv-hud-track i{display:block !important;height:100% !important;width:0 !important;' +
      'background:#3b82f6 !important;border-radius:2px !important;}';

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
    MENU_CSS + SEEK_CSS + RATE_CSS + SPEED_MENU_CSS + SPEED_MENU_ANIM + MORE_CSS + MORE_MENU_CSS + HUD_CSS;

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

  // 内联 important 样式：优先级高于网页任何 stylesheet（含 Tailwind v4 的
  // @layer 与 md: 断点），避免 1.1.15 那种「断点没命中就几乎没变化」。
  // 只在值确实不同时才写，配合下面的 attributeFilter 避开自触发循环。
  function forceStyle(el, props) {
    for (var k in props) {
      if (!Object.prototype.hasOwnProperty.call(props, k)) continue;
      if (el.style.getPropertyValue(k) === props[k] &&
          el.style.getPropertyPriority(k) === 'important') continue;
      el.style.setProperty(k, props[k], 'important');
    }
  }
  // 只把尺寸写成内联：CSSOM 会把 '0' / 'rgba(0,0,0,.22)' 规范化成
  // '0px' / 'rgba(0, 0, 0, 0.22)'，若把它们放进比较表会导致每秒重复写入。
  // padding / 底色 / hover 缩放交给上面注入的 !important 规则负责。
  var BTN_PROPS = {
    'width': '28px', 'height': '28px',
    'min-width': '28px', 'min-height': '28px',
    'max-width': '28px', 'max-height': '28px'
  };
  var SVG_PROPS = {
    'width': '16px', 'height': '16px',
    'min-width': '16px', 'min-height': '16px',
    'max-width': '16px', 'max-height': '16px'
  };

  // 结构兜底：上游改了文案也能命中。父容器是贴左/贴右的整高绝对定位层，
  // 按钮本身是 rounded-full 且用 w-10 / md:w-16 那套尺寸类，内含 svg。
  // 底部控制条按钮与中间播放大按钮都不满足父容器条件，不会误伤。
  function looksLikeSeek(b) {
    if (!b.querySelector || !b.querySelector('svg')) return false;
    var c = String(b.className || '');
    if (c.indexOf('rounded-full') < 0) return false;
    if (c.indexOf('md:w-16') < 0 && c.indexOf('w-10') < 0) return false;
    var p = b.parentElement;
    var pc = p ? String(p.className || '') : '';
    return pc.indexOf('absolute left-0 top-0 bottom-0') >= 0 ||
           pc.indexOf('absolute right-0 top-0 bottom-0') >= 0;
  }
  function labelIsSeek(label) {
    var s = String(label || '').trim();
    var low = s.toLowerCase();
    var zh = s.indexOf('后退') === 0 || s.indexOf('前进') === 0 ||
             s.indexOf('快退') === 0 || s.indexOf('快进') === 0;
    var en = low.indexOf('back ') === 0 || low.indexOf('forward ') === 0 ||
             low.indexOf('rewind ') === 0;
    if (!zh && !en) return false;
    for (var j = 0; j < s.length; j++) {
      if (s.charAt(j) >= '0' && s.charAt(j) <= '9') return true;
    }
    return false;
  }

  // 全量扫描：每次都对已打标元素重刷内联样式，React 重建 DOM 后也能自愈。
  function shrinkSeekButtons() {
    var list = document.querySelectorAll('button');
    for (var i = 0; i < list.length; i++) {
      var b = list[i];
      if (b.closest && b.closest('[data-kvideo-menu]')) continue;
      var marked = b.getAttribute('data-kvideo-seek') === '1';
      if (!marked && !labelIsSeek(b.getAttribute('aria-label')) &&
          !looksLikeSeek(b)) continue;
      if (!marked) {
        b.setAttribute('data-kvideo-seek', '1');
        b.setAttribute('data-kvideo-seek-dir', seekDirection(b));
      }
      forceStyle(b, BTN_PROPS);
      var svg = b.querySelector('svg');
      if (svg) forceStyle(svg, SVG_PROPS);
    }
  }

  // 打标时判定方向：优先 aria-label 文案，回落看父容器贴左还是贴右。
  function seekDirection(b) {
    var s = String(b.getAttribute('aria-label') || '').trim();
    var low = s.toLowerCase();
    if (s.indexOf('后退') === 0 || s.indexOf('快退') === 0 ||
        low.indexOf('back ') === 0 || low.indexOf('rewind ') === 0) return 'back';
    if (s.indexOf('前进') === 0 || s.indexOf('快进') === 0 ||
        low.indexOf('forward ') === 0) return 'fwd';
    var p = b.parentElement;
    var pc = p ? String(p.className || '') : '';
    return pc.indexOf('absolute right-0') >= 0 ? 'fwd' : 'back';
  }

  // 1.1.22：左右滑动 = 跟手拖进度条（连续定位，不再是固定 10 秒跳跃）；
  // 纵向滑动按起点左右分区 = 音量 / 亮度。两者按首次位移的主方向二选一。
  // 网页自己的进度条拖动机制（isDraggingProgressRef）是：touchstart 置标志，
  // document 上的 touchmove 只更新 React 时间显示，touchend 才真正 seek。
  // 这里合成同构的 touch 事件喂给它，让原生进度条/时间文字实时跟着手指走；
  // 合成失败也不影响核心功能 —— 松手时用 video.currentTime 兜底定位。
  var SCRUB_MIN_PX = 10;
  var AXIS_RATIO = 1.2;
  var VERT_STEP_PX = 24;
  var scrub = null;
  var synthDispatching = false;
  var hudTimer = null;

  function kvClamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function kvTime(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
    function p(n) { return (n < 10 ? '0' : '') + n; }
    return h > 0 ? h + ':' + p(m) + ':' + p(x) : p(m) + ':' + p(x);
  }
  function kvHud() {
    var h = document.getElementById('kvideo-gesture-hud');
    if (h) return h;
    h = document.createElement('div');
    h.id = 'kvideo-gesture-hud';
    h.setAttribute('data-kvideo-hud', '1');
    h.innerHTML = '<div class="kv-hud-main"></div>' +
      '<div class="kv-hud-sub"></div><div class="kv-hud-track"><i></i></div>';
    (document.body || document.documentElement).appendChild(h);
    return h;
  }
  function showHud(main, sub, ratio) {
    if (hudTimer) { clearTimeout(hudTimer); hudTimer = null; }
    var h = kvHud();
    h.querySelector('.kv-hud-main').textContent = main;
    h.querySelector('.kv-hud-sub').textContent = sub;
    h.querySelector('.kv-hud-track i').style.width =
      Math.round(kvClamp(ratio, 0, 1) * 100) + '%';
    // 注意：HUD_CSS 里 display:none 带了 !important，必须用内联 important 才能覆盖。
    h.style.setProperty('display', 'block', 'important');
  }
  function hideHud(delay) {
    if (hudTimer) clearTimeout(hudTimer);
    hudTimer = setTimeout(function () {
      var h = document.getElementById('kvideo-gesture-hud');
      if (h) h.style.setProperty('display', 'none', 'important');
      hudTimer = null;
    }, delay || 0);
  }
  // 用普通 Event + 自定义 touches 合成，避免依赖 Touch 构造函数；
  // 标志位防止派发到 document 时又回到本脚本的 capture 监听造成递归。
  function synthTouch(type, targetEl, clientX, clientY) {
    try {
      var e = new Event(type, { bubbles: false, cancelable: true });
      var t = { identifier: 1, target: targetEl, clientX: clientX, clientY: clientY,
                pageX: clientX, pageY: clientY, screenX: clientX, screenY: clientY };
      Object.defineProperty(e, 'touches', { value: [t], configurable: true });
      Object.defineProperty(e, 'targetTouches', { value: [t], configurable: true });
      Object.defineProperty(e, 'changedTouches', { value: [t], configurable: true });
      synthDispatching = true;
      try { targetEl.dispatchEvent(e); } finally { synthDispatching = false; }
      return true;
    } catch (err) { synthDispatching = false; return false; }
  }
  function findProgressBar() {
    var b = document.querySelector('.slider-track');
    if (b) return b;
    var t = document.querySelector('.slider-thumb');
    return t ? t.parentElement : null;
  }
  function videoInfo() {
    var v = document.querySelector('video');
    if (!v) return null;
    var d = v.duration;
    if (!isFinite(d) || d <= 1) return null;
    return { v: v, d: d, t: v.currentTime || 0 };
  }
  // 网页 Portal 弹层（右上角三点「更多」菜单、倍速菜单）自身可滚动，手指在
  // 上面上下滑的本意是滚菜单，不能同时被当成左右半屏的音量/亮度手势，
  // 更不能 preventDefault 把菜单滚动吃掉。不依赖随时会变的 Tailwind class，
  // 用「浮层定位 + 尺寸够大 + 可滚动 / 含菜单文案」识别并打标记。
  var OVERLAY_MENU_ATTR = 'data-kvideo-overlay-menu';
  var OVERLAY_TEXT_HINT = '复制链接';
  function looksLikeOverlayMenu(el) {
    if (!el || el.nodeType !== 1 || el === document.body ||
        el === document.documentElement) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 120 || r.height < 120) return false;
    // 铺满视口的是播放器根容器/遮罩，绝不可能是弹出菜单；误标会让手势全废。
    var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
    if (vw > 0 && vh > 0 && r.width >= vw * 0.9 && r.height >= vh * 0.9) return false;
    if (el.querySelector && el.querySelector('video')) return false;
    var cs;
    try { cs = window.getComputedStyle(el); } catch (e) { return false; }
    if (!cs || (cs.position !== 'fixed' && cs.position !== 'absolute')) return false;
    if (cs.display === 'none' || cs.visibility === 'hidden' ||
        cs.opacity === '0' || cs.pointerEvents === 'none') return false;
    if (cs.overflowY === 'auto' || cs.overflowY === 'scroll' ||
        el.scrollHeight > el.clientHeight + 8) return true;
    if (r.width >= 140 && r.height >= 140 &&
        String(el.textContent || '').indexOf(OVERLAY_TEXT_HINT) >= 0) return true;
    return false;
  }
  // 每次 touchstart 刷一次（频率低），touchmove 只查属性，避免每帧 getComputedStyle。
  function markOverlayMenus() {
    var l = document.querySelectorAll('div');
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      if (e.hasAttribute(OVERLAY_MENU_ATTR)) {
        var rr = e.getBoundingClientRect();
        if (rr.width < 1 || rr.height < 1) e.removeAttribute(OVERLAY_MENU_ATTR);
        continue;
      }
      if (looksLikeOverlayMenu(e)) e.setAttribute(OVERLAY_MENU_ATTR, '1');
    }
  }
  // 三点「更多」设置面板：单独标记，供尺寸 CSS 与手势闸门使用。
  // 只认含「复制链接」的浮层（该菜单独有文案），取最内层/最窄的一个，
  // 防止父子同时命中造成二次缩放；菜单关闭后清掉标记。
  function markMoreMenu() {
    var l = document.querySelectorAll('div');
    var best = null, bw = 1e9;
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      var marked = e.hasAttribute(MORE_MENU_ATTR);
      var r = e.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      // zoom 后菜单视觉尺寸会变小；已标记的放宽下限，避免「标记→缩小→
      // 判太小→清标记→恢复」的闪烁循环。
      if (!marked && (r.width < 110 || r.height < 110)) continue;
      if (String(e.textContent || '').indexOf(OVERLAY_TEXT_HINT) < 0) continue;
      var cs;
      try { cs = window.getComputedStyle(e); } catch (err) { continue; }
      if (!cs || (cs.position !== 'fixed' && cs.position !== 'absolute')) continue;
      if (cs.display === 'none' || cs.visibility === 'hidden' ||
          cs.opacity === '0') continue;
      if (r.width < bw) { bw = r.width; best = e; }
    }
    var olds = document.querySelectorAll('[' + MORE_MENU_ATTR + '="1"]');
    for (var j = 0; j < olds.length; j++) {
      if (olds[j] !== best) olds[j].removeAttribute(MORE_MENU_ATTR);
    }
    if (best && best.getAttribute(MORE_MENU_ATTR) !== '1') {
      best.setAttribute(MORE_MENU_ATTR, '1');
    }
  }
  function elInOverlay(el) {
    var hops = 0;
    while (el && el !== document.body && hops < 24) {
      if (el.hasAttribute) {
        if (el.hasAttribute(OVERLAY_MENU_ATTR) ||
            el.hasAttribute(SPEED_MENU_ATTR) ||
            el.hasAttribute(MORE_MENU_ATTR) ||
            el.hasAttribute('data-kvideo-popover')) return true;
        var role = el.getAttribute('role');
        if (role === 'menu' || role === 'dialog' || role === 'listbox') return true;
      }
      el = el.parentElement;
      hops++;
    }
    return false;
  }
  // 菜单弹出期间整体暂停手势：即使手指从菜单外的画面起滑也不该调音量/亮度。
  // 只认「明确是菜单」的浮层（倍速菜单 / 含三点菜单文案），并校验真实可见，
  // 避免误伤字幕层、弹幕层等常驻浮层，也避免菜单 DOM 残留时把手势永久关掉。
  function overlayMenuVisible() {
    var l = document.querySelectorAll(
      '[' + OVERLAY_MENU_ATTR + '="1"], [' + SPEED_MENU_ATTR + '="1"], ' +
      '[' + MORE_MENU_ATTR + '="1"]');
    for (var i = 0; i < l.length; i++) {
      var e = l[i];
      var r = e.getBoundingClientRect();
      if (r.width < 100 || r.height < 100) continue;
      var cs;
      try { cs = window.getComputedStyle(e); } catch (err) { continue; }
      if (cs.display === 'none' || cs.visibility === 'hidden' ||
          cs.opacity === '0') continue;
      if (e.hasAttribute(SPEED_MENU_ATTR) || e.hasAttribute(MORE_MENU_ATTR) ||
          String(e.textContent || '').indexOf(OVERLAY_TEXT_HINT) >= 0) return true;
    }
    return false;
  }
  function seekGestureAllowed(x, y) {
    var h = window.innerHeight || 0;
    if (h > 0 && (y < h * 0.12 || y > h * 0.78)) return false;
    var el = document.elementFromPoint(x, y);
    if (el && el.closest && (
        el.closest('[data-kvideo-menu]') ||
        el.closest('input,select,textarea') ||
        el.closest('[role="dialog"]') ||
        el.closest('[data-kvideo-popover]') ||
        el.closest('[data-kvideo-hud]') ||
        el.closest('.slider-track') ||
        el.closest('button,a'))) return false;
    // 三点菜单/倍速菜单里上下滑只滚菜单，不触发音量、亮度、拖进度
    if (elInOverlay(el)) return false;
    return true;
  }
  // 手势只在「全屏播放页」生效：首页/详情页/内嵌预览的滑动不应改音量、
  // 亮度或进度。盒子与 TV 本身恒为横屏，不能用宽高比判定；网页的全屏播放器
  // 是自实现的（网页 UI 仍可见，不会走 WebView onShowCustomView），所以以
  // video 是否铺满视口为主，再以「无可见顶部导航 + 有播放进度条」兜底。
  function isFullscreenPlayback() {
    if (document.fullscreenElement || document.webkitFullscreenElement ||
        document.mozFullScreenElement) return true;
    var v = document.querySelector('video');
    if (!v) return false;
    var w = window.innerWidth || 0, h = window.innerHeight || 0;
    var r = v.getBoundingClientRect();
    // 宽屏电影上下留黑边，高度阈值放宽到 0.6
    if (w > 0 && h > 0 && r.width >= w * 0.9 && r.height >= h * 0.6) return true;
    var nav = document.querySelector(NAV + ',' + HEADER);
    var nr = nav ? nav.getBoundingClientRect() : null;
    var navVisible = !!(nr && nr.width > 0 && nr.height > 0);
    if (!navVisible && document.querySelector('.slider-track')) return true;
    return false;
  }
  function onSeekTouchStart(ev) {
    if (synthDispatching) return;
    if (!isFullscreenPlayback()) { scrub = null; return; }
    if (ev.touches.length !== 1) { scrub = null; return; }
    markOverlayMenus();
    if (overlayMenuVisible()) { scrub = null; return; }
    var t = ev.touches[0];
    if (!seekGestureAllowed(t.clientX, t.clientY)) { scrub = null; return; }
    var info = videoInfo();
    if (!info) { scrub = null; return; }
    var bar = findProgressBar();
    var rect = bar ? bar.getBoundingClientRect() : null;
    var usable = !!(rect && rect.width > 120);
    scrub = {
      x: t.clientX, y: t.clientY, lastY: t.clientY,
      t0: info.t, d: info.d, v: info.v,
      width: usable ? rect.width : (window.innerWidth || 1280) * 0.9,
      rect: rect, bar: bar, usable: usable,
      baseX: usable ? rect.left + (info.t / info.d) * rect.width : 0,
      side: t.clientX < (window.innerWidth || 0) / 2 ? 'left' : 'right',
      mode: null, target: info.t, synthStarted: false
    };
  }
  function onSeekTouchMove(ev) {
    if (synthDispatching || !scrub || ev.touches.length !== 1) return;
    // 滑动途中退出全屏也要立刻停手，避免继续调音量/亮度
    if (!isFullscreenPlayback()) { scrub = null; return; }
    var t = ev.touches[0];
    // 手指滑进网页弹层时立刻收手：这里的上下滑本意是滚菜单，
    // 不能继续调音量/亮度，也不能 preventDefault / stopPropagation 干扰网页。
    if (elInOverlay(document.elementFromPoint(t.clientX, t.clientY))) {
      scrub = null; return;
    }
    var dx = t.clientX - scrub.x, dy = t.clientY - scrub.y;
    if (!scrub.mode) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < SCRUB_MIN_PX) return;
      if (Math.abs(dx) >= Math.abs(dy) * AXIS_RATIO) {
        scrub.mode = 'scrub';
        if (scrub.usable) {
          scrub.synthStarted = synthTouch('touchstart', scrub.bar,
            kvClamp(scrub.baseX, scrub.rect.left, scrub.rect.right),
            scrub.rect.top + scrub.rect.height / 2);
        }
      } else if (Math.abs(dy) >= Math.abs(dx) * AXIS_RATIO) {
        scrub.mode = 'vertical';
      } else return;
    }
    if (scrub.mode === 'scrub') {
      scrub.target = kvClamp(scrub.t0 + dx / scrub.width * scrub.d,
                             0, Math.max(0, scrub.d - 0.25));
      var delta = scrub.target - scrub.t0;
      showHud(kvTime(scrub.target) + ' / ' + kvTime(scrub.d),
              (delta >= 0 ? '+' : '-') + kvTime(Math.abs(delta)) + ' · 松手定位',
              scrub.target / scrub.d);
      if (scrub.synthStarted) {
        synthTouch('touchmove', document,
          kvClamp(scrub.baseX + dx, scrub.rect.left, scrub.rect.right), t.clientY);
      }
    } else {
      var steps = Math.trunc((t.clientY - scrub.lastY) / VERT_STEP_PX);
      if (steps !== 0) {
        scrub.lastY += steps * VERT_STEP_PX;
        var dir = steps < 0 ? 1 : -1;
        var n = Math.min(Math.abs(steps), 3);
        var bridge = window.KVideoAndroid;
        for (var i = 0; i < n; i++) {
          try {
            // 音量与亮度都由壳层原生卡片浮层反馈（见 adjustVolume / adjustBrightness），外观完全一致。
            if (scrub.side === 'left') {
              if (bridge && bridge.adjustVolume) bridge.adjustVolume(dir);
            } else {
              if (bridge && bridge.adjustBrightness) bridge.adjustBrightness(dir);
            }
          } catch (e) {}
        }
      }
    }
    if (ev.cancelable) ev.preventDefault();
    if (ev.stopPropagation) ev.stopPropagation();
  }
  function onSeekTouchEnd() {
    if (!scrub) return;
    if (scrub.mode === 'scrub') {
      // 先让网页清掉 isDraggingProgressRef，否则它的 timeupdate 会跳过状态更新，
      // 进度条会停在我们最后合成的位置而不回到真实播放位置。
      if (scrub.synthStarted) {
        synthTouch('touchend', document, scrub.baseX, 0);
        synthTouch('touchcancel', document, scrub.baseX, 0);
      }
      try { if (scrub.v) scrub.v.currentTime = scrub.target; } catch (e) {}
      hideHud(350);
    } else if (scrub.mode === 'vertical') {
      hideHud(500);
    }
    scrub = null;
  }

  // 网页上报「当前横屏且有 video」，壳层据此决定是否显示锁按钮。
  // 状态变化才调桥，避免 1s 补扫每轮都跨 JNI。
  var lastPlaybackState = null;
  function syncPlaybackState() {
    var v = document.querySelector('video');
    var active = !!v &&
      (window.innerWidth || 0) > (window.innerHeight || 0);
    if (lastPlaybackState === active) return;
    lastPlaybackState = active;
    try {
      var bridge = window.KVideoAndroid;
      if (bridge && bridge.onPlaybackState) bridge.onPlaybackState(active);
    } catch (e) { lastPlaybackState = null; }
  }

  // 找到右上角「1x」倍速钮。优先用 aria-label，上游改文案时回落到
  // 「纯数字 + x」文本且不含 svg 的按钮（底栏图标钮都带 svg，不会误伤）。
  // 播放页原生「更多」按钮 aria-label 是「更多选项」，里面是 lucide
  // MoreHorizontal。首页自建 dock 的「更多」带 data-kvideo-act，显式排除。
  function findMoreButton() {
    var exact = document.querySelector(
      'button[aria-label="更多选项"]:not([data-kvideo-act])');
    if (exact) return exact;
    var names = ['更多', '更多设置', 'more options', 'more'];
    var l = document.querySelectorAll(
      'button[aria-label]:not([data-kvideo-act])');
    for (var i = 0; i < l.length; i++) {
      var a = String(l[i].getAttribute('aria-label') || '').trim().toLowerCase();
      if (names.indexOf(a) >= 0 && l[i].querySelector('svg')) return l[i];
    }
    var s = document.querySelector(
      'button:not([data-kvideo-act]) svg[class*="more-horizontal"]');
    return s ? s.closest('button') : null;
  }

  // 只移动外层定位容器，绝不 appendChild 搬按钮节点：React 下次 commit 仍能
  // 在原父节点上找到它，不会抛 removeChild；容器上的 opacity / pointerEvents
  // 淡出逻辑（网页 showControls 控制）也完整保留，静止时三点照样自动消失。
  function moveMoreButton() {
    var b = findMoreButton();
    if (!b) return;
    if (b.getAttribute(MORE_ATTR) !== '1') b.setAttribute(MORE_ATTR, '1');
    var wrap = b.parentElement;
    var fallback = null;
    while (wrap && wrap !== document.body) {
      var cls = String(wrap.className || '');
      if (cls.indexOf('absolute top-8 left-6') >= 0) break;
      if (!fallback &&
          window.getComputedStyle(wrap).position === 'absolute' &&
          wrap.getBoundingClientRect().top < 220) fallback = wrap;
      wrap = wrap.parentElement;
    }
    if (!wrap || wrap === document.body) wrap = fallback;
    if (!wrap) return;
    if (wrap.getAttribute(MORE_WRAP_ATTR) !== '1') {
      wrap.setAttribute(MORE_WRAP_ATTR, '1');
    }
    // CSS !important + 内联 !important 双保险，压过 Tailwind 的 left-6。
    // forceStyle 只在值不同或缺 important 时写入，不会自触发观察器死循环。
    forceStyle(wrap, {
      'left': 'auto',
      'right': '24px',
      'top': '32px'
    });
  }

  function findRateButton() {
    var b = document.querySelector('button[aria-label="播放速度"]');
    if (b) return b;
    var l = document.querySelectorAll('button');
    for (var i = 0; i < l.length; i++) {
      var t = String(l[i].textContent || '').trim();
      if (/^[0-9]+([.][0-9]+)?x$/.test(t) && !l[i].querySelector('svg')) return l[i];
    }
    return null;
  }

  // 把右上角倍速容器整体挪到底部控制栏、右侧按钮组左边。
  // 关键点：移动的是外层定位容器而不是按钮本身，DOM 层级不变，
  // React 不会因节点被移走而在下次 commit 抛 removeChild；同时网页速度
  // 菜单的两套定位算法（getBoundingClientRect / offsetTop 累加）仍能跟上。
  function moveRateButton() {
    var b = findRateButton();
    if (!b) return;
    if (b.getAttribute(RATE_ATTR) !== '1') b.setAttribute(RATE_ATTR, '1');
    var wrap = b.parentElement;
    while (wrap && String(wrap.className || '').indexOf('absolute top-8 right-6') < 0) {
      wrap = wrap.parentElement;
    }
    if (!wrap) return;
    if (wrap.getAttribute(RATE_WRAP_ATTR) !== '1') {
      wrap.setAttribute(RATE_WRAP_ATTR, '1');
    }
    var anchor = document.querySelector('.player-controls-right');
    if (!anchor) return;
    var ar = anchor.getBoundingClientRect();
    if (ar.width < 4 || ar.height < 4) return;
    var host = wrap.offsetParent || wrap.parentElement;
    if (!host) return;
    var hr = host.getBoundingClientRect();
    var size = 40, gap = 12;
    var left = ar.left - hr.left - size - gap;
    var top = ar.top - hr.top + (ar.height - size) / 2;
    if (left < 8) left = 8;
    if (top < 0) top = 0;
    forceStyle(wrap, {
      'position': 'absolute',
      'left': left.toFixed(1) + 'px',
      'top': top.toFixed(1) + 'px'
    });
  }

  // 速度菜单是 Portal 出来的独立 div，直接子元素是一串
  // 「0.5x / 0.75x / 1x」按钮。用「直接子按钮全是 数字x 且至少 2 个」
  // 识别，不依赖随时会变的 Tailwind class。
  function findSpeedMenu() {
    var l = document.querySelectorAll('div');
    for (var i = 0; i < l.length; i++) {
      var kids = l[i].children;
      if (!kids || kids.length < 2) continue;
      var total = 0, hit = 0;
      for (var j = 0; j < kids.length; j++) {
        if (kids[j].tagName !== 'BUTTON') continue;
        total++;
        if (/^[0-9]+([.][0-9]+)?x$/.test(
              String(kids[j].textContent || '').trim())) hit++;
      }
      if (total >= 2 && hit === total) return l[i];
    }
    return null;
  }

  // 只监听菜单自身 style 属性。React 的 w() 会在 useEffect / 50ms 后
  // 重算坐标，把我们的 left 覆盖掉；这个观察器立刻写回。forceStyle 在
  // 值相同且不缺 important 时不会写，因此不会自触发死循环。
  var speedMenuObserver = null;
  var speedMenuWatched = null;
  function watchSpeedMenu(menu) {
    if (speedMenuWatched === menu || !window.MutationObserver) return;
    if (speedMenuObserver) speedMenuObserver.disconnect();
    speedMenuWatched = menu;
    speedMenuObserver = new MutationObserver(function () { placeSpeedMenu(); });
    speedMenuObserver.observe(menu,
      { attributes: true, attributeFilter: ['style'] });
  }

  function placeSpeedMenu() {
    var menu = findSpeedMenu();
    if (!menu) return;
    if (menu.getAttribute(SPEED_MENU_ATTR) !== '1') {
      menu.setAttribute(SPEED_MENU_ATTR, '1');
    }
    watchSpeedMenu(menu);
    var b = document.querySelector('[' + RATE_ATTR + '="1"]') || findRateButton();
    if (!b) return;
    var br = b.getBoundingClientRect();
    if (br.width < 1) return;
    var bottom = (window.innerHeight || 0) - br.top + 10;
    var mw = menu.getBoundingClientRect().width || 72;
    var left = br.right - mw;
    if (left < 8) left = 8;
    forceStyle(menu, {
      'width': '136px',
      'min-width': '0px',
      'left': left.toFixed(1) + 'px',
      'bottom': bottom.toFixed(1) + 'px'
    });
  }

  // 播放页浮动控件统一同步：三点位置、倍速钮位置、速度菜单定位。
  // 这个函数已挂在 syncMenu 开头 / 1s 补扫 / pageshow / resize /
  // orientationchange 上，加在这里就等于全部钩子自动生效。
  function syncRateUi() {
    moveMoreButton();
    moveRateButton();
    placeSpeedMenu();
    markMoreMenu();
  }

  function syncMenu() {
    shrinkSeekButtons();
    syncRateUi();
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
        seekButtons: document.querySelectorAll('[data-kvideo-seek="1"]').length,
        seekDirs: (function () {
          var l = document.querySelectorAll('[data-kvideo-seek="1"]');
          var o = [];
          for (var i = 0; i < l.length; i++) {
            o.push(l[i].getAttribute('data-kvideo-seek-dir'));
          }
          return o.join(',');
        })(),
        seekFlash: document.querySelectorAll('[data-kvideo-seek-show]').length,
        progressBar: document.querySelectorAll('.slider-track').length,
        gestureHud: document.querySelectorAll('[data-kvideo-hud]').length,
        videoDuration: (function () {
          var v = document.querySelector('video');
          return v && isFinite(v.duration) ? Math.round(v.duration) : -1;
        })(),
        moreButton: document.querySelectorAll('[' + MORE_ATTR + '="1"]').length,
        moreWrap: document.querySelectorAll('[' + MORE_WRAP_ATTR + '="1"]').length,
        morePos: (function () {
          var w = document.querySelector('[' + MORE_WRAP_ATTR + '="1"]');
          return w ? (w.style.left + ',' + w.style.right) : 'none';
        })(),
        rateButton: document.querySelectorAll('[' + RATE_ATTR + '="1"]').length,
        rateWrap: document.querySelectorAll('[' + RATE_WRAP_ATTR + '="1"]').length,
        ratePos: (function () {
          var w = document.querySelector('[' + RATE_WRAP_ATTR + '="1"]');
          return w ? (w.style.left + ',' + w.style.top) : 'none';
        })(),
        speedMenu: document.querySelectorAll('[' + SPEED_MENU_ATTR + '="1"]').length,
        speedMenuPos: (function () {
          var m = document.querySelector('[' + SPEED_MENU_ATTR + '="1"]');
          return m ? (m.style.left + ',' + m.style.bottom) : 'none';
        })(),
        playbackActive: !!document.querySelector('video'),
        seekSizes: (function () {
          var out = [];
          var l = document.querySelectorAll('[data-kvideo-seek="1"]');
          for (var i = 0; i < l.length; i++) {
            var s = l[i].querySelector('svg');
            out.push(window.getComputedStyle(l[i]).width + '/' +
              (s ? window.getComputedStyle(s).width : '-'));
          }
          return out.join(',');
        })(),
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
  // 播放器由 React 挂载/重渲染：只监听 class 与 aria-label（不监听 style，
  // 否则 forceStyle 会自触发）；再补一个 1s 低频全量扫描，覆盖注入早于
  // hydrate、以及 Portal 挂载等 MutationObserver 漏掉的情况。
  new MutationObserver(function () { schedule(); }).observe(
    document.documentElement,
    { childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'aria-label'] });
  setInterval(function () {
    if (document.visibilityState === 'visible') {
      shrinkSeekButtons();
      syncRateUi();
      syncPlaybackState();
    }
  }, 1000);
  window.addEventListener('pageshow', function () {
    shrinkSeekButtons();
    syncRateUi();
    syncPlaybackState();
  });
  window.addEventListener('resize', syncRateUi, { passive: true });
  window.addEventListener('orientationchange', function () {
    setTimeout(syncRateUi, 150);
  }, { passive: true });
  document.addEventListener('touchstart', onSeekTouchStart,
    { capture: true, passive: true });
  document.addEventListener('touchmove', onSeekTouchMove,
    { capture: true, passive: false });
  document.addEventListener('touchend', onSeekTouchEnd,
    { capture: true, passive: true });
  document.addEventListener('touchcancel', onSeekTouchEnd,
    { capture: true, passive: true });
  syncPlaybackState();
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
    private lateinit var lockOverlay: View
    private lateinit var lockToggle: ImageButton
    private lateinit var lockHint: TextView
    private lateinit var gestureHud: View
    private lateinit var gestureHudValue: TextView
    private lateinit var gestureHudBar: ProgressBar
    private val gestureHudHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideGestureHud = Runnable { gestureHud.visibility = View.GONE }
    private var playbackLocked = false
    private var playbackActive = false
    private val lockHintHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideLockHint = Runnable { lockHint.visibility = View.GONE }
    private val lockIdleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideLockToggle = Runnable { lockToggle.visibility = View.GONE }

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
        lockOverlay = findViewById(R.id.lock_overlay)
        lockToggle = findViewById(R.id.lock_toggle)
        lockHint = findViewById(R.id.lock_hint)
        gestureHud = findViewById(R.id.gesture_hud)
        gestureHudValue = findViewById(R.id.gesture_hud_value)
        gestureHudBar = findViewById(R.id.gesture_hud_bar)
        viewsReady = true
        settingsButton.setOnClickListener {
            showSetup(getString(R.string.status_settings_hint))
        }
        lockToggle.setOnClickListener { togglePlaybackLock() }
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

    /**
     * 任意触摸都算「有操作」。即使锁定态下 lock_overlay 吞掉了事件，
     * Activity 层仍会先收到这里，所以触屏一定能把锁钮唤回来。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) scheduleLockToggleHide()
        return super.dispatchTouchEvent(event)
    }

    /**
     * 锁定时把方向键、OK、媒体键、数字键全部吞掉，避免遥控器/键盘误触。
     * 音量与 HOME 放行；BACK / OK / ENTER / MENU 作为解锁键，保证不会锁死。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) scheduleLockToggleHide()
        if (playbackLocked && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_HOME -> return super.dispatchKeyEvent(event)
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS -> {
                    setPlaybackLocked(false)
                    return true
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
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
        lockHintHandler.removeCallbacks(hideLockHint)
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
        playbackActive = false
        setPlaybackLocked(false)
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
        // 必须在 setupContainer 可见之后刷新，updateLockUi() 才判定得到
        // !isSetupVisible() == false，锁按钮不会盖在设置表单上。
        setPlaybackLocked(false)
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

    /** 网页只在「横屏 + 存在 video」时上报 true；离开播放页自动解锁并收起按钮。 */
    private fun onPlaybackStateChanged(active: Boolean) {
        playbackActive = active
        if (!active) {
            if (playbackLocked) setPlaybackLocked(false)
            resetWindowBrightness()
        }
        updateLockUi()
    }

    /** 手势调过的亮度只在本窗口生效；离开播放页交还给系统亮度。 */
    private fun resetWindowBrightness() {
        val lp = window.attributes
        if (lp.screenBrightness in 0f..1f) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    /** 原生手势浮层：左右半屏上下滑调音量/亮度时显示百分比与进度条，松手 800ms 后自动隐藏。
     *  音量与亮度共用同一卡片，外观完全一致；用原生 View 而非注入 JS 浮层，
     *  避免网页 CSS !important 把显示压住导致「无反馈」。 */
    private fun showGestureHud(valueText: String, pct: Int) {
        if (!viewsReady) return
        val p = pct.coerceIn(0, 100)
        gestureHudValue.text = valueText
        gestureHudBar.progress = p
        gestureHud.visibility = View.VISIBLE
        gestureHudHandler.removeCallbacks(hideGestureHud)
        gestureHudHandler.postDelayed(hideGestureHud, 800L)
    }

    /** JS 桥在 WebView 线程调用，音量和窗口亮度必须在 UI 线程改；同步回传百分比给提示浮层。 */
    private fun runOnUiSync(block: () -> Int): Int {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return block()
        }
        var result = -1
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                result = block()
            } catch (error: Exception) {
                Log.w(TAG, "Gesture adjustment failed", error)
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(500, TimeUnit.MILLISECONDS)) result else -1
    }

    private fun canShowLockToggle(): Boolean =
        playbackActive && !isSetupVisible() &&
            errorContainer.visibility != View.VISIBLE

    /** 有输入时调用：立刻显示锁钮并重新计 5 秒空闲；离开播放页则立即收起。 */
    private fun scheduleLockToggleHide() {
        if (!viewsReady) return
        lockIdleHandler.removeCallbacks(hideLockToggle)
        if (!canShowLockToggle()) {
            lockToggle.visibility = View.GONE
            return
        }
        lockToggle.visibility = View.VISIBLE
        lockIdleHandler.postDelayed(hideLockToggle, LOCK_TOGGLE_IDLE_MS)
    }

    private fun hideLockToggleNow() {
        if (!viewsReady) return
        lockIdleHandler.removeCallbacks(hideLockToggle)
        lockToggle.visibility = View.GONE
    }

    private fun updateLockUi() {
        val show = canShowLockToggle()
        lockOverlay.visibility =
            if (playbackLocked && show) View.VISIBLE else View.GONE
        if (show) scheduleLockToggleHide() else hideLockToggleNow()
        lockToggle.setImageResource(
            if (playbackLocked) R.drawable.ic_lock_closed_24
            else R.drawable.ic_lock_open_24
        )
        lockToggle.contentDescription = getString(
            if (playbackLocked) R.string.lock_button_unlock
            else R.string.lock_button_lock
        )
        lockHint.text = getString(R.string.lock_hint_locked)
        lockHintHandler.removeCallbacks(hideLockHint)
        if (playbackLocked) {
            lockHint.visibility = View.VISIBLE
            lockHintHandler.postDelayed(hideLockHint, 2500L)
        } else {
            lockHint.visibility = View.GONE
        }
    }

    private fun setPlaybackLocked(locked: Boolean) {
        playbackLocked = locked
        updateLockUi()
    }

    private fun togglePlaybackLock() = setPlaybackLocked(!playbackLocked)

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
                    if (viewsReady && !playbackActive && !isSetupVisible() && customView == null &&
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
        setPlaybackLocked(false)
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
        /** 网页上报当前是否处于横屏播放，壳层据此显示/隐藏防误触锁按钮。 */
        @JavascriptInterface
        fun onPlaybackState(active: Boolean) {
            runOnUiThread { onPlaybackStateChanged(active) }
        }

        /** 左半屏上下滑动：调系统媒体音量，返回 0..100 供提示浮层显示。
         *  不再用系统 FLAG_SHOW_UI 音量条，改走与亮度完全一致的壳层原生卡片。 */
        @JavascriptInterface
        fun adjustVolume(direction: Int): Int = runOnUiSync {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = maxOf(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val step = maxOf(1, max / 15)
            val next = (cur + direction * step).coerceIn(0, max)
            if (next != cur) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
            }
            val pct = ((next.toFloat() / max) * 100).roundToInt()
            showGestureHud(getString(R.string.volume_hud_value, pct), pct)
            pct
        }

        /** 右半屏上下滑动：调当前窗口亮度，返回 0..100 供提示浮层显示。 */
        @JavascriptInterface
        fun adjustBrightness(direction: Int): Int = runOnUiSync {
            val lp = window.attributes
            var value = lp.screenBrightness
            if (value !in 0f..1f) {
                value = try {
                    Settings.System.getInt(
                        contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128
                    ) / 255f
                } catch (error: Exception) {
                    0.5f
                }
                value = value.coerceIn(0.05f, 1f)
            }
            value = (value + direction * 0.06f).coerceIn(0.02f, 1f)
            lp.screenBrightness = value
            window.attributes = lp
            val pct = (value * 100).roundToInt()
            showGestureHud(getString(R.string.brightness_hud_value, pct), pct)
            pct
        }

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
