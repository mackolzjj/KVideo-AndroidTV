package com.kvideo.tv

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TouchDelegate
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
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
        private const val SWIPE_THRESHOLD_DP = 32f
        /** 锁按钮空闲多久后自动隐藏；触屏或按任意键重新计时并显示。 */
        private const val LOCK_TOGGLE_IDLE_MS = 3_000L

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
         *    380px 以下超窄屏回落 32px / 20px，避免再次挤到 logo 上。
         * 7. mark() 给贴顶元素打 data-kvideo-topbar 兜底；MutationObserver 用
         *    「节流立即扫 + 尾部补扫」，syncMenu() 稳态不产生 DOM 变更。
         * 8. 播放页左右快退/快进圆钮按 aria-label 打标后缩小，保留 44px 热区。
         */
        private const val CHROME_INJECT_JS_A = """
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
  var BRAND_NAME_ATTR = 'data-kvideo-brand-name';
  var BRAND_SUB_ATTR = 'data-kvideo-brand-sub';
  var TOPBAR_ATTR = 'data-kvideo-topbar';
  var DOCK_ATTR = 'data-kvideo-dock';
  var STRAY_ATTR = 'data-kvideo-stray';
  var LOGO_ATTR = 'data-kvideo-logo';
  var BRAND_IMG_ATTR = 'data-kvideo-brand-img';
  var HIDE = 'transform:translateY(-120%) !important;opacity:0 !important;' +
             'pointer-events:none !important;';

  // 顶栏 logo：上游是紫色实心方块 + 白色实心三角，和旁边空心白线性圆钮
  // 形状、底色、图标语言全不一致。隐藏原图，插入同尺寸圆形矢量标：
  // 与圆钮共用 10% 白底 + 20% 白描边，紫色只留在线性播放三角上。
  var LOGO_SVG =
    '<svg viewBox="0 0 36 36" width="36" height="36" ' +
      'xmlns="http://www.w3.org/2000/svg" aria-hidden="true">' +
      '<circle cx="18" cy="18" r="17" fill="#FFFFFF" fill-opacity=".10" ' +
        'stroke="#FFFFFF" stroke-opacity=".20" stroke-width="1"/>' +
      '<path d="M16 12 L16 24 L26 18 Z" fill="none" stroke="#7F77DD" ' +
        'stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>' +
    '</svg>';
  var LOGO_CSS =
    '[' + BRAND_IMG_ATTR + ']{display:none !important;}' +
    '[' + LOGO_ATTR + ']{width:36px;height:36px;min-width:36px;min-height:36px;' +
      'display:inline-flex !important;align-items:center !important;' +
      'justify-content:center !important;vertical-align:middle !important;' +
      'box-sizing:border-box !important;flex:0 0 auto !important;}' +
    '[' + LOGO_ATTR + '] svg{width:100% !important;height:100% !important;' +
      'display:block !important;}' +
    '@media (min-width:640px){' +
      '[' + LOGO_ATTR + ']{width:40px;height:40px;min-width:40px;min-height:40px;}}' +
    '@media (max-width:430px){' +
      '[' + LOGO_ATTR + ']{width:32px;height:32px;min-width:32px;min-height:32px;}}' +
    '@media (max-width:360px){' +
      '[' + LOGO_ATTR + ']{width:30px;height:30px;min-width:30px;min-height:30px;}}' +
    // logo 的内圈同样是白 10% 底 + 白 20% 描边，浅色顶栏上会整圈融掉，只剩
    // 中间那个紫色三角孤零零悬着。只换 circle 的两个 alpha，三角的描边与
    // 颜色一个字不动——紫色是唯一彩色，深浅两种主题下都保持。
    // SVG 的 fill-opacity / stroke-opacity 是表现属性，优先级低于任何 CSS
    // 声明，所以这里不加 !important 也压得住；加上只是为了跟同文件其他规则
    // 保持一致的写法。
    'html[data-kv-light="1"] [' + LOGO_ATTR + '] circle{' +
      'fill:#000000 !important;fill-opacity:.08 !important;' +
      'stroke:#000000 !important;stroke-opacity:.16 !important;}';

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
    // 三档合成一个循环钮：图标永远等于「当前档位」。
    // 「跟随系统」用半黑半白的圆，和太阳、月亮都分得开。
    autoTheme: svg('<circle cx="12" cy="12" r="9"/>' +
                   '<path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor" stroke="none"/>'),
    refresh: svg('<path d="M20.5 12a8.5 8.5 0 1 1-2.5-6"/>' +
                 '<polyline points="20.5 3.5 20.5 8.5 15.5 8.5"/>'),
    logout: svg('<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>' +
                '<polyline points="16 17 21 12 16 7"/>' +
                '<line x1="21" y1="12" x2="9" y2="12"/>'),
    // 原来的「圆 + 八条短线」跟太阳几乎一样，放在同一排分不清；换成带齿的
    // 经典齿轮，并且弹层里的「网站设置」改用地球，避免两个齿轮撞图标。
    gear: svg('<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25' +
              'a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1' +
              'a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38' +
              'a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20' +
              'a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25' +
              'a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08' +
              'a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38' +
              'a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4' +
              'a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>'),
    globe: svg('<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/>' +
               '<path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10' +
               ' 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>'),
    link: svg('<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7.1-7.1l-1.7 1.7"/>' +
              '<path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7.1 7.1l1.7-1.7"/>')
  };

  var BTN = '[data-kvideo-menu] button';
  var MENU_CSS =
    // 顶栏右侧不再有任何「壳」：胶囊方案（外框底色 + 描边）整套去掉，
    // 7 个图标直接浮在顶栏上——导航段（收藏 / 历史 / 直播 / 设置齿轮）紧贴
    // logo，外观段（浅色 / 深色 / 跟随系统）贴右边缘。
    //
    // 为什么彻底不留壳：只要有一个圆角外框，它就会和底栏那四个圆钮抢视觉
    // 重量，顶栏显得比播放控制还重。裸图标把「导航」降为背景层，播放控制
    // 才是主角。两个分组容器仍保留（.kvideo-group），只为定位弹层和分组
    // 间距服务，本身完全透明。
    '[data-kvideo-menu]{display:flex;align-items:center;gap:14px;flex:1 1 auto;' +
      'position:relative;margin-left:10px;min-width:0;' +
      'justify-content:space-between;}' +
    // 组内必须永远是「紧凑一组」：网页顶栏自己带 justify-between / 按钮
    // flex:1 之类的规则，会把组里的图标拉成等距散开（截图里就是这个样子），
    // 和效果图的「左边一撮、右边一颗」完全不是一回事。这里全部用 !important 钉死。
    '[data-kvideo-menu] .kvideo-group{display:inline-flex !important;align-items:center;' +
      'justify-content:flex-start !important;' +
      // 三档主题合成一个循环钮后空出一个位置，间距 4 → 8px：
      // 6×32+5×8=232px，比原来 7×32+6×4=248px 更窄，同时更好按。
      'gap:6px !important;padding:0 !important;flex:0 0 auto !important;position:relative;' +
      'border:0 !important;background:transparent !important;' +
      'border-radius:0 !important;box-shadow:none !important;}' +
    BTN + '{flex:0 0 auto !important;' +
      'width:34px;height:34px;min-width:34px;min-height:34px;' +
      'display:inline-flex;align-items:center;justify-content:center;position:relative;' +
      'border-radius:999px !important;padding:0;margin:0;cursor:pointer;' +
      // 圆底交给胶囊：按钮自己不画描边和底色，否则胶囊里会再浮出一串圆点。
      'border:0 !important;background:transparent !important;' +
      'color:inherit;font:inherit;' +
      '-webkit-tap-highlight-color:transparent;' +
      'transition:background-color .16s ease,color .16s ease,transform .16s ease;}' +
    BTN + ':active{transform:scale(.92);}' +
    BTN + ' svg{width:20px;height:20px;flex:0 0 auto;}' +
    BTN + ' .kvideo-ico{display:inline-flex;align-items:center;justify-content:center;}' +
    // 当前主题档位：只把图标染蓝，不画底——壳都去掉了，再冒一个蓝色圆点
    // 反而比原来更像「多出来一个控件」。蓝色只表示「当前是哪一档」。
    BTN + '[data-kvideo-active="1"]{color:#60A5FA !important;}' +
    // 齿轮弹层展开：用中性「加深底色」，绝不用蓝色——否则会被读成主题被切换。
    BTN + '[data-kvideo-open="1"]{background:rgba(255,255,255,.20) !important;}' +
    '[data-kvideo-menu] .kvideo-group + .kvideo-group{margin-left:auto !important;}' +
    // 主题钮不要贴到胶囊右缘：再让出 10px（加上容器自身 padding 约 22px）
    // 主题钮右侧留白：用容器 padding-right（在 group 上加 margin 会被
    // justify-content + margin-left:auto 吃掉，实测改成 42px 也没变化）。
    // 配合组内间距 8 → 6px，内容不再溢出，18px 才真正生效（本地实测 gap=18px）。
    '[data-kvideo-menu]{padding-right:18px !important;}' +
    '[data-kvideo-menu] [hidden]{display:none !important;}' +
    // 弹层仍然挂第一段分组里（它只负责定位，本身透明）。组壳去掉后组的右沿
    // 就是齿轮的右沿，所以偏移量取 (176-32)/2 = 72，弹层正好居中在齿轮下方。
    '[data-kvideo-menu] .kvideo-menu-popover{' +
      'position:absolute;top:calc(100% + 10px);right:-72px;z-index:3000;' +
      'min-width:176px;padding:6px;display:flex;flex-direction:column;gap:2px;' +
      'border-radius:16px;' +
      'border:1px solid rgba(127,127,127,.42) !important;' +
      'background:rgba(26,27,31,.97) !important;' +
      'color:#F2F3F5 !important;' +
      'backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);' +
      'box-shadow:0 12px 32px rgba(0,0,0,.42);}' +
    '[data-kvideo-menu] .kvideo-menu-popover button{' +
      'width:100%;height:44px;min-height:44px;' +
      'border:0 !important;border-radius:10px !important;' +
      'background:transparent !important;color:#F2F3F5 !important;' +
      'justify-content:flex-start;gap:10px;padding:0 10px;' +
      'font-size:14px;white-space:nowrap;}' +
    '[data-kvideo-menu] .kvideo-menu-popover button:active{' +
      'background:rgba(255,255,255,.12) !important;}' +
    '[data-kvideo-menu] .kvideo-menu-popover button svg{width:20px;height:20px;}' +
    // 破坏性操作单独标红，并压到最底（中间用一条分隔线隔开）。
    '[data-kvideo-menu] .kvideo-menu-popover button[data-kvideo-act="logout"]{' +
      'color:#F87171 !important;}' +
    '[data-kvideo-menu] .kvideo-menu-hr{' +
      'height:1px;margin:6px 8px;background:rgba(255,255,255,.10);}' +
    '@media (min-width:640px){' +
      BTN + '{width:40px;height:40px;min-width:40px;min-height:40px;}' +
      BTN + ' svg{width:22px;height:22px;}' +
      // 宽屏：不再用 space-between 把两组推到屏幕两端。700dp 上 7 个图标只占
      // 304px，中间会空出 300+px，顶栏看着像「左边一小撮、右边一小撮」两个孤岛。
      // 整组靠 logo 排、组间固定 16px，右侧留白由 flex 自然吃掉。
      '[data-kvideo-menu]{justify-content:flex-start;gap:16px;}}' +
    // 窄屏：按钮 32px、图标间距 4px。7 个图标合计 7×32+6×4 = 248px，
    // 加 logo 与边距约 300px，320dp 也放得下，不会反压 logo。
    '@media (max-width:430px){' +
      '[data-kvideo-menu]{gap:12px;margin-left:8px;}' +
      // 组内间距不再降到 6px：6 个图标在 320dp 也放得下（6×30+5×8=220），
      // 统一 8px 才能和效果图逐像素对上，命中区也就能一律扩到 4px。
      '[data-kvideo-menu] .kvideo-group{gap:8px;}' +
      BTN + '{width:32px;height:32px;min-width:32px;min-height:32px;}' +
      BTN + ' svg{width:19px;height:19px;}}' +
    '@media (max-width:360px){' +
      // 320dp 上「圆标 + 站名 + 6 个图标」会挤到重叠，这一档藏回文字
      prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_TEXT_ATTR + ']']) +
        '{display:none !important;}' +
      BTN + '{width:30px;height:30px;min-width:30px;min-height:30px;}' +
      BTN + ' svg{width:18px;height:18px;}}' +
    // 浅色主题：网页顶栏是白底，上面所有「白色 alpha」的层级反馈都会失效
    // —— 白底铺白底等于没铺。这里只补三条，把白系 alpha 换成黑系 alpha，
    // 并把强调色换到对白底 5.17:1 的深蓝（#60A5FA 对白底只有 2.54:1，
    // 低于 3:1 的 UI 元素下限）。深色主题一条不动。
    // 按下态用 .kvideo-group > button 限定，避免把弹层里那三个深色底按钮
    // 也刷成黑色铺底：弹层本身是深色，黑 8% 在它上面等于没有反馈。
    'html[data-kv-light="1"] ' + BTN + '[data-kvideo-active="1"]{' +
      'color:#2563EB !important;}' +
    'html[data-kv-light="1"] ' + BTN + '[data-kvideo-open="1"]{' +
      'background:rgba(0,0,0,.14) !important;}' +
    'html[data-kv-light="1"] [data-kvideo-menu] .kvideo-group > button:active{' +
      'background:rgba(0,0,0,.08) !important;}' +
    // 触摸目标：视觉上按钮只有 30–34px（远低于 48dp），但顶栏放不下更大的圆。
    // 这里只撑「命中区」、不改外观：上下各扩 9px（30+18=48），左右各扩 3px
    // —— 横向不扩到 6px 是因为相邻按钮只隔 4px，扩多了两个按钮的命中区会互压，
    // 点边缘会落到邻居身上。伪元素在按钮内部，不占布局、不挡弹层。
    // 组内间距已经从 4px 放到 8px，横向命中区跟着从 3px 放到 4px：
    // 两侧各 4px 正好相接但不重叠（再大就会互压，点击落到 DOM 里靠后的那个）。
    '[data-kvideo-menu] .kvideo-group > button::after{content:"";position:absolute;' +
      'left:-4px;right:-4px;top:-9px;bottom:-9px;border-radius:999px;}' +
    '[data-kvideo-menu] .kvideo-group > button:active::after{' +
      'background:rgba(255,255,255,.06);}' +
    'html[data-kv-light="1"] [data-kvideo-menu] .kvideo-group > button:active::after{' +
      'background:rgba(0,0,0,.04);}';

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

  // 倍速圆钮从右上角挪到底栏后，外观要跟底栏右侧按钮组「并成一排」：
  // 之前在组外留 12px 间距、自己画 10% 白底 + 1px 描边，看上去就是浮在
  // 组旁边的一个独立小件。现在底色/描边改为透明，实际外观由 moveRateButton
  // 照抄组内图标钮的计算样式（内联 important 优先级高于本表的 important）。
  var RATE_ATTR = 'data-kvideo-rate';
  var RATE_WRAP_ATTR = 'data-kvideo-rate-wrap';
  var RATE_CSS =
    '[' + RATE_ATTR + '="1"]{width:40px !important;height:40px !important;' +
      'min-width:40px !important;min-height:40px !important;' +
      'max-width:40px !important;max-height:40px !important;' +
      'padding:0 !important;margin:0 !important;box-sizing:border-box !important;' +
      'border-radius:999px !important;background:transparent !important;' +
      'border:0 !important;color:#FFFFFF !important;' +
      'font-size:13px !important;font-weight:500 !important;line-height:1 !important;' +
      'backdrop-filter:none !important;-webkit-backdrop-filter:none !important;' +
      'box-shadow:none !important;pointer-events:auto !important;' +
      'display:flex !important;align-items:center !important;justify-content:center !important;' +
      // 常态仍锁死 transform（网页可能拿 translate 做定位），只加过渡曲线；
      // 底色/描边**不给过渡**：外观规则一旦被重写，过渡会把变化播成一段渐变，
      // 叠在「每个 tick 重写一次」上就是肉眼可见的闪烁。
      'transform:none !important;' +
      'transition:transform .16s ease !important;}' +
    // 只留按下的轻微缩放做触感反馈：底色/描边已经是照抄相邻钮的内联 important，
    // 再写 hover 底色也压不过它，写进去只会让 1x 在按下时跟邻居「不一样」。
    '[' + RATE_ATTR + '="1"]:active{transform:scale(.96) !important;}' +
    // 移动的是右上角那个 absolute 定位容器，left/top 由 moveRateButton 内联写入。
    // Tailwind 的 top-8 / right-6 必须先置 auto，否则两个方向会互相拉扯。
    '[' + RATE_WRAP_ATTR + '="1"]{top:auto !important;right:auto !important;' +
      'bottom:auto !important;width:40px !important;height:40px !important;' +
      'min-width:0 !important;overflow:visible !important;z-index:45 !important;' +
      // 透明度由 rAF 逐帧写成控制条的实测值，这里**不能**再挂过渡：
      // 过渡 + 采样台阶叠在一起，淡入淡出的那一下就会跳一下。
      'transition:none !important;}';

  // 曾经想把「1x + 画中画 / 网页全屏 / 全屏」收成一条共用底色的胶囊，做法是给
  // 整段垫一层圆角底、再把组内按钮自己的圆底抹平。实际效果是**改掉了原样式**：
  // 三个原本各自独立的圆钮被抹成一排裸图标，外面套一个长条——用户要的是
  // 「1x 跟他们并排、长得一模一样」，不是「换一种控件」。所以胶囊方案整体撤掉，
  // 底栏按钮的圆底一律不动，只让 1x 变成第四个同款圆钮。

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
  // 1.1.62：直接走「固定弹出」——两个弹层都不做任何动画，元素一挂出来就
  // 停在最终位置。之前尝试过对齐网页动画，但只要网页那边还有 transition /
  // animate-in，就会叠出第二段；索性全部压平。
  // 只压 animation 与 transition，绝不动 transform（三个点菜单靠 transform
  // 做 align:right 的位移，一动就会跑出屏幕）。
  var SPEED_MENU_ANIM =
    '[' + SPEED_MENU_ATTR + '="1"],' +
    '[' + SPEED_MENU_ATTR + '="1"] *{' +
      'animation:none !important;transition:none !important;}';

  // 播放页「更多」三点原本贴在左上角，和绿色 1080P 画质标签挤在一起。
  // 1.1.18 已把右上角 top-8 right-6 的倍速容器整体挪到底栏，右上角空出来。
  // 这里只翻转外层定位容器的左右方向（left-6 -> right-6），DOM 层级不动，
  // 网页菜单的三套定位算法会自动按「按钮在右半屏」算成 align:right，
  // 用 translateX(-100%) 向左展开，不会跑出屏幕，无需再接管弹层。
  var MORE_ATTR = 'data-kvideo-more';
  var MORE_WRAP_ATTR = 'data-kvideo-more-wrap';
  var MORE_CSS =
    '[' + MORE_WRAP_ATTR + '="1"]{' +
      // 24px 是常规边距，再叠加壳层实测的右侧安全区（横屏挖孔时 >0），
      // 否则三点会落进挖孔里被切掉一角。
      'left:auto !important;right:calc(24px + var(--kv-safe-right, 0px)) !important;' +
      'top:32px !important;}';

  // 三点弹出的设置面板原本卡片接近三分之一屏宽，挡画面。1.1.28 起接管尺寸：
  // max-content 让宽度贴合文字内容，zoom 等比缩小字号/间距/图标/内边距——
  // 比逐条改子元素的 padding、font-size 稳妥，也不会碰网页用来向左展开的
  // transform（那个一旦覆盖，菜单就会跑出屏幕或贴错位置）。
  var MORE_MENU_ATTR = 'data-kvideo-more-menu';
  var MORE_MENU_CSS =
    '[' + MORE_MENU_ATTR + '="1"],' +
    '[' + MORE_MENU_ATTR + '="1"] *{' +
      // 同样走固定弹出：网页的淡入/缩放也一并压掉，避免第二段动画。
      // transform 不能碰（对齐靠它），所以只清 animation 与 transition。
      'animation:none !important;transition:none !important;}' +
    '[' + MORE_MENU_ATTR + '="1"]{' +
      'zoom:0.82 !important;width:max-content !important;min-width:0 !important;' +
      'max-width:300px !important;box-sizing:border-box !important;}';

  // 手势提示浮层：跟手拖进度时显示目标时间/偏移/进度条，音量亮度时显示百分比。
  // 自建 DOM 挂在 body 上，不受 React 管理，也不吃网页的 CSS 变量。
  // 1.1.33 起与原生音量/亮度/锁定提示统一：45% 半透底、12% 白细描边、10px 圆角、蓝色进度条，
  // 主字收到 18px；去掉实心深底与投影，避免同一播放页交替弹出「大黑框」和「小胶囊」。
  // 三档合成一个钮后，第一次切档浮一条提示说明循环顺序；2.5 秒淡出，只出现一次。
  var THEME_HINT_CSS =
    '[data-kvideo-theme-hint]{position:fixed;left:50%;transform:translateX(-50%);' +
      'top:calc(env(safe-area-inset-top, 0px) + 62px);z-index:2147483646;' +
      'padding:8px 14px;border-radius:999px;background:rgba(20,22,28,.94);' +
      'color:#F2F3F5;font-size:12px;line-height:1.3;white-space:nowrap;' +
      'box-shadow:0 8px 24px rgba(0,0,0,.42);pointer-events:none;' +
      'opacity:0;transition:opacity .25s ease;}';

  // 播放页没有 sticky 顶栏：菜单没有落脚点，页面上就没有这条栏。
  // 挂到 body 上做固定栏；只改定位，内部布局（品牌 + 两组图标）沿用首页那套。
  var DOCK_CSS =
    '[' + DOCK_ATTR + '="fixed"]{' +
      'position:fixed !important;top:0 !important;left:0 !important;right:0 !important;' +
      // 56dp 是从首页截图量出来的胶囊高度（168px ÷ 3），与首页对齐
      'width:100% !important;height:56px !important;margin:0 !important;' +
      // 横向 37 / 47 = 网页顶栏的 29px + 品牌 8 / 主题钮留白 18（本地实测对齐）
      'padding-left:37px !important;padding-right:47px !important;box-sizing:border-box;' +
      'display:flex !important;align-items:center;' +
      'z-index:2147483000 !important;flex:0 0 auto !important;' +
      // 底色按页面实际底色由 JS 写进变量：以前用 data-kv-light 猜，遇到
      // 「浅色标记但实际深色」的设置页就会画出一条大白条，白字压在白底上还看不见。
      'background:var(--kv-dock-bg, rgba(13,15,19,.94)) !important;' +
      'color:var(--kv-dock-fg, #F2F4F8) !important;' +
      '-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);}' +
    // 行内（首页/播放页有网页顶栏时）只补站名文字，logo 用页面自己的
    // 网页自己的站名：不许压缩、不许省略号截断（首页「显示不完整」就是被挤的）
    '[' + BRAND_NAME_ATTR + ']{white-space:nowrap !important;overflow:visible !important;' +
      'text-overflow:clip !important;flex:0 0 auto !important;min-width:0 !important;}' +
    '.kvideo-brand-text{font-size:15px;font-weight:600;white-space:nowrap;' +
      'margin-right:10px;color:inherit !important;font-family:inherit;}' +
    '[' + DOCK_ATTR + '="fixed"] .kvideo-float-brand{' +
      'display:inline-flex;align-items:center;gap:8px;flex:0 0 auto;' +
      'font-size:15px;font-weight:600;white-space:nowrap;padding-right:12px;' +
      'color:inherit !important;}' +
    '[' + DOCK_ATTR + '="fixed"] .kvideo-float-brand svg{' +
      'width:26px !important;height:26px !important;flex:0 0 auto;}' +
    // 挂固定栏时把网页自己顶部那条（div 形态、选择器命不中的）藏掉
    '[' + STRAY_ATTR + ']{display:none !important;}';

  var HUD_CSS =
    '[data-kvideo-hud]{position:fixed !important;left:50% !important;top:30% !important;' +
      'transform:translate(-50%,-50%) !important;min-width:152px !important;' +
      'padding:10px 16px !important;box-sizing:border-box !important;' +
      'background:rgba(16,17,21,.45) !important;' +
      'border:1px solid rgba(255,255,255,.12) !important;border-radius:10px !important;' +
      'color:#FFFFFF !important;' +
      'text-align:center !important;z-index:2147483647 !important;' +
      'pointer-events:none !important;display:none !important;font-family:sans-serif !important;}' +
    '.kv-hud-main{font-size:18px !important;font-weight:500 !important;' +
      'line-height:1.2 !important;font-variant-numeric:tabular-nums !important;' +
      'color:#FFFFFF !important;}' +
    '.kv-hud-sub{font-size:12px !important;color:rgba(255,255,255,.68) !important;' +
      'margin-top:4px !important;line-height:1.3 !important;}' +
    '.kv-hud-track{height:4px !important;border-radius:2px !important;' +
      'margin-top:8px !important;background:rgba(255,255,255,.20) !important;' +
      'overflow:hidden !important;}' +
    '.kv-hud-track i{display:block !important;height:100% !important;width:0 !important;' +
      'background:#3b82f6 !important;border-radius:2px !important;}';

  // 直播页（/iptv）的筛选条是网页自己的 sticky 块：搜索 + 计数 + 全部地区/平台
  // chip。宽屏上三行，手机上 chip 换成十几行，sticky 后比整屏还高，频道区永远
  // 被压在下面（393×853 复刻实测占视口 66%，真机截图 ≥94%）。
  // 壳里不搬动网页节点（React 持有父容器引用，挪走会在下次重渲染时抛
  // NotFoundError）：只给 chip 容器打标，用 CSS 折叠；展开时把 sticky 块本身
  // 限高并内部滚动，频道区始终露出来。
  var IPTV_BAR_ATTR = 'data-kv-iptv-bar';
  var IPTV_CHIPS_ATTR = 'data-kv-iptv-chips';
  var IPTV_OPEN_ATTR = 'data-kv-iptv-open';
  var IPTV_BTN_ATTR = 'data-kv-iptv-btn';
  var IPTV_CLOSE_ATTR = 'data-kv-iptv-close';
  var IPTV_MINI_ATTR = 'data-kv-iptv-mini';
  var IPTV_MINI_BTNS_ATTR = 'data-kv-iptv-mini-btns';
  var IPTV_CSS =
    '[' + IPTV_CHIPS_ATTR + ']{display:none !important;}' +
    '[' + IPTV_BAR_ATTR + '][' + IPTV_OPEN_ATTR + '="1"]{' +
      'max-height:36vh !important;overflow-y:auto !important;' +
      'overscroll-behavior:contain;-webkit-overflow-scrolling:touch;' +
      'padding-bottom:12px !important;' +
      // 展开时底色改实：条是半透明 + 毛玻璃，一旦变成内滚容器，下层的频道
      // 卡片就会从条里透出来，看着像「列表文字压在 chip 上」。颜色同样由
      // JS 按页面底色写进 --kv-iptv-fade。
      'background:var(--kv-iptv-fade,rgba(12,14,19,.96)) !important;}' +
    '[' + IPTV_BAR_ATTR + '][' + IPTV_OPEN_ATTR + '="1"] [' + IPTV_CHIPS_ATTR + ']{' +
      'display:flex !important;}' +
    // 底边渐隐：底色已改实，这层只盖在 chip 上，不会透出下层。
    // 不给条本体加 mask —— mask 会连底色一起抹掉，那才是之前「下层文字
    // 压在 chip 上」的原因。
    '[' + IPTV_BAR_ATTR + '][' + IPTV_OPEN_ATTR + '="1"]::after{' +
      'content:"";display:block;position:sticky;bottom:0;height:22px;' +
      'margin-top:-22px;pointer-events:none;z-index:2;' +
      'background:linear-gradient(rgba(0,0,0,0),' +
      'var(--kv-iptv-fade,rgba(12,14,19,.96)));}' +
    // 折叠/展开钮绝对定位在搜索框右端：不进入网页的 flex 流，网页重渲染也
    // 不会把它排到别处；万一被清掉，下一拍 syncIptvFilter 会补回来。
    '[' + IPTV_BTN_ATTR + ']{position:absolute !important;top:14px !important;' +
      'right:16px !important;height:28px !important;padding:0 12px !important;' +
      'display:inline-flex !important;align-items:center !important;' +
      'border-radius:999px !important;border:1px solid rgba(140,150,170,.32) !important;' +
      'background:rgba(140,150,170,.16) !important;color:inherit !important;' +
      'font-size:12px !important;font-weight:500 !important;line-height:1 !important;' +
      'font-family:inherit !important;z-index:3 !important;' +
      'pointer-events:auto !important;-webkit-tap-highlight-color:transparent;}' +
    '[' + IPTV_BTN_ATTR + '][' + IPTV_OPEN_ATTR + '="1"]{' +
      'border-color:rgba(59,130,246,.55) !important;' +
      'background:rgba(59,130,246,.20) !important;color:#A8C8FF !important;}' +
    // mark() 会把「贴顶、高 20–240px、position 为 sticky/fixed 或 class 含
    // sticky」的块打上 data-kvideo-topbar，而那条规则会把该属性压成 36px 高。
    // 折叠后的筛选条正好落进 20–240 这个区间，不排除就会被压成一条横杠。
    '[' + IPTV_BAR_ATTR + ']{height:auto !important;min-height:0 !important;}' +
    // 直播播放层：网页自己的关闭钮（右上角）在某些状态下整条顶栏看不到。
    // 这枚是我们兜底的关闭钮，只在检测不到网页那枚可见时才创建。
    '[' + IPTV_CLOSE_ATTR + ']{position:fixed !important;top:calc(env(safe-area-inset-top,0px) + 12px) !important;' +
      'right:12px !important;width:36px !important;height:36px !important;' +
      'border-radius:50% !important;border:1px solid rgba(255,255,255,.18) !important;' +
      'background:rgba(0,0,0,.55) !important;color:#fff !important;' +
      'display:flex !important;align-items:center !important;justify-content:center !important;' +
      'font-size:18px !important;line-height:1 !important;z-index:2147483646 !important;' +
      'padding:0 !important;-webkit-tap-highlight-color:transparent;}' +
    // 播放器底部控制条原本贴着屏幕下沿，全面屏手势上滑很容易误触到它。
    // 加一点底部留白：env() 在没有 viewport-fit=cover 时是 0，所以固定补 10px 兜底。
    '[data-controls][class*="bottom-0"]{' +
      'padding-bottom:calc(10px + env(safe-area-inset-bottom, 0px)) !important;}' +
    // 迷你播放器：直播页把全屏浮层收成顶部一条，下面的频道列表露出来可以直接换台。
    '[' + IPTV_MINI_ATTR + '="1"]{' +
      'position:fixed !important;' +
      'top:calc(env(safe-area-inset-top, 0px) + var(--kv-mini-top, 8px)) !important;' +
      'left:8px !important;right:8px !important;bottom:auto !important;' +
      'height:32vh !important;max-height:32vh !important;min-height:0 !important;' +
      'border-radius:18px !important;overflow:hidden !important;z-index:9998 !important;' +
      'border:1px solid rgba(255,255,255,.10) !important;' +
      'box-shadow:0 10px 28px rgba(0,0,0,.55) !important;}' +
    '[' + IPTV_MINI_ATTR + '="1"] > div:first-child{width:100% !important;flex:1 1 100% !important;}' +
    '[' + IPTV_MINI_ATTR + '="1"] [data-controls],' +
    '[' + IPTV_MINI_ATTR + '="1"] [data-sidebar]{display:none !important;}' +
    '[' + IPTV_MINI_ATTR + '="1"] video{object-fit:contain !important;}' +
    '[' + IPTV_MINI_BTNS_ATTR + ']{position:fixed !important;z-index:9999 !important;' +
      'top:calc(env(safe-area-inset-top, 0px) + var(--kv-mini-top, 8px) + 8px) !important;right:16px !important;' +
      'display:flex !important;gap:8px !important;align-items:center !important;}' +
    '[' + IPTV_MINI_BTNS_ATTR + '] button{width:34px !important;height:34px !important;' +
      'border-radius:50% !important;border:1px solid rgba(255,255,255,.18) !important;' +
      'background:rgba(0,0,0,.55) !important;color:#fff !important;font-size:15px !important;' +
      'line-height:1 !important;display:flex !important;align-items:center !important;' +
      'justify-content:center !important;padding:0 !important;' +
      '-webkit-tap-highlight-color:transparent;}';

  // 收起时必须包含注入菜单自己：ATTR 是「顶栏行」的属性（data-kvideo-topbar），
  // 只藏行的话菜单还浮在原处 —— 这就是「上滑后栏还在、只剩图标」的原因。
  var sels = ['[data-kvideo-menu]', '[' + ATTR + ']', NAV, HEADER];
  var CSS =
    sels.join(',') + '{transition:transform .22s ease,opacity .22s ease !important;}' +
    prefixed('html.' + CLS + ' ', sels) + '{' + HIDE + '}' +
    // 收起的只是菜单本身，页面那一行还占着高度，会留下一条空白。
    // 这里把标记过的顶栏行（data-kvideo-topbar）压成 0 高。
    prefixed('html.' + CLS + ' ', ['[' + TOPBAR_ATTR + ']']) +
      '{height:0 !important;min-height:0 !important;max-height:0 !important;' +
      'padding-top:0 !important;padding-bottom:0 !important;' +
      'margin-top:0 !important;margin-bottom:0 !important;' +
      'border:0 !important;overflow:hidden !important;}' +
    prefixed('html.' + READY_CLS + ' ', [NATIVE_NAV, NATIVE_HEADER]) +
      '{display:none !important;}' +
    prefixed('html.' + READY_CLS + ' ', ['[' + NATIVE_ATTR + ']']) +
      '{display:none !important;}' +
    // 站名文字：以前整段藏掉（只留 logo 圆标），现在按效果图放出来。
    // 只给文字元素定排版，容器不再 font-size:0（否则裸文本节点一起被清空）；
    // img/svg 仍然藏，避免和圆标重复。
    // 站名压成一行：只留「KVideo」这一个文字叶子，副标题（视频聚合平台）整段隐藏。
    // 顶栏因此从约 44dp 回到 36dp，图标与文字在垂直方向同一条中线。
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_SUB_ATTR + ']']) +
      '{display:none !important;}' +
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_NAME_ATTR + ']']) +
      '{display:inline-block !important;' +
      'font-size:15px !important;font-weight:600 !important;line-height:36px !important;' +
      'letter-spacing:.2px !important;margin-left:8px !important;' +
      // 不再用省略号：站名就是「KVideo」，截断只会显示不全
      'max-width:none !important;white-space:nowrap !important;overflow:visible !important;' +
      'text-overflow:clip !important;vertical-align:middle !important;flex:0 0 auto !important;' +
      'color:inherit !important;}' +
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_TEXT_ATTR + ']']) +
      '{vertical-align:middle !important;}' +
    // 关键：品牌容器不许被压缩。网页的 flex 布局会把它挤到只剩 logo 的宽度，
    // 文字就成了「视频聚…」——不是 max-width 的锅，是父容器宽度不够。
    prefixed('html.' + READY_CLS + ' ', ['[' + BRAND_ATTR + ']']) +
      '{font-size:inherit !important;flex:0 0 auto !important;flex-shrink:0 !important;' +
      'max-width:none !important;overflow:visible !important;white-space:nowrap !important;' +
      'height:36px !important;min-height:0 !important;align-items:center !important;}' +
    // 行高回到 36dp：原来两行文字把它撑到约 44dp。纵向 padding 一起收掉，
    // 不然只剩一行时行高仍被 padding 顶住；横 6 个图标 36dp 内垂直居中。
    prefixed('html.' + READY_CLS + ' ', ['[' + TOPBAR_ATTR + ']']) +
      '{min-height:36px !important;height:36px !important;' +
      'padding-top:0 !important;padding-bottom:0 !important;align-items:center !important;}' +
    favorites.join(',') + ',' + history.join(',') + '{display:none !important;}' +
    prefixed('html.' + FAIL_CLS + ' ', history) + '{display:block !important;}' +
    MENU_CSS + SEEK_CSS + RATE_CSS + SPEED_MENU_CSS + SPEED_MENU_ANIM + MORE_CSS + MORE_MENU_CSS +
    THEME_HINT_CSS + DOCK_CSS + HUD_CSS + LOGO_CSS + IPTV_CSS;

  function btn(act, label, icon) {
    return '<button type="button" data-kvideo-act="' + act + '" ' +
      'aria-label="' + label + '" title="' + label + '">' + icon + '</button>';
  }
  function item(act, label, icon) {
    return '<button type="button" data-kvideo-act="' + act + '">' + icon +
      '<span>' + label + '</span></button>';
  }
  // 顶栏右侧分两段胶囊：
  //   第一段（导航）收藏 / 历史 / 直播 / 设置齿轮 —— 紧贴 logo
  //   第二段（外观）浅色 / 深色 / 跟随系统
  // 原来的「更多」三点去掉，改成齿轮直接弹层；直播从弹层里提出来成为常驻图标，
  // 弹层只剩网站设置 / 应用设置 / 退出登录（破坏性操作压到最底并标红）。
  function grp(inner) {
    return '<div class="kvideo-group">' + inner + '</div>';
  }
  function menuHtml() {
    // 图标 7 → 6：三档主题合成一个循环钮，空出来的位置给「重新加载」，
    // 组内间距同时从 4px 放到 8px。7×32+6×4=248 → 6×32+5×8=232，反而更宽。
    return grp(
        btn('favorites', '我的收藏', ICON.heart) +
        btn('history', '观看历史', ICON.history) +
        btn('live', '直播', ICON.tv) +
        btn('reload', '重新加载页面', ICON.refresh) +
        btn('more', '设置', ICON.gear) +
        '<div class="kvideo-menu-popover" data-kvideo-popover hidden>' +
          item('websettings', '网站设置', ICON.globe) +
          item('appsettings', '应用设置', ICON.link) +
          '<div class="kvideo-menu-hr"></div>' +
          item('logout', '退出登录', ICON.logout) +
        '</div>'
      ) +
      grp(
        '<button type="button" data-kvideo-act="themecycle" aria-label="切换主题"' +
          ' title="切换主题"><span class="kvideo-ico">' + ICON.monitor + '</span></button>'
      );
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
      // 展开态用独立属性：以前借用 data-kvideo-active，导致点开「更多」时
      // 主题图标跟着变蓝，看起来像主题被切换。
      if (open) m.setAttribute('data-kvideo-open', '1');
      else m.removeAttribute('data-kvideo-open');
    }
    // 先取消 hidden 再量宽度，否则 offsetWidth 是 0。
    if (open) positionPopover();
  }

  // 弹层水平居中到齿轮。CSS 里那句 right:-72px 是按 34px 按钮算出来的常数
  // （17 − 88 = −71，约等于 −72），而按钮在 ≥640dp 变 40px、≤360dp 变 30px 时
  // 居中会偏 1–4px。展开时用实测几何算一次，并夹住左右屏幕边缘。
  // 注意 `right` 的语义：元素右沿 = 包含块右沿 − right，所以负值才代表伸出组外。
  function positionPopover() {
    var p = popEl(), m = moreEl();
    if (!p || !m || !p.getBoundingClientRect) return;
    var g = p.parentNode;                // 所在的 .kvideo-group，只负责定位
    if (!g || !g.getBoundingClientRect) return;
    var gr = g.getBoundingClientRect(), mr = m.getBoundingClientRect();
    var pw = p.offsetWidth || 176;
    if (!gr.width || !mr.width) return;
    var right = gr.right - (mr.left + mr.width / 2 + pw / 2);
    var vw = window.innerWidth || document.documentElement.clientWidth || 0;
    if (vw > 0) {
      right = Math.max(right, gr.right - (vw - 8));   // 右沿不越出屏幕
      right = Math.min(right, gr.right - 8 - pw);     // 左沿不越出屏幕
    }
    p.style.setProperty('right', Math.round(right) + 'px', 'important');
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
    if (act === 'more') {
      if (diagFired) { diagFired = false; return; }
      togglePopover(); return;
    }
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
    } else if (act === 'reload') {
      try { location.reload(); } catch (e) {}
    } else if (act === 'themecycle') {
      // 循环顺序：浅色(0) → 深色(1) → 跟随系统(2) → 浅色
      var ti = themeIndex();
      var next = (ti < 0) ? 0 : (ti + 1) % 3;
      applyTheme(next);
      showThemeHint(next);
    }
  }

  // 三档合成一个钮之后，「还有第 3 档」这件事只靠图标传达不出来，
  // 所以第一次切档时浮一条提示，2.5 秒淡出，之后再点不再打扰。
  var THEME_NAMES = ['浅色', '深色', '跟随系统'];
  var themeHintEl = null, themeHintTimer = null;
  function showThemeHint(next) {
    if (!themeNodes()[next]) return;            // 页面上没有主题按钮（播放页），不提示
    try {
      if (localStorage.getItem('kv-theme-hint') === '1') return;
      localStorage.setItem('kv-theme-hint', '1');
    } catch (e) {}
    if (!themeHintEl) {
      themeHintEl = document.createElement('div');
      themeHintEl.setAttribute('data-kvideo-theme-hint', '1');
      document.body.appendChild(themeHintEl);
    }
    themeHintEl.textContent = '已切到「' + THEME_NAMES[next] + '」· 再点依次是 ' +
      THEME_NAMES[(next + 1) % 3] + ' → ' + THEME_NAMES[(next + 2) % 3];
    themeHintEl.style.opacity = '1';
    if (themeHintTimer) clearTimeout(themeHintTimer);
    themeHintTimer = setTimeout(function () {
      if (themeHintEl) themeHintEl.style.opacity = '0';
    }, 2500);
  }

  // 稳态不产生 DOM 变更：只在值真的变化时才写，避免观察器自激循环
  // 图标恒定显示：以前「页面上找不到原生控件就隐藏」，播放页没有收藏/历史/
  // 直播入口就少几个图标，顶栏自然和首页不一致。
  function syncAvailability() {
    if (!root) return;
    var all = root.querySelectorAll('[data-kvideo-act]');
    for (var ai = 0; ai < all.length; ai++) {
      if (all[ai].style.display === 'none') all[ai].style.display = '';
    }
    if (true) return;
    var checks = {
      live: 'a[aria-label="直播"]',
      history: history[0],
      logout: 'button[aria-label="退出登录"]',
      websettings: 'a[aria-label="设置"]',
      themecycle: themeSels[0] || themeSels[1] || themeSels[2]
    };
    for (var act in checks) {
      if (!Object.prototype.hasOwnProperty.call(checks, act)) continue;
      var b = root.querySelector('[data-kvideo-act="' + act + '"]');
      if (!b) continue;
      var want = q(checks[act]) ? '' : 'none';
      if (b.style.display !== want) b.style.display = want;
    }
  }
  // 当前网页是浅色还是深色：注入的 CSS 里有一批「白 alpha」的层级反馈
  // （logo 内圈、齿轮展开底、按下底色、主题选中蓝），它们默认写给深色顶栏，
  // 落到浅色顶栏上就变成白铺白 / 白底上的淡蓝。这里判定出实际亮暗，写到
  // html[data-kv-light] 上，由 CSS 选择性覆盖，深色侧规则一条不动。
  //
  // 判定优先级：
  //   1. 主题按钮的选中档（唯一权威，网页自己的状态）
  //   2. 量 body 底色亮度（播放页没有主题按钮时的兜底）
  //   3. 都拿不到就保持上一次的值 —— 绝不能因为「读不到」翻回深色，
  //      否则播放页会在深浅之间来回跳。
  var lightFlag = null;
  var darkQuery = (window.matchMedia ?
    window.matchMedia('(prefers-color-scheme: dark)') : null);

  function bodyIsLight() {
    try {
      var cs = window.getComputedStyle(document.body || document.documentElement);
      var m = String(cs.backgroundColor || '').match(/rgba?\(([^)]+)\)/);
      if (!m) return null;
      var p = m[1].split(',');
      var a = p.length > 3 ? parseFloat(p[3]) : 1;
      if (!(a > 0)) return null;  // body 透明，读不到实质底色
      var lum = 0.2126 * (parseFloat(p[0]) || 0) +
                0.7152 * (parseFloat(p[1]) || 0) +
                0.0722 * (parseFloat(p[2]) || 0);
      return lum > 150;
    } catch (e) { return null; }
  }

  function applyLightFlag() {
    // 0 浅色 / 1 深色 / 2 跟随系统 / -1 认不出
    var idx = themeIndex();
    var light;
    if (idx === 0) light = true;
    else if (idx === 1) light = false;
    else if (idx === 2) light = darkQuery ? !darkQuery.matches : false;
    else light = bodyIsLight();
    if (light === null || light === undefined) return;
    if (light === lightFlag) return;
    lightFlag = light;
    document.documentElement.setAttribute('data-kv-light', light ? '1' : '0');
    // 顺手告诉壳层，让状态栏/导航栏跟着换底色与图标明暗（浅色网页配白栏 + 深图标）。
    // 桥没注入或方法不存在就静默跳过 —— 这条只影响观感，绝不能影响顶栏本身。
    try {
      var bridge = window.KVideoAndroid;
      if (bridge && bridge.setWebTheme) bridge.setWebTheme(light);
    } catch (e) {}
  }

  // 「跟随系统」档：系统切深浅色时不重新加载网页也要立刻跟上。
  // 只在 idx === 2 时有意义，但这里无条件挂一个空转的监听更省心。
  if (darkQuery) {
    if (darkQuery.addEventListener) darkQuery.addEventListener('change', applyLightFlag);
    else if (darkQuery.addListener) darkQuery.addListener(applyLightFlag);
  }

  function syncThemeState() {
    if (!root) return;
    var idx = themeIndex();
    var b = root.querySelector('[data-kvideo-act="themecycle"]');
    if (!b) return;
    // 图标 = 当前档位；蓝色只给「浅色 / 深色」，跟随系统用中性色，
    // 否则会被读成「已经选定某个主题」。
    var key = String(idx);
    if (b.getAttribute('data-kv-idx') !== key) {
      b.setAttribute('data-kv-idx', key);
      var ico = b.querySelector('.kvideo-ico');
      if (ico) ico.innerHTML = (idx === 0) ? ICON.sun : (idx === 1 ? ICON.moon : ICON.autoTheme);
      var names = THEME_NAMES || ['浅色', '深色', '跟随系统'];
    var label = (idx === 0 || idx === 1 || idx === 2) ?
        ('当前' + names[idx] + '主题，点按切换') : '切换主题';
      b.setAttribute('aria-label', label);
      b.setAttribute('title', label);
    }
    var want = (idx === 0 || idx === 1);
    var has = b.hasAttribute('data-kvideo-active');
    if (want && !has) b.setAttribute('data-kvideo-active', '1');
    else if (!want && has) b.removeAttribute('data-kvideo-active');
  }

  var failTimer = null;
  function armFallback() {
    if (failTimer) return;
    failTimer = setTimeout(function () {
      failTimer = null;
      var m = q('[data-kvideo-menu]');
      if (!m || !m.parentNode) {
        document.documentElement.classList.remove(READY_CLS);
        document.documentElement.classList.add(FAIL_CLS);
      }
    }, 1500);
  }
  function disarmFallback() {
    if (failTimer) { clearTimeout(failTimer); failTimer = null; }
    document.documentElement.classList.remove(FAIL_CLS);
  }

  function mark() {
    var nodes = document.querySelectorAll(
      'nav,header,[class*="navbar"],[class*="topbar"],[class*="top-0"],[class*="sticky"]');
    var w = window.innerWidth || document.documentElement.clientWidth;
    var maxTop = Math.max(160, (window.innerHeight || 800) * 0.25);
    // 直播页正在播时整页不打标：播放层自己的顶栏（LIVE + 频道名 + 关闭钮）
    // class 含 top-0，被打上 data-kvideo-topbar 后会被压成 36px 高。
    try { if (iptvPlaying()) return; } catch (eL) { }
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var r = el.getBoundingClientRect();
      // 直播页筛选条由 syncIptvFilter 接管，不能被打上 data-kvideo-topbar ——
      // 那条属性会把元素压成 36px 高（折叠后的筛选条正好落进打标区间）。
      if (el.hasAttribute(IPTV_BAR_ATTR)) continue;
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
    var brand = null, brandIdx = -1, i, j, el, n, txt, raw, logo;
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
    // 同尺寸圆形矢量 logo 替换上游紫色实心方块。幂等：已插入就不再动 DOM，
    // 避免 MutationObserver 反复触发；原图只打标隐藏，不删除，保住品牌容器宽度。
    if (!brand.querySelector('[' + LOGO_ATTR + ']')) {
      raw = brand.querySelector('img,svg,picture,canvas');
      if (raw) {
        if (!raw.hasAttribute(BRAND_IMG_ATTR)) raw.setAttribute(BRAND_IMG_ATTR, '1');
        logo = document.createElement('span');
        logo.setAttribute(LOGO_ATTR, '1');
        logo.innerHTML = LOGO_SVG;
        if (raw.parentNode) raw.parentNode.insertBefore(logo, raw);
      }
    }
    var nodes = brand.querySelectorAll('*');
    var named = brand.querySelector('[' + BRAND_NAME_ATTR + ']') ? true : false;
    for (j = 0; j < nodes.length; j++) {
      n = nodes[j];
      if (n.closest && n.closest('svg')) continue;
      if (n.children.length) continue;
      txt = String(n.textContent || '').trim();
      if (!txt || txt.length > 24) continue;
      if (!n.hasAttribute(BRAND_TEXT_ATTR)) n.setAttribute(BRAND_TEXT_ATTR, '1');
      // 第一个文字叶子是站名（KVideo），后面的都是副标题（视频聚合平台）。
      // 压成一行后副标题整个隐藏，只留站名。
      if (!named) {
        if (!n.hasAttribute(BRAND_NAME_ATTR)) n.setAttribute(BRAND_NAME_ATTR, '1');
        named = true;
      } else if (!n.hasAttribute(BRAND_SUB_ATTR)) {
        n.setAttribute(BRAND_SUB_ATTR, '1');
      }
    }
    // 顶栏行打标：它自己多半是 flex 容器，行高由内容与 padding 决定，
    // 压成一行后需要把 min-height 与纵向 padding 一起收掉才能真的回到 36dp。
    // 从上往下找「同时装着品牌容器和注入菜单」的那一层，它才是真正的顶栏行；
    // 只按 parentElement 打标有可能打在行内的一个左对齐分组上，把分组压扁。
    var rowEl = brand.parentElement, hops = 0;
    while (rowEl && hops < 4) {
      if (rowEl.querySelector && rowEl.querySelector('[data-kvideo-menu]')) break;
      rowEl = rowEl.parentElement;
      hops++;
    }
    if (rowEl && rowEl.querySelector && rowEl.querySelector('[data-kvideo-menu]') &&
        !rowEl.hasAttribute(TOPBAR_ATTR)) {
      rowEl.setAttribute(TOPBAR_ATTR, '1');
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
"""
        private val CHROME_INJECT_JS_B = """  var SVG_PROPS = {
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
    // 直播播放层里没有可 seek 的时间轴（IPTVPlayer 压根没有快进/倍速钮），
    // 壳这边不去缩、不去标，免得在真机上凑出多余的小图标。
    try { if (iptvPlaying()) return; } catch (eS) {}
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
  // 手势诊断：真机上「手势没反应」时，唯一能拿到证据的渠道。
  // 每个闸门都往这里记一条原因，长按顶栏齿轮把整份 JSON 复制到剪贴板。
  var gestureDiag = {
    starts: 0, moves: 0, ends: 0, mode: null, reject: null, onVideo: null,
    inPlayer: null, hit: '', hitBlock: '', hud: 0, hudDisplay: '', scrub: 0,
    bar: null, barW: 0, videoDur: null
  };

  // 菜单展开瞬间的样式快照：确认「两次动画」到底来自网页的 transition、
  // 我们自己的 animation，还是子元素各自动了一套。
  var menuDiag = { speed: null, more: null };
  var menuTrace = [];
  var menuTraceSeq = 0;
  function startMenuTrace(menu) {
    menuTrace = [];
    var seq = ++menuTraceSeq;
    menu.__kvSeq = seq;
    var t0 = (window.performance && performance.now) ? performance.now() : Date.now();
    var last = null;
    function nowMs() { return ((window.performance && performance.now) ? performance.now() : Date.now()) - t0; }
    function tick() {
      var t = Math.round(nowMs());
      var el = findSpeedMenu();
      var r = el ? el.getBoundingClientRect() : null;
      var cs = null;
      try { cs = el ? window.getComputedStyle(el) : null; } catch (e) { cs = null; }
      var rec = {
        t: t,
        sameNode: el === menu,
        seq: el ? (el.__kvSeq || 0) : -1,
        x: r ? Math.round(r.left) : 0, y: r ? Math.round(r.top) : 0,
        w: r ? Math.round(r.width) : 0, h: r ? Math.round(r.height) : 0,
        il: el ? String(el.style.left || '') : '',
        ib: el ? String(el.style.bottom || '') : '',
        tf: cs ? String(cs.transform || '').slice(0, 28) : ''
      };
      if (!last || rec.x !== last.x || rec.y !== last.y || rec.w !== last.w || rec.h !== last.h ||
          rec.seq !== last.seq || rec.il !== last.il || rec.ib !== last.ib) {
        if (menuTrace.length < 20) menuTrace.push(rec);
        last = rec;
      }
      if (t < 700) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  }
  function snapMenu(el) {
    if (!el) return null;
    try {
      var cs = window.getComputedStyle(el);
      var kids = 0, c = el.children || [];
      for (var i = 0; i < c.length; i++) {
        var k = window.getComputedStyle(c[i]);
        if (k.animationName !== 'none') kids++;
        else if (k.transitionDuration && k.transitionDuration !== '0s') kids++;
      }
      return {
        cls: String(el.className || '').slice(0, 70),
        trans: cs.transitionProperty + '/' + cs.transitionDuration,
        anim: cs.animationName + '/' + cs.animationDuration,
        kidsMoving: kids, kids: c.length
      };
    } catch (e) { return 'err'; }
  }
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
    gestureDiag.hud++;
    try {
      gestureDiag.hudDisplay = window.getComputedStyle(h).display;
      var hr = h.getBoundingClientRect();
      gestureDiag.hudRect = [Math.round(hr.left), Math.round(hr.top),
        Math.round(hr.width), Math.round(hr.height)];
      // 浮层中心点「压在最上面的是谁」：不是 HUD 本身，说明有别的东西盖住它
      var topEl = document.elementFromPoint(
        hr.left + hr.width / 2, hr.top + hr.height / 2);
      gestureDiag.hudTop = topEl ? (topEl.id || topEl.tagName) : 'null';
    } catch (e) {}
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
  // 播放器卡片范围：从 video 往上找同时包含进度条的那层容器。
  // 页面内播放器的 video 只占卡片一小块（线上实测 video 353×199、视口 385 宽，
  // 92%×26%），手指按到卡片里的黑边或控制区就落空 —— 所以起手判定用「卡片」
  // 而不是「video 本身」。找不到就退回 video 自身。
  function playerBox() {
    var v = document.querySelector('video');
    if (!v) return null;
    var bar = findProgressBar();
    var box = v.parentElement, hops = 0;
    while (box && box !== document.body && hops < 6) {
      if (bar && box.contains && box.contains(bar)) break;
      box = box.parentElement; hops++;
    }
    return box || v;
  }
  function hitInPlayer(x, y) {
    var box = playerBox();
    if (!box) return false;
    var r = box.getBoundingClientRect();
    if (!(r.width > 0 && r.height > 0)) return false;
    return x >= r.left - 8 && x <= r.right + 8 &&
           y >= r.top - 8 && y <= r.bottom + 8;
  }
  // 播放器在竖屏与全屏各挂一套控制栏时，querySelector('.player-controls-right')
  // 命中的可能是当前不可见的那一套 —— 我们会对着看不见的按钮摆位置、写样式，
  // 屏幕上那套被另一条路径重复处理，表现就是「点一次、动两次」。
  // 所有抓取统一走「可见优先」。
  function visibleRect(el) {
    if (!el || !el.getBoundingClientRect) return null;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return null;
    var cs;
    try { cs = window.getComputedStyle(el); } catch (e) { return null; }
    if (!cs || cs.display === 'none' || cs.visibility === 'hidden') return null;
    if (cs.opacity === '0') return null;
    return r;
  }
  function firstVisible(sel, minW, minH) {
    var l = document.querySelectorAll(sel);
    for (var i = 0; i < l.length; i++) {
      var r = visibleRect(l[i]);
      if (r && r.width >= (minW || 20) && r.height >= (minH || 10)) return l[i];
    }
    return l[0] || null;
  }
  function controlsBar() {
    return firstVisible('.player-controls-right', 20, 10);
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
      // 和倍速菜单同一个道理：先藏起来把 zoom/宽度写进去再显示。
      // 否则网页会先按原尺寸把这面板画出来，我们再缩到 82% —— 用户看到的
      // 就是「弹出来一次、又挪/缩一次」。
      best.style.setProperty('visibility', 'hidden', 'important');
      best.setAttribute(MORE_MENU_ATTR, '1');
      // 触发一次样式重算，确保新规则（zoom / width）先落地再显示
      void best.offsetWidth;
      best.style.setProperty('visibility', 'visible', 'important');
    }
  }
  // 网页的浮层菜单（播放设置、倍速面板等）是 Portal 到 body 的高 z-index 面板，
  // 没有 role 也没有我们的标记，原来只有 elInOverlay 那几条命中不了 —— 于是
  // 在菜单上横滑/竖滑会被当成播放手势（右半屏就变成调亮度）。
  // 这里用两条页面上很稳定的特征来认：z-index 极高，或 backdrop 毛玻璃面板。
  var PANEL_SEL = '[role="menu"],[role="dialog"],[data-radix-popper-content-wrapper]' +
    ',[class*="z-[2147483647]"]';
  function panelAt(el) {
    var hops = 0;
    while (el && el !== document.body && hops < 24) {
      var cs;
      try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
      if (cs && (cs.position === 'fixed' || cs.position === 'absolute')) {
        var z = parseInt(cs.zIndex, 10);
        if (!isNaN(z) && z >= 1000) return el;
        if (cs.backdropFilter && cs.backdropFilter !== 'none') return el;
      }
      el = el.parentElement;
      hops++;
    }
    return null;
  }
  var panelSeenCount = 0;
  var panelSeenAt = 0;
  var panelFlagOn = false;
  // JS 与原生共享「网页面板开着」这个状态：原生全屏手势要靠它让开。
  function pushPanelFlag(open) {
    if (panelFlagOn === open) return;
    panelFlagOn = open;
    try {
      if (window.KVideoAndroid && window.KVideoAndroid.setWebPanelOpen) {
        window.KVideoAndroid.setWebPanelOpen(open);
      }
    } catch (e) {}
  }
  // 菜单开着时，连菜单外面那一片也不该起手势：手指往往从面板边缘外落下。
  function panelOpen() {
    var l = document.querySelectorAll(PANEL_SEL);
    for (var i = 0; i < l.length; i++) {
      var e = l[i], r = e.getBoundingClientRect();
      if (r.width < 60 || r.height < 60) continue;
      // 整屏遮罩不算面板：它出现时手势本来就该继续工作
      var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
      if (vw > 0 && vh > 0 && r.width > vw * 0.95 && r.height > vh * 0.95) continue;
      var cs;
      try { cs = window.getComputedStyle(e); } catch (err) { continue; }
      if (!cs || cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') continue;
      return e;
    }
    return null;
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
    if (h > 0 && (y < h * 0.12 || y > h * 0.78)) {
      gestureDiag.reject = 'edge:y=' + Math.round(y) + '/' + h;
      return false;
    }
    var el = document.elementFromPoint(x, y);
    gestureDiag.hit = el ? (el.tagName + '.' + String(el.className || '').slice(0, 40)) : 'null';
    if (el && el.closest && (
        el.closest('[data-kvideo-menu]') ||
        el.closest('input,select,textarea') ||
        el.closest('[role="dialog"]') ||
        el.closest('[data-kvideo-popover]') ||
        el.closest('[data-kvideo-hud]') ||
        el.closest('.slider-track') ||
        el.closest('button,a'))) {
      gestureDiag.reject = 'widget';
      gestureDiag.hitBlock = (function () {
        var n = el.closest(
          '[data-kvideo-menu],input,select,textarea,[role="dialog"],' +
          '[data-kvideo-popover],[data-kvideo-hud],.slider-track,button,a');
        return n ? n.tagName + (n.getAttribute('aria-label') ? '[' + n.getAttribute('aria-label') + ']' : '') : '?';
      })();
      return false;
    }
    // 三点菜单/倍速菜单里上下滑只滚菜单，不触发音量、亮度、拖进度
    if (elInOverlay(el)) {
      gestureDiag.reject = 'overlay';
      return false;
    }
    // 网页自带的浮层面板（播放设置 / 倍速）：落在面板上或面板开着时都不起手势
    var pnl = panelAt(el) || panelOpen();
    pushPanelFlag(!!pnl);
    if (pnl) {
      gestureDiag.reject = 'panel';
      gestureDiag.hitBlock = 'PANEL.' + String(pnl.className || '').slice(0, 40);
      return false;
    }
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
    gestureDiag.starts++;
    if (ev.touches.length !== 1) { gestureDiag.reject = 'multitouch'; scrub = null; return; }
    var t0 = ev.touches[0];
    // 手指直接落在 <video> 上也放行：1.1.53 的线上数据里，页面内播放器的
    // video 只有视口的 92%×26%（竖屏 353×199），isFullscreenPlayback() 的
    // 90%×60% 阈值永远过不去，于是横滑被判 not-fullscreen 直接丢弃。
    // 判定「在看视频」用命中的元素比用几何比例更准，也不会误伤浏览页。
    var hitEl = document.elementFromPoint(t0.clientX, t0.clientY);
    var onVideo = !!(hitEl && hitEl.tagName === 'VIDEO');
    // 卡片内（video + 黑边 + 控制区）都算「在看视频」，卡片外才要求几何全屏
    var inPlayer = onVideo || hitInPlayer(t0.clientX, t0.clientY);
    gestureDiag.onVideo = onVideo;
    gestureDiag.inPlayer = inPlayer;
    if (!inPlayer && !isFullscreenPlayback()) {
      gestureDiag.reject = 'not-fullscreen';
      scrub = null; return;
    }
    markOverlayMenus();
    if (overlayMenuVisible()) { gestureDiag.reject = 'overlay-menu'; scrub = null; return; }
    var t = t0;
    if (!seekGestureAllowed(t.clientX, t.clientY)) { scrub = null; return; }
    var info = videoInfo();
    if (!info) {
      var vv = document.querySelector('video');
      gestureDiag.videoDur = vv ? String(vv.duration) : 'no-video';
      // 直播流 duration = Infinity：没有可拖动的时间轴，但纵向的音量/亮度
      // 仍然有用（全屏判定照旧）。开一个 live 态的 scrub，横向在 move 里丢弃。
      // 只有 duration === Infinity 才算直播流；NaN（元数据未到）仍按老逻辑丢弃，
      // 免得点播加载中也能起纵向手势。
      if (!vv || vv.duration !== Infinity) {
        gestureDiag.reject = 'no-video-info';
        scrub = null; return;
      }
      gestureDiag.reject = null;
      gestureDiag.bar = 'live';
      gestureDiag.barW = 0;
      scrub = {
        x: t.clientX, y: t.clientY, lastY: t.clientY, t0: 0, d: 0, v: vv,
        width: 0, rect: null, bar: null, usable: false, baseX: 0,
        side: t.clientX < (window.innerWidth || 0) / 2 ? 'left' : 'right',
        mode: null, target: 0, synthStarted: false,
        onVideo: onVideo, inPlayer: inPlayer, live: true
      };
      return;
    }
    var bar = findProgressBar();
    var rect = bar ? bar.getBoundingClientRect() : null;
    var usable = !!(rect && rect.width > 120);
    gestureDiag.reject = null;
    gestureDiag.mode = null;
    gestureDiag.hitBlock = '';
    gestureDiag.bar = bar ? (bar.className || bar.tagName) : 'none';
    gestureDiag.barW = rect ? Math.round(rect.width) : 0;
    gestureDiag.videoDur = info.d;
    scrub = {
      x: t.clientX, y: t.clientY, lastY: t.clientY,
      t0: info.t, d: info.d, v: info.v,
      width: usable ? rect.width : (window.innerWidth || 1280) * 0.9,
      rect: rect, bar: bar, usable: usable,
      baseX: usable ? rect.left + (info.t / info.d) * rect.width : 0,
      side: t.clientX < (window.innerWidth || 0) / 2 ? 'left' : 'right',
      mode: null, target: info.t, synthStarted: false,
      onVideo: onVideo, inPlayer: inPlayer
    };
  }
  function onSeekTouchMove(ev) {
    if (synthDispatching || !scrub || ev.touches.length !== 1) return;
    gestureDiag.moves++;
    // 滑动途中退出全屏也要立刻停手，避免继续调音量/亮度；
    // 从播放器卡片上起手的手势不依赖全屏判定，不能因此中途被砍。
    if (!scrub.onVideo && !scrub.inPlayer && !isFullscreenPlayback()) { scrub = null; return; }
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
        if (scrub.live) {   // 直播：横向拖动没有意义，直接放手
          gestureDiag.reject = 'live-no-seek';
          scrub = null; return;
        }
        scrub.mode = 'scrub';
        gestureDiag.mode = 'scrub';
        gestureDiag.scrub++;
        if (scrub.usable) {
          scrub.synthStarted = synthTouch('touchstart', scrub.bar,
            kvClamp(scrub.baseX, scrub.rect.left, scrub.rect.right),
            scrub.rect.top + scrub.rect.height / 2);
        }
      } else if (Math.abs(dy) >= Math.abs(dx) * AXIS_RATIO) {
        // 纵向（音量 / 亮度）只在全屏播放时启用。页面内播放器虽然也放大了
        // 起手范围，但那里纵向手势没有意义，还会和网页自己的滚动抢事件。
        if (!isFullscreenPlayback()) {
          gestureDiag.reject = 'vertical-non-fullscreen';
          scrub = null; return;
        }
        scrub.mode = 'vertical';
        gestureDiag.mode = 'vertical';
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
      var v = scrub.v, target = scrub.target;
      gestureDiag.seekTarget = Math.round(target);
      gestureDiag.seekBefore = v ? Math.round((v.currentTime || 0) * 10) / 10 : -1;
      try {
        if (v) v.currentTime = target;
        gestureDiag.seekError = null;
      } catch (e) {
        gestureDiag.seekError = String(e).slice(0, 60);
      }
      // 500ms 后回读，回答「到底跳没跳」——这是本轮唯一还缺的证据。
      setTimeout(function () {
        try {
          if (!v) return;
          gestureDiag.seekAfter = Math.round((v.currentTime || 0) * 10) / 10;
          gestureDiag.seekOK =
            Math.abs(v.currentTime - gestureDiag.seekTarget) < 3;
        } catch (e) {}
      }, 500);
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
    var exact = firstVisible(
      'button[aria-label="更多选项"]:not([data-kvideo-act])', 16, 16);
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
    var b = firstVisible('button[aria-label="播放速度"]', 20, 20);
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
    try { if (iptvPlaying()) return; } catch (eR) {}
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
    var anchor = controlsBar();
    if (!anchor) return;
    var ar = anchor.getBoundingClientRect();
    if (ar.width < 4 || ar.height < 4) return;
    var host = wrap.offsetParent || wrap.parentElement;
    if (!host) return;
    var hr = host.getBoundingClientRect();

    // 纵向以「最后一个图标钮」为基准，横向插入点必须用「第一个图标钮」的左沿：
    // 拿最后一个的左沿去减，倍速钮会直接压在倒数第二个图标上。
    var mate = lastIconIn(anchor);
    var mr = mate ? mate.getBoundingClientRect() : null;
    var first = firstIconIn(anchor);
    var fr = first ? first.getBoundingClientRect() : null;
    var size = mr ? Math.round(mr.width) : 40;
    if (!(size >= 24 && size <= 64)) size = 40;
    var gap = groupGap(anchor);
    if (!(gap >= 0 && gap <= 32)) gap = 8;

    var left = (fr ? fr.left : ar.left) - hr.left - size - gap;
    var top = mr
      ? mr.top - hr.top + (mr.height - size) / 2
      : ar.top - hr.top + (ar.height - size) / 2;
    if (left < 8) left = 8;
    if (top < 0) top = 0;
    forceStyle(wrap, {
      'position': 'absolute',
      'left': left.toFixed(1) + 'px',
      'top': top.toFixed(1) + 'px',
      'width': size + 'px',
      'height': size + 'px'
    });
    // 尺寸是几何值，直接内联写死；外观（底色/描边/圆角/阴影/字色）交给
    // applyLook 生成的样式规则——只采样一次，不再每个 tick 重写。
    if (mr) {
      var px = size + 'px';
      forceStyle(b, {
        'width': px, 'height': px,
        'min-width': px, 'min-height': px,
        'max-width': px, 'max-height': px
      });
      applyLook(anchor);
    }

    // 显示/隐藏完全跟随控制条：点屏幕时整条一起淡出、再一起淡入，
    // 倍速钮不再自己闪。
    syncRateVisibility(anchor, wrap, b);
  }

  // 控制条整体的「有效不透明度」：从 anchor 往上逐级累加，取最小值。
  //
  // 两条红线（1.1.39 的线上故障就是踩了第一条）：
  //   1. 不认 pointer-events-none。这类播放器为了让点击穿透到视频，控制层
  //      常年挂着 pointer-events-none，靠子按钮自己 pointer-events-auto 接事件。
  //      把它当「已隐藏」，倍速容器和胶囊就会被永久写成 opacity:0——按钮直接
  //      消失，而且组内圆底已被胶囊方案抹平，右侧只剩三个裸图标。
  //   2. 不采样组内兄弟按钮的 opacity。兄弟可能常态就是 0.3（禁用态/分隔条），
  //      采到一次就再也不会亮。
  // 宁可「控制条淡出时倍速钮多亮半拍」，也不接受它消失。
  function barOpacity(node) {
    var el = node, i = 0, min = 1;
    while (el && el.nodeType === 1 && i < 8) {
      var cs = null;
      try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
      if (cs) {
        if (cs.display === 'none' || cs.visibility === 'hidden') return 0;
        var op = parseFloat(cs.opacity);
        if (!isNaN(op) && op < min) min = op;
      }
      el = el.parentElement;
      i++;
    }
    return min;
  }

  // 组内按钮透明度中位数。播放器也可能直接把 opacity 挂在每个按钮上做淡出，
  // 这时祖先链恒为 1，只看祖先链会漏掉整条控制条的淡出。
  // 用中位数而不是最小值：组里常混着禁用态、分隔条（常态 0.3），取最小会被
  // 它们带偏，导致倍速钮永远半透明。奇数个时取偏小的那一个——宁可早淡出。
  function groupOpacity(group) {
    var kids = group.children, ops = [];
    for (var i = 0; i < kids.length; i++) {
      if (!isIconNode(kids[i])) continue;
      var o = parseFloat(window.getComputedStyle(kids[i]).opacity);
      if (!isNaN(o)) ops.push(o);
    }
    if (!ops.length) return 1;
    ops.sort(function (a, b) { return a - b; });
    return ops[Math.floor((ops.length - 1) / 2)];
  }

  // 控制条此刻的「有效不透明度」：祖先链最小值与组内按钮中位数取小。
  // 祖先链覆盖「整条控制条挂 opacity 淡出」，组内中位数覆盖「每个按钮各自
  // 挂 opacity 淡出」——两种播放器实现都能跟住。
  function barVisibleOpacity(anchor) {
    var op = barOpacity(anchor);
    var g = groupOpacity(anchor);
    return g < op ? g : op;
  }

  // 祖先链（**不含自身**）的透明度乘积。
  //
  // 用来回答一个问题：倍速容器自己是不是已经站在控制条的淡出链里了？
  // 很多播放器淡出控制条就是把整条 bar 的 opacity 从 1 拉到 0，如果 1x 挂在
  // bar 里面，它本来就会被父级带着一起淡——这时我再写一遍 opacity 就是
  // **双重淡出**：两层相乘，比邻居暗得更快、更早看不见，表现就是
  // 「不跟别人一起走、还慢半拍」。所以要先把父级已经贡献掉的那部分除掉。
  function chainOpacity(node) {
    var el = node ? node.parentElement : null, i = 0, prod = 1;
    while (el && el.nodeType === 1 && i < 12) {
      var cs = null;
      try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
      if (cs) {
        if (cs.display === 'none') return 0;
        var o = parseFloat(cs.opacity);
        if (!isNaN(o)) prod *= o;
      }
      if (prod <= 0.0001) return 0;
      el = el.parentElement;
      i++;
    }
    return prod;
  }

  // 倍速容器该写多少 opacity，才是「和画中画/全屏同一个亮度」。
  //
  //   v = 控制条祖先链透明度 / 容器祖先链透明度 × 组内按钮透明度
  //
  //   · 容器在淡出链里   → 前两项相除恒为 1，只剩组内那层；一般就是 1，
  //                        也就是**完全不干预**，由父级带着淡，天然同帧
  //   · 容器在淡出链外   → 分母为 1，直接跟随控制条
  //   · 播放器按按钮淡出 → 乘上组内中位数，逐帧对齐
  //
  // 三种实现都不需要我猜，也不存在「我淡一半、它淡一半」的叠加。
  function applyRateOpacity(anchor, wrap) {
    var bar = barOpacity(anchor);
    var base = chainOpacity(wrap);
    var grp = groupOpacity(anchor);
    var v;
    if (base <= 0.0005) {
      // 父级已经彻底不可见：这里写 1，不要跟父级抢着归零。
      v = 1;
    } else {
      v = (bar / base) * grp;
    }
    if (v > 1) v = 1;
    if (v < 0) v = 0;
    if (v < 0.02) v = 0;
    if (v > 0.98) v = 1;
    // 记下控制条自己的实测值：pointer-events 要按它判，不能按映射值判
    // （容器在淡出链里时映射值恒 1，但播放器此刻已经不让点了）。
    wrap.__kvBarOp = bar;
    forceStyle(wrap, {
      'opacity': v === 0 ? '0' : (v === 1 ? '1' : v.toFixed(3))
    });
    return v;
  }

  // 淡入淡出期间用 rAF 逐帧跟随，而不是每 60ms 采样一次：
  // 200ms 的淡出只采到三四个点，写成台阶状；台阶本身加上任何 CSS 过渡，
  // 在「点屏幕」那一下就是一次可见的跳动。逐帧写入没有台阶、也没有滞后。
  var fadeRunning = false;
  var fadeFrames = 0;      // 这次跟随已经跑了多少帧
  var fadePrevBar = -1;    // 上一帧读到的控制条透明度
  var fadeSame = 0;        // 连续多少帧「控制条没再变」

  function fadeTick() {
    var anchor = controlsBar();
    var wrap = document.querySelector('[' + RATE_WRAP_ATTR + '="1"]');
    if (!anchor || !wrap) { fadeRunning = false; return; }

    var bar = barOpacity(anchor);
    applyRateOpacity(anchor, wrap);

    // 控制条淡出的同时常常会上滑/下移。只跟透明度的话，1x 是「留在原地
    // 慢慢变淡」，邻居却已经滑走——用户看到的就是「不整体」。
    // 淡出窗口内顺带逐帧重算位置，几何和透明度走同一条链。
    try { moveRateButton(); } catch (e) {}

    fadeFrames++;
    if (Math.abs(bar - fadePrevBar) < 0.004) fadeSame++; else fadeSame = 0;
    fadePrevBar = bar;

    // 收工条件：控制条已经停在「完全显示」或「完全隐藏」并且连续若干帧
    // 不再变化，且这次跟随已经跑满一小段（约 0.5s）。
    //
    // 之前这里写的是「只要当前值不在 0 和 1 之间就停」——过渡刚起步的第一帧
    // 读出来往往还是 1，于是循环当场就停了；而 CSS 过渡不会产生 DOM 变更，
    // 之后再没有任何东西唤醒它，1x 只能等 1s 保底扫描才跟上。控制条慢慢淡完
    // （250ms），1x 却要等 1s 才「啪」地消失——就是「慢半拍」和「不同步」的
    // 真正来源。现在保证至少跟满 FADE_MIN_FRAMES 帧，不会一启动就退出。
    var settled = fadeSame >= 8 && (bar < 0.02 || bar > 0.98) &&
                  fadeFrames >= 30;
    if (settled || fadeFrames >= 150) {
      fadeRunning = false;
    } else {
      window.requestAnimationFrame(fadeTick);
    }
  }
  // 任何一次变化都可以唤起它：已经在跟就不重复起，停下来了才重新起。
  function startFade() {
    if (fadeRunning || !window.requestAnimationFrame) return;
    fadeRunning = true;
    fadeFrames = 0;
    fadePrevBar = -1;
    fadeSame = 0;
    window.requestAnimationFrame(fadeTick);
  }

  // 控制条显隐可能是「换 class」「写内联 style」「CSS 过渡」「CSS 动画」里的
  // 任何一种。前两种 MutationObserver 能看到，后两种只有过渡/动画事件能听到；
  // 全都接上，1x 就不会漏拍。
  function hookMotion() {
    var evts = ['transitionrun', 'transitionstart', 'animationstart'];
    for (var i = 0; i < evts.length; i++) {
      try {
        document.addEventListener(evts[i], function () { startFade(); }, true);
      } catch (e) {}
    }
  }

  function syncRateVisibility(anchor, wrap, btn) {
    var v = applyRateOpacity(anchor, wrap);
    var barOp = wrap.__kvBarOp;
    if (btn) {
      forceStyle(btn, {
        'pointer-events': (barOp === 0 || v === 0) ? 'none' : 'auto'
      });
    }
    if (v > 0 && v < 1) startFade();
  }

  // 只认「看得见的按钮 / 含 svg 的节点」：组里可能夹着分隔条、占位 div，
  // 拿它们的尺寸当基准会把倍速钮撑成一整条。
  function isIconNode(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 20 || r.height < 20) return false;
    if (window.getComputedStyle(el).display === 'none') return false;
    if (el.tagName === 'BUTTON') return true;
    return !!(el.querySelector && el.querySelector('svg'));
  }

  // 组内最后一个可见的图标钮（右侧组最右通常是全屏，最适合作为外观基准）。
  function lastIconIn(group) {
    var kids = group.children, found = null;
    for (var i = 0; i < kids.length; i++) {
      if (isIconNode(kids[i])) found = kids[i];
    }
    return found;
  }

  // 组内第一个可见的图标钮：倍速钮要插在它左边 size+gap 处。
  function firstIconIn(group) {
    var kids = group.children;
    for (var i = 0; i < kids.length; i++) {
      if (isIconNode(kids[i])) return kids[i];
    }
    return null;
  }

  // 组内相邻两个按钮的真实水平间隔；取不到给 -1，由调用方回落到 8px。
  function groupGap(group) {
    var kids = group.children, prev = null;
    for (var i = 0; i < kids.length; i++) {
      if (!isIconNode(kids[i])) continue;
      var r = kids[i].getBoundingClientRect();
      if (prev) return Math.round(r.left - prev.right);
      prev = r;
    }
    return -1;
  }

  // ── 外观：只采样一次，写进一条独立样式规则 ─────────────────────────────
  //
  // 之前的做法是每个 tick 都从相邻按钮「照抄」底色/描边再写成内联。相邻按钮
  // 的底色会随焦点、悬停变化（全屏钮拿到焦点时会高亮），抄一次写一次，配合
  // CSS 里的 background-color 过渡，底色就在「有」和「没有」之间来回渐变——
  // 用户看到的现象就是 1x 一直在闪。
  //
  // 现在：采样一次 → 生成一条 button[data-kvideo-rate="1"] 规则 → 之后不再重写。
  // 选择器比 RATE_CSS 里的 [data-kvideo-rate="1"] 多一个元素名，特异性更高，
  // 所以能压过它的 background:transparent / border:0 兜底值。
  var LOOK_STYLE_ID = 'kvideo-rate-look';
  var lookEl = null;
  var lookRef = null;

  // 基准节点必须是「没被聚焦的真 button」：组内最后一个通常是全屏，它拿到
  // 焦点时底色会被高亮，抄它等于把高亮样式固化到 1x 上。
  function pickLookRef(group) {
    var kids = group.children, fallback = null;
    for (var i = kids.length - 1; i >= 0; i--) {
      if (!isIconNode(kids[i])) continue;
      if (kids[i] === document.activeElement) continue;
      if (kids[i].tagName === 'BUTTON') return kids[i];
      if (!fallback) fallback = kids[i];
    }
    return fallback;
  }

  // 组内底色取「众数」：触屏上 :hover / 焦点会粘在刚点过的那个按钮上（比如
  // 全屏钮一直是高亮白底），单取某一个按钮就会把高亮态当成常态抄过来。
  // 组里通常三个按钮，众数必然是那两个没被碰过的。
  function groupBgMode(group) {
    var kids = group.children, m = {}, best = null, bestN = 0;
    for (var i = 0; i < kids.length; i++) {
      if (!isIconNode(kids[i])) continue;
      var bg = window.getComputedStyle(kids[i]).backgroundColor;
      if (!bg) continue;
      m[bg] = (m[bg] || 0) + 1;
      if (m[bg] > bestN) { bestN = m[bg]; best = bg; }
    }
    return best;
  }

  function applyLook(group) {
    var ref = pickLookRef(group);
    if (!ref) return;
    // 规则内容没变就绝不重写：重写一次就是一次样式重算，反复写等于把「闪」
    // 换个地方复现。React 每秒换新节点、但外观一致时也会走到这里并直接返回。
    var bg = groupBgMode(group);
    var cs = null;
    try { cs = window.getComputedStyle(ref); } catch (e) { return; }
    if (!cs) return;
    var css = 'button[' + RATE_ATTR + '="1"],[' + RATE_ATTR + '="1"]{' +
      'background-color:' + (bg || cs.backgroundColor) + ' !important;' +
      'background-image:' + (cs.backgroundImage || 'none') + ' !important;' +
      'border:' + (cs.border || '0 none transparent') + ' !important;' +
      'border-radius:' + cs.borderRadius + ' !important;' +
      'box-shadow:' + (cs.boxShadow || 'none') + ' !important;' +
      'color:' + cs.color + ' !important;}';
    if (!lookEl || !lookEl.isConnected) {
      lookEl = document.getElementById(LOOK_STYLE_ID);
      if (!lookEl) {
        lookEl = document.createElement('style');
        lookEl.id = LOOK_STYLE_ID;
        (document.head || document.documentElement).appendChild(lookEl);
      }
    }
    if (lookEl.textContent !== css) lookEl.textContent = css;
    lookRef = ref;
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
  // 位置只钉一次：首次算出来就冻结，之后无论网页怎么改写 style，
  // 我们都只写回这同一组数值。之前每次按按钮实时坐标重算，按钮随控制栏
  // 滑动时弹层就跟着挪一次 —— 那就是用户看到的「第二段」。
  var speedPin = null;
  var speedPinAt = 0;
  function watchSpeedMenu(menu) {
    if (speedMenuWatched === menu || !window.MutationObserver) return;
    // 800ms 内换节点当「同一次打开的重新挂载」（Portals 常见）：沿用原位置，
    // 否则会按新按钮坐标再算一遍，屏幕上就是又挪一次。
    if (!speedPin || Date.now() - speedPinAt > 800) speedPin = null;
    if (speedMenuObserver) speedMenuObserver.disconnect();
    if (speedMenuObserver) speedMenuObserver.disconnect();
    speedMenuWatched = menu;
    speedMenuObserver = new MutationObserver(function () { placeSpeedMenu(); });
    speedMenuObserver.observe(menu,
      { attributes: true, attributeFilter: ['style'] });
  }

  // 在一个新增子树里就地判定倍速菜单：全部子按钮都是「1x / 1.5x」这种文本。
  // 只扫新增节点，成本可控，可以在 MutationObserver 里同步调用。
  function speedMenuIn(node) {
    if (!node || node.nodeType !== 1) return null;
    // 廉价预筛：子树里连 button 都没有，肯定不是倍速菜单
    if (!node.querySelector || !node.querySelector('button')) return null;
    var cands = [node];
    var l = node.querySelectorAll ? node.querySelectorAll('div') : [];
    for (var i = 0; i < l.length && i < 300; i++) cands.push(l[i]);
    for (var c = 0; c < cands.length; c++) {
      var kids = cands[c].children;
      if (!kids || kids.length < 2) continue;
      var total = 0, hit = 0;
      for (var j = 0; j < kids.length; j++) {
        if (kids[j].tagName !== 'BUTTON') continue;
        total++;
        if (/^[0-9]+([.][0-9]+)?x$/.test(String(kids[j].textContent || '').trim())) hit++;
      }
      if (total >= 2 && hit === total) return cands[c];
    }
    return null;
  }
  function placeSpeedMenu(node) {
    try { if (iptvPlaying()) return; } catch (eP2) {}
    var menu = node || findSpeedMenu();
    if (!menu) return;
    if (menu.getAttribute(SPEED_MENU_ATTR) !== '1') {
      menu.setAttribute(SPEED_MENU_ATTR, '1');
      menuDiag.speed = snapMenu(menu);
      startMenuTrace(menu);
    }
    watchSpeedMenu(menu);
    var b = document.querySelector('[' + RATE_ATTR + '="1"]') || findRateButton();
    if (!b) return;
    var br = b.getBoundingClientRect();
    if (br.width < 1) return;
    var bottom = (window.innerHeight || 0) - br.top + 10;
    var mw = menu.getBoundingClientRect().width || 136;
    var left = br.right - mw;
    if (left < 8) left = 8;
    if (!speedPin) {
      speedPin = { left: left, bottom: bottom };
      speedPinAt = Date.now();
      // 关键：先藏起来把属性写完再显示。否则网页会先用它自己的位置/
      // 尺寸画一帧，我们再跳到最终值 —— 这一跳就是「第二段」。
      menu.style.setProperty('visibility', 'hidden', 'important');
    }
    forceStyle(menu, {
      'width': '136px',
      'min-width': '0px',
      'left': speedPin.left.toFixed(1) + 'px',
      'bottom': speedPin.bottom.toFixed(1) + 'px'
    });
    menu.style.setProperty('visibility', 'visible', 'important');
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

  // 没有顶栏的页面（播放页）顶部那条是 div 形态，nav/header 选择器命不中，
  // 会和我们的固定栏叠在一起。按几何兜底：贴顶、横向 ≥60%、高 18–120px。
  function hideStrayHeaders() {
    // 直播页的播放层是网页自己的 fixed inset-0 浮层，它的顶栏（频道名 + LIVE +
    // 关闭钮）正落在下面的几何判据里，会被一起藏掉 —— 一藏，用户除了系统返回
    // 键就没有别的出口，而返回键又会直接退回上一页。浮层在场时不动它。
    if (iptvPlaying()) return;
    // 只在「页面没有可用顶栏」时调用（播放页）：那条是任意 div，选择器命不中，
    // 所以在顶部横向取 3 个点，把命中元素及其 3 层祖先里「贴顶 + 宽 ≥60% +
    // 高 18–120px」的都标掉。
    var vw = window.innerWidth || 0;
    if (!vw) return;
    var xs = [Math.round(vw * 0.2), Math.round(vw * 0.5), Math.round(vw * 0.8)];
    var ys = [10, 26];
    var seen = [];
    function consider(e) {
      if (!e || !e.getBoundingClientRect) return;
      if (root && (e === root || (e.contains && e.contains(root)))) return;
      // 直播页的筛选条由 syncIptvFilter 接管；收起后 82px 高正好落进
      // 「贴顶 + 宽 ≥60% + 高 18–120」这条判据，不排除就会被当杂条藏掉。
      if (e.hasAttribute && e.hasAttribute(IPTV_BAR_ATTR)) return;
      for (var k = 0; k < seen.length; k++) if (seen[k] === e) return;
      seen.push(e);
      var r = e.getBoundingClientRect();
      if (r.width < vw * 0.6 || r.height < 18 || r.height > 120) return;
      if (r.top > 120 || r.bottom < 0) return;
      if (!e.hasAttribute(STRAY_ATTR)) e.setAttribute(STRAY_ATTR, '1');
    }
    for (var yi = 0; yi < ys.length; yi++) {
      for (var xi = 0; xi < xs.length; xi++) {
        var stack = document.elementsFromPoint ?
          document.elementsFromPoint(xs[xi], ys[yi]) : [document.elementFromPoint(xs[xi], ys[yi])];
        for (var si = 0; si < stack.length; si++) {
          var el = stack[si], hops = 0;
          while (el && hops < 4) {
            consider(el);
            el = el.parentElement;
            hops++;
          }
        }
      }
    }
  }
  // 行内形态下网页只留 logo、文字被我们藏了，这里补一条站名，保证「logo 后面有字母」
  // 播放页判据：路由 /player 优先（播放器没挂载、加载失败也成立），
  // 有 <video> 兜底（别处也可能直接内嵌播放器）。
  function isPlayerPage() {
    try {
      var p = String(location.pathname || '').toLowerCase();
      if (p.indexOf('/player') >= 0) return true;
      if (/[?&](s|source|id)=/.test(location.search || '') && document.querySelector('video')) return true;
    } catch (e) {}
    return !!document.querySelector('video');
  }

  function rowHasText(row, skip) {
    try {
      var els = row.querySelectorAll('span,b,a,p,div');
      for (var i = 0; i < els.length; i++) {
        var e = els[i];
        if (e === skip || (skip && skip.contains(e)) || e.children.length) continue;
        var t = (e.textContent || '').trim();
        if (!t) continue;
        var r = e.getBoundingClientRect();
        if (r.width > 10 && r.height > 6) return true;
      }
    } catch (err) {}
    return false;
  }

  function ensureBrandText(m) {
    if (!m || m.querySelector('.kvideo-brand-text')) return;
    // 只在播放页补站名：首页网页自己就有站名，再插一条必然重复。
    // 判据用路由 /player，所以「没点播放/加载失败」时也会有字母。
    if (!isPlayerPage()) return;
    var b = document.createElement('span');
    b.className = 'kvideo-brand-text';
    b.textContent = 'KVideo';
    m.insertBefore(b, m.firstChild);
  }

  function dockFixed() {
    // 直播页的播放层本来就是全屏浮层，网页自己的顶栏（LIVE + 频道名 + 关闭钮）
    // 就在最上面。壳再挂一条 56px 固定栏会正好压住那枚关闭钮，所以浮层在场时
    // 不挂固定栏，把顶栏交还网页。
    try {
      if (iptvPlaying()) {
        if (root && root.getAttribute(DOCK_ATTR) === 'fixed') undock(root);
        return;
      }
    } catch (eDock) {}
    // 只有播放页才挂固定栏：设置页/列表页滚到某个位置也会「找不到顶栏」，
    // 一挂就变成一条浮在内容上的菜单。判据走路由 /player（与播放器是否就绪无关）。
    if (!isPlayerPage()) {
      if (root && root.getAttribute(DOCK_ATTR) === 'fixed') undock(root);
      return;
    }
    var m = ensureRoot();
    var rowTxt = m.querySelector('.kvideo-brand-text');
    if (rowTxt && rowTxt.parentNode) rowTxt.parentNode.removeChild(rowTxt);
    // 用页面实际底色决定深浅，而不是 data-kv-light 标记
    try {
      var bg = window.getComputedStyle(document.body).backgroundColor || '';
      var mm = bg.match(/rgba?\(([0-9.]+),\s*([0-9.]+),\s*([0-9.]+)/);
      var lightBg = mm ? ((0.299 * +mm[1] + 0.587 * +mm[2] + 0.114 * +mm[3]) / 255 > 0.5) : false;
      m.style.setProperty('--kv-dock-bg', lightBg ? 'rgba(255,255,255,.95)' : 'rgba(13,15,19,.94)');
      m.style.setProperty('--kv-dock-fg', lightBg ? '#111318' : '#F2F4F8');
    } catch (e) {}
    if (m.parentNode !== document.body) document.body.appendChild(m);
    if (m.getAttribute(DOCK_ATTR) !== 'fixed') {
      m.setAttribute(DOCK_ATTR, 'fixed');
      if (!m.querySelector('.kvideo-float-brand')) {
        var brand = document.createElement('span');
        brand.className = 'kvideo-float-brand';
        brand.innerHTML = LOGO_SVG + '<b>KVideo</b>';
        m.insertBefore(brand, m.firstChild);
      }
    }
    hideStrayHeaders();
    document.documentElement.classList.add(READY_CLS);
    syncAvailability();
    syncThemeState();
    bindDiagLongPress();
    disarmFallback();
  }
  function undock(m) {
    if (!m || m.getAttribute(DOCK_ATTR) !== 'fixed') return;
    m.removeAttribute(DOCK_ATTR);
    var brand = m.querySelector('.kvideo-float-brand');
    if (brand && brand.parentNode) brand.parentNode.removeChild(brand);
  }

  function syncMenu() {
    // 放在所有早退之前：播放页没有顶栏（下面会 return），但浅色标记必须照样到位，
    // 否则从首页进播放页后注入的浅色规则会整体失效。
    applyLightFlag();
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
    // 关键：`bar` 只是「当前可见的顶栏」。页面一滚动它就会滚出视口（top > 200 就
    // 查询不到），以前这里直接 dockFixed() —— 于是滚动中在「行内」和「固定栏」之间
    // 来回搬家，看到的就是整条栏闪一下。
    // 现在只有**页面里根本没有 nav/header**（播放页）才挂固定栏；
    // 有顶栏、只是暂时滚出视口时保持原状，不重建。
    if (!bar) {
      // 判断「这个页面到底有没有顶栏」：有 nav/header 且尺寸正常（只是滚出视口）
      // 就当有，保持原状；一个像样的都没有（播放页）才挂固定栏。
      var usable = false;
      for (var ui = 0; ui < navs.length; ui++) {
        var ur = navs[ui].getBoundingClientRect();
        if (ur.width > 100 && ur.height > 20) { usable = true; break; }
      }
      if (!usable) { dockFixed(); return; }
      // 要显示、但宿主行已经滚出视口：挂固定栏，让栏当场出现在屏幕顶部。
      // 否则状态变成「已显示」，眼睛看到的是「没回来」。
      // 要显示、但宿主行已经滚出视口：直接把页面滚回顶部，让栏真的出现。
      // 不换宿主（换宿主会改变栏的形态和位置，看起来就是「闪一下」）。
      if (!hidden) {
        try { window.scrollTo(0, 0); } catch (e) {}
        armFallback();
        return;
      }
      if (root && root.getAttribute(DOCK_ATTR) === 'fixed') {
        var sm = window.scrollY || document.documentElement.scrollTop || 0;
        if (sm > 8) return;                     // 滚动中保持固定栏，不搬回行内
      }
      armFallback();
      return;
    }
    var row = pickRow(bar);
    var m = ensureRoot();
    undock(m);
    if (m.parentNode !== row) row.appendChild(m);
    if (row.setAttribute && !row.hasAttribute(TOPBAR_ATTR)) row.setAttribute(TOPBAR_ATTR, '1');
    hideNativeControls(row, m);
    hideBrandText(row, m);
    ensureBrandText(m);
    document.documentElement.classList.add(READY_CLS);
    syncAvailability();
    syncThemeState();
    bindDiagLongPress();
    disarmFallback();
  }

  // ---- 直播页（/iptv）筛选条折叠 ------------------------------------------
  // 判据全走结构与计算样式，不写死上游 class：
  //   placeholder 含「搜索频道」的输入框 → 向上找最近 position:sticky 的祖先 = 筛选条
  //   筛选条直接子节点里含 ≥3 个 button 的 = chip 组
  // 只打标 + 加一枚按钮，绝不搬动网页节点（React 持有它们，挪走会抛 NotFoundError）。
  var iptvOpen = false;
  var IPTV_PH_HINT = '搜索频道';
  function kvSetA(el, k, v) { if (el.getAttribute(k) !== v) el.setAttribute(k, v); }

  function iptvBar() {
    if (String(location.pathname || '').toLowerCase().indexOf('/iptv') < 0) return null;
    var ins = document.querySelectorAll('input[placeholder]');
    for (var i = 0; i < ins.length; i++) {
      if (String(ins[i].getAttribute('placeholder') || '').indexOf(IPTV_PH_HINT) < 0) continue;
      var el = ins[i], hops = 0;
      while (el && el !== document.body && hops < 8) {
        var cs = null;
        try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
        if (cs && cs.position === 'sticky') return el;
        el = el.parentElement; hops++;
      }
    }
    return null;
  }

  function iptvTag(bar) {
    kvSetA(bar, IPTV_BAR_ATTR, '1');
    if (bar.hasAttribute('data-kvideo-stray')) bar.removeAttribute('data-kvideo-stray');
    var kids = bar.children, n = 0;
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      if (!el || el.nodeType !== 1 || !el.querySelectorAll) continue;
      if (el.hasAttribute(IPTV_BTN_ATTR)) continue;
      if (el.querySelectorAll('button').length < 3) continue;
      kvSetA(el, IPTV_CHIPS_ATTR, '1');
      n++;
    }
    return n;
  }

  function iptvApply(bar) {
    var b = bar.querySelector('[' + IPTV_BTN_ATTR + ']');
    var v = iptvOpen ? '1' : '0';
    kvSetA(bar, IPTV_OPEN_ATTR, v);
    if (b) {
      kvSetA(b, IPTV_OPEN_ATTR, v);
      var say = iptvOpen ? '收起 \u25b4' : '筛选 \u25be';
      if (b.textContent !== say) b.textContent = say;
    }
  }

  function iptvSyncBar(bar) {
    var b = bar.querySelector('[' + IPTV_BTN_ATTR + ']');
    if (!b) {
      b = document.createElement('button');
      b.type = 'button';
      b.setAttribute(IPTV_BTN_ATTR, '1');
      b.setAttribute('aria-label', '筛选');
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        ev.stopPropagation();
        iptvOpen = !iptvOpen;
        iptvApply(bar);
      }, false);
      bar.appendChild(b);
    }
    iptvApply(bar);
  }

  function iptvDrop() {
    iptvOpen = false;
    var bars = document.querySelectorAll('[' + IPTV_BAR_ATTR + ']');
    for (var i = 0; i < bars.length; i++) {
      var bar = bars[i];
      var b = bar.querySelector('[' + IPTV_BTN_ATTR + ']');
      if (b && b.parentNode) b.parentNode.removeChild(b);
      var ch = bar.querySelectorAll('[' + IPTV_CHIPS_ATTR + ']');
      for (var k = 0; k < ch.length; k++) ch[k].removeAttribute(IPTV_CHIPS_ATTR);
      bar.removeAttribute(IPTV_BAR_ATTR);
      bar.removeAttribute(IPTV_OPEN_ATTR);
    }
  }

  // 底部渐隐的终点色按页面实际底色算（浅色主题下不能是深灰）
  function iptvFade(bar) {
    var c = '';
    try {
      var bg = window.getComputedStyle(document.body).backgroundColor || '';
      var mm = bg.match(/rgba?\(([0-9.]+),\s*([0-9.]+),\s*([0-9.]+)/);
      if (mm) {
        c = 'rgba(' + Math.round(+mm[1]) + ',' + Math.round(+mm[2]) + ',' +
            Math.round(+mm[3]) + ',.96)';
      }
    } catch (e) {}
    if (!c) c = 'rgba(12,14,19,.96)';
    if (bar.style.getPropertyValue('--kv-iptv-fade') !== c) {
      bar.style.setProperty('--kv-iptv-fade', c);
    }
  }

  function syncIptvFilter() {
    var bar = iptvBar();
    if (!bar) { iptvDrop(); return; }
    if (!iptvTag(bar)) return;   // chip 组还没渲染出来，下一拍再说
    iptvSyncBar(bar);
    iptvFade(bar);
  }

  // ---- 直播页播放层的返回键（/iptv）--------------------------------------
  // 网页的 IPTVPlayer 是一个 fixed inset-0 的 React 浮层，开播/关播只改 state，
  // 不产生历史条目。于是系统返回键会直接把 /iptv 这条历史退掉，回到首页 ——
  // 用户期望的是「先回到直播列表」。做法：浮层一出现就压一条同 URL 的历史，
  // popstate 时先关浮层（网页自己监听 window 的 Escape），历史就停在 /iptv。
  var iptvPlayerPushed = false;

  // 「直播页正在播」的判据：/iptv 路由上出现了一个有实际尺寸的 <video>。
  // 不用几何去认那个 fixed 浮层 —— 真机上浮层结构和本地复刻不一致，按几何
  // 认会漏判（漏判的后果是壳在播放页又挂了一条固定栏，正好压住关闭钮）。
  function iptvPlaying() {
    if (String(location.pathname || '').toLowerCase().indexOf('/iptv') < 0) return null;
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (r.width > 40 && r.height > 20) return vids[i];
    }
    return null;
  }
  function iptvPlayerLayer() { return iptvPlaying(); }

  // 网页自己的关闭钮是否真的看得见（不是 opacity:0 的淡出条）
  function iptvWebClose() {
    var btns = document.querySelectorAll('button'), vw = window.innerWidth || 0;
    var best = null, bestR = -1;
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i], r = b.getBoundingClientRect();
      if (String(b.textContent || '').trim()) continue;
      if (!b.querySelector('svg')) continue;
      if (r.top > 160 || r.width < 20 || r.width > 80 || r.height > 80) continue;
      var cs = null;
      try { cs = window.getComputedStyle(b); } catch (e) { cs = null; }
      if (!cs || +cs.opacity < 0.15 || cs.visibility === 'hidden' || cs.display === 'none') continue;
      var pe = cs.pointerEvents;
      if (pe === 'none') continue;
      if (r.right > bestR) { bestR = r.right; best = b; }
    }
    return (best && bestR > vw * 0.6) ? best : null;
  }

  // 网页控制层是否显示中。网页自己给上下两条控制条打了 data-controls，
  // 淡出是 opacity 变化（class 切换会触发 MutationObserver → schedule）。
  function iptvControlsVisible() {
    var els = document.querySelectorAll('[data-controls]');
    for (var i = 0; i < els.length; i++) {
      var cs = null;
      try { cs = window.getComputedStyle(els[i]); } catch (e) { cs = null; }
      if (!cs) continue;
      if (+cs.opacity > 0.15 && cs.visibility !== 'hidden' && cs.display !== 'none') return true;
    }
    return false;
  }

  // 没有可点的关闭钮时给一枚兜底钮，保证任何时候都能退出播放层。
  // 它跟随网页控制条一起显隐 —— 控制条淡出时不留一枚孤立的白叉。
  function iptvEnsureClose() {
    var mine = document.querySelector('[' + IPTV_CLOSE_ATTR + ']');
    function drop() {
      if (mine && mine.parentNode) mine.parentNode.removeChild(mine);
    }
    if (iptvWebClose()) { drop(); return; }
    if (!iptvControlsVisible()) { drop(); return; }
    if (mine) return;
    var b = document.createElement('button');
    b.type = 'button';
    b.setAttribute(IPTV_CLOSE_ATTR, '1');
    b.setAttribute('aria-label', '关闭播放');
    b.textContent = '\u2715';
    b.addEventListener('click', function (ev) {
      ev.preventDefault();
      ev.stopPropagation();
      iptvClosePlayer();
    });
    (document.body || document.documentElement).appendChild(b);
  }

  function iptvClosePlayer() {
    function esc() {
      try {
        var ev = new KeyboardEvent('keydown',
          { key: 'Escape', code: 'Escape', bubbles: true, cancelable: true });
        window.dispatchEvent(ev);
      } catch (e) {}
    }
    esc();
    setTimeout(function () {
      if (!iptvPlaying()) return;
      // 兜底：点网页那枚关闭钮（无文字、含 svg、贴右上）
      var b = iptvWebClose();
      if (b) { try { b.click(); } catch (e) {} }
    }, 240);
  }

  // ---- 直播页迷你播放器 ----------------------------------------------------
  // 网页的播放层是 fixed inset-0 的浮层：一开播整页被盖住，换台要先退出。
  // 这里把浮层本身收成顶部一条（32vh），下面的频道列表露出来，点一下就是换台
  // （点的是网页自己的列表，直接走它的 setActiveChannel）。右上角保留两枚钮：
  // 收起成迷你 / 放大回全屏。
  var kvMiniOn = true;
  var kvMiniScrolled = false;   // 本轮播放是否已把列表滚到迷你条下方
  var kvMiniTop = 8;
  function kvMiniPlace(host) {
    // 迷你条挂在网页自己的筛选条下面：筛选条是 sticky 的，一直可见，
    // 所以迷你条跟着它的底边走，能还原「搜索框在上、播放器在中、列表在下」。
    var top = 8;
    try {
      var bar = document.querySelector('[' + IPTV_BAR_ATTR + ']');
      if (bar) {
        var br = bar.getBoundingClientRect();
        var vh = window.innerHeight || 0;
        if (br.height > 20 && br.bottom > 0 && br.bottom < vh * 0.55) top = br.bottom + 8;
      }
    } catch (e) {}
    if (Math.abs(top - kvMiniTop) > 1) {
      kvMiniTop = top;
      try { document.documentElement.style.setProperty('--kv-mini-top', top + 'px'); } catch (e2) {}
    } else if (!document.documentElement.style.getPropertyValue('--kv-mini-top')) {
      try { document.documentElement.style.setProperty('--kv-mini-top', top + 'px'); } catch (e3) {}
    }
    return top;
  }
  function kvScrollListUnderMini(host) {
    if (kvMiniScrolled || !host) return;
    try {
      var g = document.querySelector('div[class*="grid-cols-2"]');
      if (!g) {
        var hostBottom = host.getBoundingClientRect().bottom;
        var all = document.querySelectorAll('button');
        for (var i = 0; i < all.length; i++) {
          var b = all[i];
          if (host.contains(b)) continue;
          var br2 = b.getBoundingClientRect();
          if (br2.width < 80 || br2.height < 32) continue;
          if (br2.top < hostBottom) continue;
          g = b.parentElement; break;
        }
      }
      if (!g) return;
      var sy = window.scrollY || document.documentElement.scrollTop || 0;
      var gridTop = g.getBoundingClientRect().top + sy;
      var bar = document.querySelector('[' + IPTV_BAR_ATTR + ']');
      var barAbsTop = bar ? (bar.getBoundingClientRect().top + sy) : 0;
      var hr = host.getBoundingClientRect();
      var target = Math.max(bar ? barAbsTop - 8 : 0, gridTop - (hr.height + kvMiniTop + 12));
      if (Math.abs((window.scrollY || 0) - target) > 4) window.scrollTo(0, Math.max(0, target));
      kvMiniScrolled = true;
    } catch (e) {}
  }
  function kvMiniHost() {
    var v = iptvPlaying();
    if (!v) return null;
    var vw = window.innerWidth || 0, el = v, hops = 0;
    while (el && el !== document.body && hops < 6) {
      var cs = null;
      try { cs = window.getComputedStyle(el); } catch (e) { cs = null; }
      if (cs && cs.position === 'fixed') {
        var r = el.getBoundingClientRect();
        if (r.width >= vw * 0.9) return el;
      }
      el = el.parentElement; hops++;
    }
    return null;
  }
  function kvMiniButtons() {
    var box = document.querySelector('[' + IPTV_MINI_BTNS_ATTR + ']');
    if (box) return box;
    box = document.createElement('div');
    box.setAttribute(IPTV_MINI_BTNS_ATTR, '1');
    var full = document.createElement('button');
    full.type = 'button';
    full.setAttribute('aria-label', '全屏');
    full.textContent = '⤢';
    full.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      kvMiniOn = false;
      syncIptvMini();
    });
    var close = document.createElement('button');
    close.type = 'button';
    close.setAttribute('aria-label', '关闭');
    close.textContent = '✕';
    close.addEventListener('click', function (ev) {
      ev.preventDefault(); ev.stopPropagation();
      iptvClosePlayer();
    });
    box.appendChild(full);
    box.appendChild(close);
    (document.body || document.documentElement).appendChild(box);
    return box;
  }
  function kvMiniDrop() {
    var hosts = document.querySelectorAll('[' + IPTV_MINI_ATTR + ']');
    for (var i = 0; i < hosts.length; i++) hosts[i].removeAttribute(IPTV_MINI_ATTR);
    var box = document.querySelector('[' + IPTV_MINI_BTNS_ATTR + ']');
    if (box && box.parentNode) box.parentNode.removeChild(box);
  }
  function kvMiniDropClose() {
    var mine = document.querySelector('[' + IPTV_CLOSE_ATTR + ']');
    if (mine && mine.parentNode) mine.parentNode.removeChild(mine);
  }
  function kvFullscreenElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }
  function syncIptvMini() {
    if (!iptvPlaying()) { kvMiniOn = true; kvMiniScrolled = false; kvMiniDrop(); kvDropHeldClick(); return; }
    // 进（网页或原生）全屏时必须把迷你态的尺寸规则摘掉，否则 32vh/圆角那套
    // !important 会把全屏画面又按回一张小卡片 —— 表现就是「横屏按了没反应」。
    if (kvFullscreenElement()) {
      kvMiniDrop();
      return;
    }
    var host = kvMiniHost();
    if (!host) return;
    if (kvMiniOn) {
      if (host.getAttribute(IPTV_MINI_ATTR) !== '1') host.setAttribute(IPTV_MINI_ATTR, '1');
      kvMiniPlace(host);
      kvMiniButtons();
      // 等一帧让 32vh 的高度落定，再把频道列表滚到迷你条正下方
      setTimeout(function () { kvScrollListUnderMini(host); }, 120);
    } else {
      if (host.getAttribute(IPTV_MINI_ATTR) === '1') host.removeAttribute(IPTV_MINI_ATTR);
      var box = document.querySelector('[' + IPTV_MINI_BTNS_ATTR + ']');
      if (box && box.parentNode) box.parentNode.removeChild(box);
    }
  }

  function syncIptvPlayer() {
    try { kvBindVideoClicks(); } catch (eB) {}
    // 已经离开 /iptv 就不再做补偿性 back()，否则会被退回直播页
    if (String(location.pathname || '').toLowerCase().indexOf('/iptv') < 0) {
      iptvPlayerPushed = false;
      return;
    }
    var layer = iptvPlayerLayer();
    if (layer) {
      if (!iptvPlayerPushed) {
        try {
          history.pushState({ kvIptvPlayer: 1 }, '', location.href);
          iptvPlayerPushed = true;
        } catch (e) { iptvPlayerPushed = false; }
      }
      if (kvMiniOn) kvMiniDropClose();
      else iptvEnsureClose();
      return;
    }
    if (iptvPlayerPushed) {
      // 用户自己点了关闭钮：把我们压进去的那条吃掉，否则下次返回会是一次
      // 「同 URL 空退」。popstate 时 iptvPlayerPushed 已是 false，不会再触发。
      iptvPlayerPushed = false;
      try { history.back(); } catch (e) {}
    }
    var mineClose = document.querySelector('[' + IPTV_CLOSE_ATTR + ']');
    if (mineClose && mineClose.parentNode) mineClose.parentNode.removeChild(mineClose);
  }

  window.addEventListener('popstate', function () {
    if (!iptvPlayerPushed) return;
    iptvPlayerPushed = false;
    if (iptvPlaying()) iptvClosePlayer();
  });

  // ---- 双击视频 → 全屏切换 ----------------------------------------------
  // 竖屏看直播时视频只占中间一条，上下大片黑，出口只有右下角那枚小全屏钮。
  // 直接调 requestFullscreen(播放容器)，让 WebChromeClient.onShowCustomView
  // 接管；网页自己监听 fullscreenchange，图标状态会自动跟上。
  // 不依赖找按钮：IPTVPlayer 那枚全屏钮没有 aria-label，靠文字/几何认不可靠。
  var kvDblTs = 0, kvDblX = 0, kvDblY = 0;
  function kvPlayingVideo() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (r.width > 40 && r.height > 20) return vids[i];
    }
    return null;
  }
  function kvFullscreenTarget(v) {
    var vw = window.innerWidth || 0, vh = window.innerHeight || 0;
    var t = v, hops = 0;
    while (t && t !== document.body && hops < 5) {
      var r = t.getBoundingClientRect();
      if (r.width >= vw * 0.9 && r.height >= vh * 0.9) return t;
      t = t.parentElement; hops++;
    }
    return v.parentElement || v;
  }
  function kvToggleFullscreen() {
    try {
      if (document.fullscreenElement) {
        if (document.exitFullscreen) document.exitFullscreen();
        return;
      }
      var v = kvPlayingVideo();
      if (!v) return;
      var target = kvFullscreenTarget(v);
      if (target.requestFullscreen) target.requestFullscreen();
      else if (target.webkitRequestFullscreen) target.webkitRequestFullscreen();
      else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
    } catch (e) {}
  }
  function kvHitVideo(x, y) {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true;
    }
    return false;
  }
  // 自己数双击：WebView 对合成的 dblclick 不一定可靠，touchstart 最稳。
  var kvDblGuardUntil = 0;     // 双击后的极短时间内吞掉 click（网页靠 click 切播放/暂停）
  var kvLastDblAction = 0;     // 上一次由 touch 触发的全屏切换时刻，用来给 dblclick 去重
  var kvTapVideo = null, kvTapPaused = false;
  function kvRestorePlay(v, wantPaused) {
    if (!v) return;
    try {
      if (wantPaused && !v.paused) v.pause();
      else if (!wantPaused && v.paused) v.play();
    } catch (e) {}
  }
  // 单击扣住 260ms：这 260ms 内来了第二下，就整对吞掉（双击完全不触发播放键，
  // 也就没有「闪一下暂停」）；没来，才把这一下补发出去，播放/暂停会慢 260ms。
  // 监听挂在 <video> 自己的捕获阶段 —— 点击必须在这里就停住，不能让它冒到
  // 网页的播放层根节点（那里的 onClick 就是播放/暂停）。
  var kvClickHold = null, kvReplaying = false, kvClickWindow = 260;
  function kvOnVideoClick(ev) {
    if (kvReplaying) return;
    ev.preventDefault();
    ev.stopPropagation();
    // 刚处理完双击：随后那一批 click（第二下、以及浏览器补发的）一律吞掉，
    // 不能再起一个新的「扣住再补发」，否则双击照样会切一次播放。
    if (Date.now() < kvDblGuardUntil) return;
    if (kvClickHold) return;
    var target = ev.target, cx = ev.clientX, cy = ev.clientY;
    var hold = { target: target, x: cx, y: cy };
    hold.timer = setTimeout(function () {
      if (kvClickHold !== hold) return;
      kvClickHold = null;
      kvReplaying = true;
      try {
        target.dispatchEvent(new MouseEvent('click', {
          bubbles: true, cancelable: true, view: window,
          clientX: hold.x, clientY: hold.y, detail: 1
        }));
      } catch (e) {}
      kvReplaying = false;
    }, kvClickWindow);
    kvClickHold = hold;
  }
  function kvBindVideoClicks() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.getAttribute('data-kv-click') === '1') continue;
      v.setAttribute('data-kv-click', '1');
      v.addEventListener('click', kvOnVideoClick, true);
    }
  }
  function kvDropHeldClick() {
    if (!kvClickHold) return;
    clearTimeout(kvClickHold.timer);
    kvClickHold = null;
  }
  document.addEventListener('touchstart', function (ev) {
    if (!ev.touches || ev.touches.length !== 1) { kvDblTs = 0; return; }
    var t = ev.touches[0], now = Date.now();
    if (!kvHitVideo(t.clientX, t.clientY)) { kvDblTs = 0; return; }
    if (now - kvDblTs < 320 &&
        Math.abs(t.clientX - kvDblX) < 40 && Math.abs(t.clientY - kvDblY) < 40) {
      kvDblTs = 0;
      // 第一下点击已经让网页切过一次播放/暂停，第二下的 click 吞掉，
      // 600ms 后把状态拨回双击之前 —— 否则双击进全屏会顺带暂停。
      kvDblGuardUntil = now + 450;
      kvLastDblAction = now;
      // 第一下的 click 还扣在手里没发出去 —— 直接丢掉，播放/暂停一次都不会触发
      kvDropHeldClick();
      var v = kvTapVideo, wantPaused = kvTapPaused;
      kvToggleFullscreen();
      // 兜底：全屏过渡个别情况下会让网页自己暂停，450ms 后对一次状态
      setTimeout(function () { kvRestorePlay(v, wantPaused); }, 450);
    } else {
      kvDblTs = now; kvDblX = t.clientX; kvDblY = t.clientY;
      kvTapVideo = kvPlayingVideo();
      kvTapPaused = kvTapVideo ? !!kvTapVideo.paused : false;
    }
  }, { capture: true, passive: true });
  document.addEventListener('dblclick', function (ev) {
    var v = ev.target && ev.target.closest ? ev.target.closest('video') : null;
    if (!v) return;
    ev.preventDefault();
    // touch 那条已经切过了就不再切，否则会「进去又出来」
    if (Date.now() - kvLastDblAction < 800) return;
    kvLastDblAction = Date.now();
    kvToggleFullscreen();
  }, true);

  window.addEventListener('scroll', function () {
    if (!iptvPlaying()) return;
    var host = kvMiniHost();
    if (host && host.getAttribute(IPTV_MINI_ATTR) === '1') kvMiniPlace(host);
  }, { passive: true });
  document.addEventListener('fullscreenchange', function () {
    try { syncIptvMini(); } catch (e) {}
  });
  document.addEventListener('webkitfullscreenchange', function () {
    try { syncIptvMini(); } catch (e) {}
  });

  var lastRun = 0;
  var tail = null;
  function schedule() {
    var now = Date.now();
    if (now - lastRun >= 120) { lastRun = now; syncIptvFilter(); syncIptvPlayer(); syncIptvMini(); mark(); syncMenu(); }
    if (tail) clearTimeout(tail);
    tail = setTimeout(function () {
      tail = null; lastRun = Date.now(); syncIptvFilter(); syncIptvPlayer(); syncIptvMini(); mark(); syncMenu();
    }, 200);
  }

  var hidden = false;
  // 长按顶栏齿轮（600ms）→ 把整份诊断 JSON 交给壳层复制到剪贴板。
  // 真机上拿不到 console，这是唯一可靠的取证通道。
  var diagFired = false;
  function bindDiagLongPress() {
    if (!root) return;
    var g = root.querySelector('[data-kvideo-act="more"]');
    if (!g || g.getAttribute('data-kvideo-diag') === '1') return;
    g.setAttribute('data-kvideo-diag', '1');
    var timer = null;
    function cancel() {
      if (timer) { clearTimeout(timer); timer = null; }
    }
    g.addEventListener('touchstart', function () {
      cancel();
      timer = setTimeout(function () {
        timer = null;
        diagFired = true;
        try {
          if (window.KVideoAndroid && window.KVideoAndroid.copyDiagnostics) {
            window.KVideoAndroid.copyDiagnostics(
              JSON.stringify(window.KVideoChrome.debug()));
          }
        } catch (e) {}
      }, 600);
    }, { passive: true });
    g.addEventListener('touchmove', cancel, { passive: true });
    g.addEventListener('touchend', cancel);
    g.addEventListener('touchcancel', cancel);
    g.addEventListener('contextmenu', function (ev) { ev.preventDefault(); });
  }
  // 探针：记录每次收起/展开的现场（何时、滚到哪、菜单当时的计算样式）
  function kvSnap(src, value) {
    try {
      var log = window.__kvHideLog || (window.__kvHideLog = []);
      var m = root, row = m && m.parentNode;
      var cs = m && window.getComputedStyle ? window.getComputedStyle(m) : null;
      log.push({
        t: Math.round((window.performance && performance.now) ? performance.now() : 0),
        src: src, v: value ? 1 : 0,
        y: Math.round(window.scrollY || document.documentElement.scrollTop || 0),
        op: cs ? cs.opacity : '',
        tr: cs ? String(cs.transform || '').slice(0, 20) : '',
        rowH: (row && row.getBoundingClientRect) ? Math.round(row.getBoundingClientRect().height) : -1,
        rowTag: row ? String(row.tagName || '').slice(0, 8) : ''
      });
      while (log.length > 24) log.shift();
    } catch (e) {}
  }
  window.KVideoChrome = {
    setHidden: function (v) {
      kvSnap('set', v);
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
      var _m = root, _cs = _m && window.getComputedStyle ? window.getComputedStyle(_m) : null;
      var _host = _m && _m.parentNode;
      var _bt = document.querySelector('.kvideo-float-brand b');
      var _btR = _bt ? _bt.getBoundingClientRect() : null;
      return {
        host: _host ? (String(_host.tagName || '?') + '/' + (_m.getAttribute(DOCK_ATTR) || 'row')) : 'none',
        hostRect: _host && _host.getBoundingClientRect ? [
          Math.round(_host.getBoundingClientRect().top),
          Math.round(_host.getBoundingClientRect().height)] : null,
        brandTxt: _bt ? (_bt.textContent + '@' + Math.round(_btR.width)) : 'none',
        hideLog: (window.__kvHideLog || []).slice(-8),
        menuSoft: _cs ? { op: _cs.opacity, tr: String(_cs.transform || '').slice(0, 20), vis: _cs.visibility } : null,
        rowH: (_m && _m.parentNode && _m.parentNode.getBoundingClientRect) ?
          Math.round(_m.parentNode.getBoundingClientRect().height) : -1,
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
        // —— 手势诊断（长按齿轮复制这份）——
        gesture: gestureDiag,
        menus: menuDiag,
        panels: (function () { try { return panelOpen() ? 1 : 0; } catch (e) { return -1; } })(),
        panelSeen: panelSeenCount,
        panelAgoMs: panelSeenAt ? (Date.now() - panelSeenAt) : -1,
        lastReject: gestureDiag.reject,
        lastHitBlock: gestureDiag.hitBlock,
        menuTrace: menuTrace,
        gestureEnv: (function () {
          var v = document.querySelector('video');
          var r = v ? v.getBoundingClientRect() : null;
          var w = window.innerWidth || 0, h = window.innerHeight || 0;
          return {
            fullscreenApi: !!(document.fullscreenElement || document.webkitFullscreenElement),
            isFullscreenPlayback: isFullscreenPlayback(),
            vw: w, vh: h,
            videoRect: r ? [Math.round(r.width), Math.round(r.height), Math.round(r.left), Math.round(r.top)] : null,
            videoRatio: r && w > 0 && h > 0 ?
              (Math.round(r.width / w * 100) + '%x' + Math.round(r.height / h * 100) + '%') : null,
            paused: v ? v.paused : null,
            duration: v ? String(v.duration) : null,
            barSel: (function () {
              var b = findProgressBar();
              return b ? (b.className || b.tagName) : 'none';
            })(),
            hudCount: document.querySelectorAll('[data-kvideo-hud]').length,
            videoCount: document.querySelectorAll('video').length,
            videoTail: (function () {
              var v = document.querySelector('video');
              return v ? String(v.currentSrc || v.src || '').slice(-42) : 'none';
            })(),
            readyState: (function () {
              var v = document.querySelector('video');
              return v ? v.readyState : -1;
            })(),
            tracks: (function () {
              var l = document.querySelectorAll('.slider-track'), o = [];
              for (var i = 0; i < l.length; i++) {
                var r = l[i].getBoundingClientRect();
                o.push(Math.round(r.width) + 'x' + Math.round(r.height) +
                  '@' + Math.round(r.left) + ',' + Math.round(r.top));
              }
              return o.join(' | ');
            })(),
            boxRect: (function () {
              var b = playerBox();
              if (!b) return null;
              var r = b.getBoundingClientRect();
              return [Math.round(r.width), Math.round(r.height),
                Math.round(r.left), Math.round(r.top)];
            })(),
            hudDisplay: (function () {
              var x = document.getElementById('kvideo-gesture-hud');
              return x ? window.getComputedStyle(x).display : 'absent';
            })()
          };
        })(),
        videoDuration: (function () {
          var v = document.querySelector('video');
          return v && isFinite(v.duration) ? Math.round(v.duration) : -1;
        })(),
        moreButton: document.querySelectorAll('[' + MORE_ATTR + '="1"]').length,
        bars: document.querySelectorAll('.player-controls-right').length,
        rateMusic: document.querySelectorAll('button[aria-label="播放速度"]').length,
        wrappers: document.querySelectorAll('[data-kvideo-rate-wrap="1"],[data-kvideo-more-wrap="1"]').length,
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
  try { syncIptvFilter(); } catch (eIptv) {}
  try { syncIptvPlayer(); } catch (eIptvP) {}
  try { syncIptvMini(); } catch (eIptvM) {}
  // 播放器由 React 挂载/重渲染：监听 class / aria-label / style 三种变化
  // （自己的写入靠 forceStyle「值相同不写」+ fadeRunning 闸门收尾，不会自触发），
  // 再补一个 1s 低频全量扫描，覆盖注入早于 hydrate、Portal 挂载等漏掉的情况。
  //
  // 显隐不能只靠变更通知：播放器也可能用 CSS 过渡/动画来淡出控制条，
  // 那种情况没有属性变更可听。所以额外挂 transitionrun / animationstart，
  // 一有「动」的迹象就起逐帧跟随。
  var lastVisSync = 0;
  function syncRateVisibilityNow() {
    var anchor = controlsBar();
    if (!anchor) return;
    var wrap = document.querySelector('[' + RATE_WRAP_ATTR + '="1"]');
    if (!wrap) return;
    syncRateVisibility(anchor, wrap,
      document.querySelector('[' + RATE_ATTR + '="1"]'));
  }
  new MutationObserver(function (list) {
    // 弹层是新建出来的节点，必须在这一帧画出去之前摆好。schedule() 有 120ms
    // 节流 + 200ms 兜底定时器，等它跑的时候网页那版位置早被画出来了 ——
    // 那就是「出现一次、又挪一次」的第二次。这里对新增子树同步处理。
    var moreHit = false;
    for (var i = 0; i < list.length; i++) {
      var an = list[i].addedNodes;
      if (!an || !an.length) continue;
      for (var k = 0; k < an.length; k++) {
        var nd = an[k];
        if (!nd || nd.nodeType !== 1) continue;
        var sm = speedMenuIn(nd);
        if (sm) { try { placeSpeedMenu(sm); } catch (e) {} }
        if (String(nd.textContent || '').indexOf(OVERLAY_TEXT_HINT) >= 0) moreHit = true;
      }
    }
    if (moreHit) { try { markMoreMenu(); } catch (e) {} }
    schedule();
    // 显隐先跟：控制条改透明度可能是「换 class」，也可能是直接写内联
    // style.opacity。只监听 class 的话，内联那种写法要等到 1s 扫描才同步，
    // 1x 就总是比别人晚一拍——这正是「延迟 1 秒」的来源。
    // 自己写 style 也会触发这里，所以必须靠 fadeRunning 去重、
    // 靠 forceStyle「值相同不写」收尾，否则会自我循环。
    startFade();
    var now = Date.now();
    if (now - lastVisSync < 60) return;
    lastVisSync = now;
    syncRateVisibilityNow();
  }).observe(
    document.documentElement,
    { childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'aria-label', 'style'] });
  // CSS 过渡 / 动画驱动的淡出不会产生属性变更，靠事件补上这一路。
  hookMotion();
  setInterval(function () {
    if (document.visibilityState === 'visible') {
      var pnlNow = null;
      try { pnlNow = panelOpen(); } catch (eP) { pnlNow = null; }
      if (pnlNow) { panelSeenCount++; panelSeenAt = Date.now(); }
      pushPanelFlag(!!pnlNow);
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

        // JVM class 常量池单个 UTF-8 项上限 65535 字节。这段脚本含大量中文
        // 注释，1.1.29 时整串已达 65900 字节，做成单个字面量会编译报
        // "UTF8 string too large"。拆成两个字面量，用 getter 在运行时拼接：
        // B 不是 const，编译器不会把两段折叠回一个大常量池项。
        private val CHROME_INJECT_JS: String
            get() = CHROME_INJECT_JS_A + CHROME_INJECT_JS_B
    }

    private lateinit var webView: WebView
    private lateinit var setupContainer: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var openButton: Button
    private lateinit var saveButton: Button
    private lateinit var loadProgress: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var errorDetail: TextView
    private lateinit var retryButton: Button
    private lateinit var changeUrlButton: Button
    private lateinit var copyUrlButton: Button
    private lateinit var openBrowserButton: Button
    private lateinit var clearUrlButton: ImageButton
    private lateinit var prefs: android.content.SharedPreferences
    private var currentLoadUrl: String = ""
    private var pageLoadFailed = false
    private var customView: View? = null
    private var customViewCallback: CustomViewCallback? = null
    private var wasSetupVisibleBeforeFullscreen = false
    private var viewsReady = false
    private var chromeHiddenByGesture = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeThresholdPx = 0f
    // 一次手势只判定一次：否则同一次滑动里每个 MOVE 都会重复触发收起/展开，
    // 来回抖就是看到的「顶栏闪一下」。
    private var swipeHandled = false
    // 按住的时长：长按之后手指再滑，多半是在选字/调出长按菜单，不该收起顶栏
    private var swipeDownAt = 0L
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
    /**
     * 网页上报的实际亮暗：true = 网页是浅色底，null = 还没收到（按系统深浅色兜底）。
     * 系统栏（状态栏/导航栏）的颜色与图标明暗跟它走；原生覆盖页盖上来时改按系统深浅色，
     * 回到网页再切回来 —— 否则深色网页配浅色设置页，状态栏会对着其中一边黑到底。
     */
    private var webLight: Boolean? = null
    /** 网页里有浮层面板（播放设置 / 倍速菜单）开着：此时原生全屏手势必须让开。 */
    private var webPanelOpen = false
    /** 最近一次收到的右侧安全边距（CSS px），注入脚本首帧要用它。 */
    private var lastSafeRightCss = -1
    /** 手势百分比最近一次到达的边界值（0 / 100），用于只在刚碰到边界时震一下。 */
    private var lastGestureBoundary = -1
    /** 播放状态下的触摸次数（诊断用）：JS 计到的 starts 为 0 而这里有数，说明触摸没进网页。 */
    private var nativeTouchCount = 0
    private val lockHintHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideLockHint = Runnable {
        lockHint.visibility = View.GONE
        lockHint.alpha = 1f
        lockHint.scaleX = 1f
        lockHint.scaleY = 1f
    }
    private val lockIdleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideLockToggle = Runnable { lockToggle.visibility = View.GONE }
    private val lockTogglePressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearLockTogglePress = Runnable { lockToggle.isPressed = false }

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
        setupHelpToggle()
        openButton = findViewById(R.id.open_button)
        saveButton = findViewById(R.id.save_button)
        loadProgress = findViewById(R.id.load_progress)
        errorContainer = findViewById(R.id.error_container)
        errorDetail = findViewById(R.id.error_detail)
        retryButton = findViewById(R.id.retry_button)
        changeUrlButton = findViewById(R.id.change_url_button)
        copyUrlButton = findViewById(R.id.copy_url_button)
        openBrowserButton = findViewById(R.id.open_browser_button)
        clearUrlButton = findViewById(R.id.clear_url_button)
        lockOverlay = findViewById(R.id.lock_overlay)
        lockToggle = findViewById(R.id.lock_toggle)
        expandLockTouchTarget()
        bindVersionLabel()
        lockHint = findViewById(R.id.lock_hint)
        gestureHud = findViewById(R.id.gesture_hud)
        gestureHudValue = findViewById(R.id.gesture_hud_value)
        gestureHudBar = findViewById(R.id.gesture_hud_bar)
        viewsReady = true
        fsSlopPx = 10 * resources.displayMetrics.density
        attachSafeAreaInsets()
        lockToggle.setOnClickListener { togglePlaybackLock() }
        // 全屏播放时注入层的顶栏是看不见的（网页全屏会把 nav 藏掉，或走原生
        // onShowCustomView 把 WebView 整个抽走），所以诊断入口挂在锁钮上：
        // 长按 1 秒把「壳层状态 + 注入层 debug JSON」复制到剪贴板。
        lockToggle.setOnLongClickListener {
            copyFullDiagnostics()
            true
        }
        copyUrlButton.setOnClickListener {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("KVideo URL", currentLoadUrl))
            Toast.makeText(this, getString(R.string.status_copied), Toast.LENGTH_SHORT).show()
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
        // 壳打不开但服务端可能没坏：交给系统浏览器验证，省得来回猜。
        openBrowserButton.setOnClickListener {
            val url = currentLoadUrl.ifEmpty { normalizeUrl(urlInput.text.toString()) }
            if (url.isEmpty()) return@setOnClickListener
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.status_no_browser), Toast.LENGTH_SHORT).show()
            }
        }
        clearUrlButton.setOnClickListener {
            urlInput.setText("")
            urlInput.requestFocus()
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

        // 输入框改过但没保存时给一条提示：以前两个按钮等宽，点「打开已保存地址」
        // 会静默丢掉刚编辑的内容。这里只提示，不改按钮语义。
        urlInput.doAfterTextChanged { editable ->
            if (!viewsReady) return@doAfterTextChanged
            val saved = prefs.getString(PREF_SERVER_URL, null)?.trim().orEmpty()
            val typed = editable?.toString()?.trim().orEmpty()
            updateSaveEnabled()
            val wantClear = if (typed.isEmpty()) View.GONE else View.VISIBLE
            if (clearUrlButton.visibility != wantClear) clearUrlButton.visibility = wantClear
            if (saved.isNotEmpty() && typed.isNotEmpty() && typed != saved) {
                setStatus(getString(R.string.status_dirty), StatusTone.WARN)
            }
        }
        updateSaveEnabled()

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
                    // 全屏仍转横屏：竖屏全屏时 16:9 画面只剩中间一条，黑边太大。
                    // 已经是横屏时设这个标志等于无操作，不会二次旋转。
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
            showSetup(getString(R.string.status_first_launch), StatusTone.WARN)
        }
    }

    /**
     * 任意触摸都算「有操作」。即使锁定态下 lock_overlay 吞掉了事件，
     * Activity 层仍会先收到这里，所以触屏一定能把锁钮唤回来。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            scheduleLockToggleHide()
            if (playbackActive) nativeTouchCount++
        }
        // 原生全屏（customView）下 WebView 收不到触摸，手势在这一层接管；
        // 页面内播放器返回 false，继续走注入层那套 JS 手势。
        if (handleFullscreenGesture(event)) return true
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
                    setPlaybackLocked(false, showUnlockHint = true)
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

    /**
     * 「保存并重载」只在地址真的变了才可点。
     * 原来地址一字未改也能点，点下去等于白重载一次，还容易让人以为保存了什么；
     * 比较用 normalizeUrl，免得 "192.168.1.2:81" 和 "http://192.168.1.2:81/" 被当成两次改动。
     */
    private fun updateSaveEnabled() {
        if (!viewsReady) return
        val typed = urlInput.text?.toString().orEmpty().trim()
        val saved = prefs.getString(PREF_SERVER_URL, null)?.trim().orEmpty()
        val same = try {
            saved.isNotEmpty() && normalizeUrl(typed) == normalizeUrl(saved)
        } catch (e: Exception) {
            false
        }
        val enabled = typed.isNotEmpty() && !same
        saveButton.isEnabled = enabled
        saveButton.alpha = if (enabled) 1f else 0.45f
    }

    /**
     * 设置页底部显示当前版本。
     * 之前版本号只出现在长按齿轮复制的诊断里 —— 装了什么版本，App 自己都说不清。
     */
    private fun bindVersionLabel() {
        val label = findViewById<TextView>(R.id.version_text) ?: return
        label.text = getString(
            R.string.version_label, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
    }

    /**
     * 锁钮视觉只有 40dp（与网页底栏圆钮同款），低于 48dp 的可点下限；它又正好
     * 压在视频画面上，横屏单手很容易点空。这里只放大**命中区**：用 TouchDelegate
     * 在父容器里把 40dp 扩到 56dp，视觉尺寸一个像素都不动。
     *
     * 每次布局变化都重装一次 —— 转屏、进出全屏时按钮位置会变，旧矩形会失效。
     */
    private fun expandLockTouchTarget() {
        val parent = lockToggle.parent as? View ?: return
        val pad = (8 * resources.displayMetrics.density).toInt()
        lockToggle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val rect = Rect()
            lockToggle.getHitRect(rect)
            if (rect.isEmpty) return@addOnLayoutChangeListener
            rect.inset(-pad, -pad)
            parent.touchDelegate = TouchDelegate(rect, lockToggle)
        }
    }

    /**
     * 首次配置页的「使用提示」：默认收起，只留两行要点。
     * 展开后是手势与诊断说明——这些一年看一次，不该常驻占掉一屏。
     */
    private fun setupHelpToggle() {
        val toggle = findViewById<TextView>(R.id.help_toggle) ?: return
        val body = findViewById<TextView>(R.id.help_body) ?: return
        toggle.setOnClickListener {
            val open = body.visibility == View.VISIBLE
            body.visibility = if (open) View.GONE else View.VISIBLE
            toggle.setText(if (open) R.string.setup_help_collapsed else R.string.setup_help_expanded)
        }
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
        lockTogglePressHandler.removeCallbacks(clearLockTogglePress)
        exitCustomFullscreen()
        webView.destroy()
        super.onDestroy()
    }

    /**
     * 状态文字的三档语义。之前不分档、一律琥珀色，导致「已准备就绪」看着像警告，
     * 真正的错误（地址无效）又和普通提示同色。
     */
    private enum class StatusTone { INFO, WARN, ERROR }

    private fun setStatus(message: String, tone: StatusTone) {
        if (!viewsReady) return
        statusText.text = message
        statusText.setTextColor(
            getColor(
                when (tone) {
                    StatusTone.INFO -> R.color.kv_text_tertiary
                    StatusTone.WARN -> R.color.kv_status_warn
                    StatusTone.ERROR -> R.color.kv_status_error
                }
            )
        )
    }

    private fun openConfiguredUrl() {
        val normalizedUrl = normalizeUrl(urlInput.text.toString())
        if (!isValidUrl(normalizedUrl)) {
            setStatus(getString(R.string.status_invalid_url), StatusTone.ERROR)
            urlInput.requestFocus()
            return
        }

        prefs.edit().putString(PREF_SERVER_URL, normalizedUrl).apply()
        // 保存成功后按钮立刻回到禁用态：地址已经是「已保存」的那一个了。
        updateSaveEnabled()
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
        // 覆盖页收起，系统栏切回跟网页走（此时还不知道新页面深浅，先用上一次的值）
        applySystemBars(false)
        setStatus(getString(R.string.status_ready), StatusTone.INFO)
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
        // 失败页是原生层画的，系统栏跟着系统深浅色，别继续用网页那份
        applySystemBars(false)
    }

    private fun showSetup(message: String, tone: StatusTone = StatusTone.INFO) {
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

        setStatus(message, tone)
        setupContainer.visibility = View.VISIBLE
        // 设置页是原生层画的，系统栏跟着系统深浅色走
        applySystemBars(false)
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
        // 碰到 0 / 100 时震一下，只在「刚碰到」那一次触发，别按住不放一直震。
        val boundary = if (p == 0 || p == 100) p else -1
        if (boundary != -1 && boundary != lastGestureBoundary) {
            gestureHud.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        lastGestureBoundary = boundary
        gestureHud.visibility = View.VISIBLE
        gestureHudHandler.removeCallbacks(hideGestureHud)
        gestureHudHandler.postDelayed(hideGestureHud, 800L)
    }

    /** 音量一档。原生全屏手势与 JS 桥共用同一份实现，浮层外观因此完全一致。 */
    private fun stepVolume(direction: Int): Int {
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
        return pct
    }

    /** 亮度一档（只改本窗口，不改系统设置）。 */
    private fun stepBrightness(direction: Int): Int {
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
        return pct
    }

    // ———————————————————————————————————————————————————————————
    // 原生全屏下的手势
    //
    // 全屏播放走 WebChromeClient.onShowCustomView 时，视频被挪进 fullscreen_container、
    // WebView 被设为 GONE，注入层的 document 收不到任何触摸（线上实测：进全屏后
    // moves=0、hud=0，而 nativeTouches 有数）。所以这一段用原生触摸事件接管，
    // JS 只用来读写 video.currentTime —— 媒体元素的表面换了宿主，元素本身还活着。
    // 浮层复用壳层的手势卡片，与页面内播放器的手感保持一致。
    // ———————————————————————————————————————————————————————————
    private var fsDownX = 0f
    private var fsDownY = 0f
    private var fsLastStepY = 0f
    private var fsMode = 0            // 0 未判定 / 1 拖进度 / 2 音量亮度
    private var fsSideLeft = true
    private var fsClockReady = false
    private var fsStartT = 0.0
    private var fsDur = 0.0
    private var fsTarget = -1.0
    private var fsSlopPx = 0f

    /** 时间码：不足一小时用 mm:ss，超过用 h:mm:ss。 */
    private fun clockText(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        fun p(n: Int) = if (n < 10) "0$n" else "$n"
        return if (h > 0) "$h:${p(m)}:${p(s)}" else "${p(m)}:${p(s)}"
    }

    /** 问网页要当前播放位置与总时长；回来之前不进入拖进度模式。 */
    private fun queryVideoClock() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
                "return v?(v.currentTime+'|'+v.duration):''})()"
        ) { raw ->
            val parts = raw.trim().trim('"').split('|')
            val t = parts.getOrNull(0)?.toDoubleOrNull()
            val d = parts.getOrNull(1)?.toDoubleOrNull()
            if (t != null && d != null && d.isFinite() && d > 1) {
                fsStartT = t
                fsDur = d
                fsClockReady = true
            }
        }
    }

    private fun applyFullscreenSeek(target: Double) {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
                "if(v){try{v.currentTime=$target}catch(e){}}})()",
            null
        )
    }

    private fun isOnLockToggle(event: MotionEvent): Boolean {
        if (lockToggle.visibility != View.VISIBLE) return false
        val pad = 8 * resources.displayMetrics.density
        return event.x >= lockToggle.left - pad && event.x <= lockToggle.right + pad &&
            event.y >= lockToggle.top - pad && event.y <= lockToggle.bottom + pad
    }

    /**
     * 返回 true 表示这次事件已经被手势接管（吞掉），false 交给正常分发，
     * 这样锁钮的点击、以及全屏里的其它控件都不受影响。
     */
    private fun handleFullscreenGesture(event: MotionEvent): Boolean {
        // 网页里有浮层面板（播放设置 / 倍速）开着时必须让开：
        // 否则在面板上滑一下，全屏的亮度/音量手势也会跟着跑。
        if (customView == null || !playbackActive || playbackLocked || webPanelOpen) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                fsMode = 0
                fsClockReady = false
            }
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isOnLockToggle(event)) {
                    fsMode = 0
                    fsClockReady = false
                    return false
                }
                fsDownX = event.x
                fsDownY = event.y
                fsLastStepY = event.y
                fsMode = 0
                fsClockReady = false
                fsTarget = -1.0
                fsSideLeft = event.x < window.decorView.width / 2f
                queryVideoClock()
            }
            MotionEvent.ACTION_MOVE -> {
                if (fsMode == 0) {
                    val dx = event.x - fsDownX
                    val dy = event.y - fsDownY
                    val ax = abs(dx)
                    val ay = abs(dy)
                    if (maxOf(ax, ay) < fsSlopPx) return false
                    fsMode = when {
                        ax >= ay * 1.2f -> 1
                        ay >= ax * 1.2f -> 2
                        else -> 0
                    }
                    if (fsMode == 0) return false
                }
                if (fsMode == 1) {
                    if (!fsClockReady || fsDur <= 1) return true
                    val width = maxOf(1, window.decorView.width).toFloat()
                    val target = (fsStartT + (event.x - fsDownX) / width * fsDur)
                        .coerceIn(0.0, maxOf(0.0, fsDur - 0.25))
                    fsTarget = target
                    val pct = (target / fsDur * 100).roundToInt()
                    showGestureHud(
                        clockText(target) + " / " + clockText(fsDur) + " · 松手定位",
                        pct
                    )
                    return true
                }
                // 纵向：左半屏音量、右半屏亮度，每 24dp 一档
                val stepPx = 24 * resources.displayMetrics.density
                val steps = ((event.y - fsLastStepY) / stepPx).toInt()
                if (steps != 0) {
                    fsLastStepY += steps * stepPx
                    val dir = if (steps < 0) 1 else -1
                    repeat(minOf(abs(steps), 3)) {
                        if (fsSideLeft) stepVolume(dir) else stepBrightness(dir)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasSeek = fsMode == 1
                if (wasSeek && fsTarget >= 0) applyFullscreenSeek(fsTarget)
                fsMode = 0
                fsClockReady = false
                fsTarget = -1.0
                if (wasSeek) gestureHudHandler.postDelayed(hideGestureHud, 350L)
            }
        }
        return false
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

    /** 有输入时调用：立刻显示锁钮并重新计 3 秒空闲；离开播放页则立即收起。 */
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

    private fun updateLockUi(showUnlockHint: Boolean = false) {
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
        lockHintHandler.removeCallbacks(hideLockHint)
        if (playbackLocked || showUnlockHint) {
            showLockHint(playbackLocked)
        } else {
            lockHint.visibility = View.GONE
        }
    }

    /** 小号灰白胶囊：复用亮度/音量 HUD 的卡片样式，两态只换图标与文字，不用状态色。 */
    private fun showLockHint(locked: Boolean) {
        lockHint.text = getString(
            if (locked) R.string.lock_hint_locked else R.string.lock_hint_unlocked
        )
        val iconRes =
            if (locked) R.drawable.ic_lock_closed_24 else R.drawable.ic_lock_open_24
        val size = (16 * resources.displayMetrics.density).toInt()
        val icon = resources.getDrawable(iconRes, theme)?.mutate()
        icon?.setBounds(0, 0, size, size)
        lockHint.setCompoundDrawables(icon, null, null, null)
        lockHintHandler.removeCallbacks(hideLockHint)
        lockHint.animate().cancel()
        lockHint.alpha = 0f
        lockHint.scaleX = 0.92f
        lockHint.scaleY = 0.92f
        lockHint.visibility = View.VISIBLE
        lockHint.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(150L)
            .start()
        lockHintHandler.postDelayed(hideLockHint, 2500L)
    }

    /**
     * 电视上锁钮不接收遥控器焦点，三态 selector 的 focused 态不会触发、pressed 态
     * 又只闪一瞬。这里手动置 pressed 220ms 并做一次缩放回弹，保证按下必有可见反馈。
     */
    private fun flashLockTogglePress() {
        if (!viewsReady) return
        lockTogglePressHandler.removeCallbacks(clearLockTogglePress)
        lockToggle.isPressed = true
        lockToggle.animate().cancel()
        lockToggle.scaleX = 1f
        lockToggle.scaleY = 1f
        lockToggle.animate()
            .scaleX(0.82f).scaleY(0.82f)
            .setDuration(90L)
            .withEndAction {
                lockToggle.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(180L)
                    .start()
            }
            .start()
        lockTogglePressHandler.postDelayed(clearLockTogglePress, 220L)
    }

    private fun setPlaybackLocked(locked: Boolean, showUnlockHint: Boolean = false) {
        playbackLocked = locked
        updateLockUi(showUnlockHint)
    }

    private fun togglePlaybackLock() {
        flashLockTogglePress()
        lockToggle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setPlaybackLocked(!playbackLocked, showUnlockHint = true)
    }

    /** 只观察手势、不消费事件，网页自身的滚动/点击/横滑/拖动进度条都不受影响。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeToHide(target: WebView) {
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeHandled = false
                    swipeDownAt = event.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    if (viewsReady && !playbackActive && !isSetupVisible() && customView == null &&
                        webView.visibility == View.VISIBLE) {
                        val dx = event.x - swipeStartX
                        val dy = event.y - swipeStartY
                        // 只认明显垂直、且超过 48dp 的滑动；横滑切换/拖进度条不误触发
                        // 长按超过 500ms 才开始滑动：本次手势不参与收起/展开
                        val heldMs = event.eventTime - swipeDownAt
                        if (!swipeHandled && heldMs > 500L) {
                            swipeHandled = true
                        }
                        if (!swipeHandled && abs(dx) < abs(dy) * 0.8f) {
                            if (dy <= -swipeThresholdPx) {
                                swipeHandled = true
                                setChromeHidden(true)
                            } else if (dy >= swipeThresholdPx) {
                                swipeHandled = true
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
        applyWebChromeState()
    }

    /** 页面（重新）加载完成后注入样式与识别逻辑，并立即同步当前手势状态。 */
    private fun injectChromeScript() {
        // 首帧就把「浅色标记」和「安全区宽度」写好：注入层的 CSS 一到就按它们渲染，
        // 不会先按深色规则画一帧再纠正（浅色页会闪一下）。
        val initialLight = if (webLight ?: !isNightMode()) "1" else "0"
        val safeRight = lastSafeRightCss.coerceAtLeast(0)
        val js = "try{document.documentElement.setAttribute('data-kv-light','" + initialLight + "');" +
            "document.documentElement.style.setProperty('--kv-safe-right','" + safeRight + "px');}catch(e){}" +
            CHROME_INJECT_JS +
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

    /**
     * 把刘海/挖孔造成的安全边距喂给网页：注入层里「更多」三点是 right:24px 的
     * 固定像素，横屏时挖孔在侧边会把它切掉一角。原生这边用 --kv-safe-right 变量
     * 参与 calc()，锁钮则直接平移。
     *
     * 只取 displayCutout：非全屏时内容已经被系统栏 inset 过一遍，再叠加 systemBars
     * 会重复让位。
     */
    private fun attachSafeAreaInsets() {
        val content = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            if (viewsReady) {
                lockToggle.translationX = cut.left.toFloat()
            }
            pushSafeRightToWeb(cut.right)
            insets
        }
    }

    private fun pushSafeRightToWeb(rightPx: Int) {
        if (!::webView.isInitialized) return
        val css = (rightPx / resources.displayMetrics.density).roundToInt()
        if (css == lastSafeRightCss) return
        lastSafeRightCss = css
        webView.post {
            try {
                webView.evaluateJavascript(
                    "try{document.documentElement.style.setProperty('--kv-safe-right','" +
                        css + "px');}catch(e){}",
                    null
                )
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to push safe area inset", error)
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
     *
     * 1.1.50 起系统栏的底色与图标明暗跟着「当前看得见的那一层」走：
     *   - 设置页 / 加载失败页盖在上面 → 跟系统深浅色（原生页已有一整套 token）
     *   - 否则跟网页上报的亮暗（webLight），没收到就退回系统深浅色
     * 之前这里写死 setAppearanceLight*Bars(false) + statusBarColor #000000，
     * 浅色网页配一条黑边，浅色系统下更明显。
     */
    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 当前露在最上面的那一层是浅色底吗。 */
    private fun surfaceIsLight(): Boolean {
        val overlayVisible = viewsReady &&
            (isSetupVisible() || errorContainer.visibility == View.VISIBLE)
        if (overlayVisible) return !isNightMode()
        return webLight ?: !isNightMode()
    }

    private fun applySystemBars(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = surfaceIsLight()
        controller.setAppearanceLightStatusBars(light)
        controller.setAppearanceLightNavigationBars(light)

        // 系统栏底色：跟当前那一层的底色对齐，别让状态栏和内容之间横一条异色。
        // 原生覆盖页用 kv_surface（跟 layout 里同一个 token），网页用纯白/纯黑
        // ——网页顶栏多数就是这两档，剩下的差异只有一两阶，肉眼不跳。
        window.statusBarColor = if (light) Color.WHITE else Color.BLACK
        window.navigationBarColor = if (light) Color.WHITE else Color.BLACK

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

    /**
     * 全屏播放时注入层的顶栏不可见（网页把 nav 藏了，或走原生 onShowCustomView 把
     * WebView 整个抽走），所以诊断入口挂在锁钮的长按上：向网页要一份 debug JSON，
     * 拼上壳层状态后复制到剪贴板。
     */
    private fun copyFullDiagnostics() {
        if (!::webView.isInitialized) return
        val js = "(function(){try{return JSON.stringify(window.KVideoChrome" +
            "?window.KVideoChrome.debug():{err:'no-chrome'});}catch(e){return 'ERR:'+e;}})()"
        webView.evaluateJavascript(js) { value ->
            val text = try {
                (org.json.JSONTokener(value).nextValue() as? String) ?: value
            } catch (error: Exception) {
                value
            }
            copyDiagReport(text)
        }
    }

    private fun copyDiagReport(js: String) {
        val report = buildString {
            append("KVideo 手势诊断 v").append(BuildConfig.VERSION_NAME)
            append('(').append(BuildConfig.VERSION_CODE).append(")\n")
            append("playbackActive=").append(playbackActive)
            append(" webPanelOpen=").append(webPanelOpen)
            append(" customView=").append(customView != null)
            append(" fullscreenContainer=").append(fullscreenContainer.visibility == View.VISIBLE)
            append(" webViewVisible=").append(webView.visibility == View.VISIBLE)
            append(" nativeTouches=").append(nativeTouchCount)
            append(" orientation=").append(resources.configuration.orientation)
            append(" density=").append(resources.displayMetrics.density)
            append(" screen=").append(resources.displayMetrics.widthPixels)
            append('x').append(resources.displayMetrics.heightPixels)
            append('\n').append(js)
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            manager.setPrimaryClip(ClipData.newPlainText("KVideo diag", report))
            Toast.makeText(this, getString(R.string.diag_copied), Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Log.w(TAG, "Failed to copy diagnostics", error)
        }
    }

    private inner class AndroidPlayerBridge {
        /**
         * 注入层长按齿轮时把诊断 JSON 交过来。真机上没有 console，
         * 这是拿「手势为什么没反应」证据的唯一通道：附上壳层状态再复制到剪贴板。
         */
        @JavascriptInterface
        fun copyDiagnostics(js: String) {
            runOnUiThread { copyDiagReport(js) }
        }

        /** 网页上报「有浮层面板开着」（播放设置 / 倍速菜单）：原生全屏手势据此让开。 */
        @JavascriptInterface
        fun setWebPanelOpen(open: Boolean) {
            runOnUiThread { webPanelOpen = open }
        }

        /**
         * 网页上报自己是浅色还是深色底（注入层 applyLightFlag 已经把这件事判出来了）。
         * 系统栏据此换底色与图标明暗：浅色网页配白状态栏 + 深图标，深色网页反之。
         * 视频全屏时系统栏本来就是隐藏的，不在这里抢方向。
         */
        @JavascriptInterface
        fun setWebTheme(light: Boolean) {
            runOnUiThread {
                if (webLight == light) return@runOnUiThread
                webLight = light
                if (customView == null) applySystemBars(false)
            }
        }

        /** 网页上报当前是否处于横屏播放，壳层据此显示/隐藏防误触锁按钮。 */
        @JavascriptInterface
        fun onPlaybackState(active: Boolean) {
            runOnUiThread { onPlaybackStateChanged(active) }
        }

        /** 左半屏上下滑动：调系统媒体音量，返回 0..100 供提示浮层显示。
         *  不再用系统 FLAG_SHOW_UI 音量条，改走与亮度完全一致的壳层原生卡片。 */
        @JavascriptInterface
        fun adjustVolume(direction: Int): Int = runOnUiSync { stepVolume(direction) }

        /** 右半屏上下滑动：调当前窗口亮度，返回 0..100 供提示浮层显示。 */
        @JavascriptInterface
        fun adjustBrightness(direction: Int): Int = runOnUiSync { stepBrightness(direction) }

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
