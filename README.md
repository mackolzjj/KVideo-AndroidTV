# KVideo Android（手机 / 平板）

从 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 拆出的 **Android WebView 壳应用**：把 KVideo 网页包装成手机 App，并针对触屏重排顶栏、加入播放手势与防误触锁。APK 内**不含服务端代码**，需要你自己的 KVideo 地址（例如 `http://192.168.1.250:81`）。

- 最新版：[Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases) · `1.1.30`（`versionCode 32`）
- 运行要求：Android 8.0（API 26）及以上；手机与服务器网络互通
- 上游许可证：MIT，见 [LICENSE](LICENSE)，Copyright (c) 2025 Kuek Hao Yang

> 目录名 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是路径与标识，**不代表只能在 TV 上运行**。

## 快速开始

1. 下载 APK 并安装（需允许「安装未知来源应用」）。
2. 首次打开输入 KVideo 地址，点「保存并重载」。只填域名会自动补 `https://`，末尾 `/` 自动去掉；只接受 `http` / `https` 且有主机名的地址。地址存在本地，下次直接打开。构建时预置了 `kvideoUrl` 则跳过这一步。
3. 随时改地址：顶部条「**更多 → 应用设置**」；也可在网页首页连按返回键、或按外接键盘的菜单键 / SETTINGS 键进入设置页。

> 「**应用设置**」改的是本壳的服务器地址；「**网站设置**」是 KVideo 站内设置，两者不要混。

## 界面

### 顶栏（首页与播放页一致）

一行 **6 个圆钮 + 5 条固定竖线**，等距铺满 logo 右侧，两页数量、间距、配色完全相同：

`我的收藏` ｜ `观看历史` ｜ `更多` ｜ `浅色主题` ｜ `深色主题` ｜ `跟随系统`

- 主题三档**并排直接切换**，点哪个切哪个，当前档蓝色高亮；「跟随系统」是让网页配色随手机深浅色模式变化，不改系统设置。
- 「更多」弹层：**直播 / 退出登录 / 网站设置 / 应用设置**。不透明深色底 + 固定白字，不依赖网页 CSS 变量；某项功能在当前账号/页面不可用时自动隐藏，两侧竖线同步收起。直播受账号 `iptv_access` 权限控制。
- 播放页没有「我的收藏」导航链接，心形会兜底点「收藏这个视频」——语义变成收藏当前视频，但**两页按钮数量与外观一致**。
- 顶栏原生右侧按钮组、logo 旁的站名文字（保留紫色 logo）、画面中间可拖动的收藏/历史悬浮钮、右下角齿轮悬浮钮全部隐藏。齿轮已彻底停用，设置入口由「更多 → 应用设置」和返回键兜底。
- 圆钮 36px / 图标 24px（宽屏 40px、≤430px 32px、≤360px 30px），窄屏压缩间距避免溢出。
- **上滑 ≥48dp 收起整条顶栏，下滑恢复**；显隐只听手势，不跟随横竖屏或播放状态，横屏播放时不再触发。原生层只观察、不消费触摸事件，网页滚动 / 点击 / 横滑不受影响。

### 播放页控件位置

| 控件 | 位置 | 说明 |
| --- | --- | --- |
| 「更多」三点 | **右上角** | 从左上角（原与绿色 1080P 标签挤在一起）移来，弹层自动向左展开 |
| 「1x」倍速 | **底栏右侧按钮组左边** | 40px 圆钮，与底栏 `.btn-icon` 同款 10% 白底 + 20% 白描边；倍速菜单强制在按钮**上方**展开、最高 `60vh` 可滚动，不会被屏幕底边裁掉 |
| 快退 / 快进圆钮 | **默认隐藏** | 已缩到 28px / 图标 16px，改由滑动手势接管；网页原生的**双击左右区域 ±10 秒**仍保留 |

## 播放手势（1.1.22）

在画面中段单指滑动（避开顶部 12% / 底部 22%、按钮、进度条、输入框、弹层）。首次位移超过 10px 时按主轴 1.2 倍判定方向，**一次手势只走一种**，水平与纵向不打架。

| 手势 | 效果 |
| --- | --- |
| 水平左右滑动 | **跟手拖进度**：按 `水平位移 ÷ 进度条宽度 × 总时长` 连续计算目标时间，中央浮层实时显示「目标时间 / 总时长」「±偏移」「松手定位」，**松手才定位** |
| 左半屏上滑 / 下滑 | 系统媒体音量 **+ / −**（每 24px 一档，带系统音量条） |
| 右半屏上滑 / 下滑 | 当前窗口亮度 **+ / −**（每 24px 一档，不改系统设置，离开播放页自动还原） |

- 水平拖动**复用网页自己的进度条拖动机制**：合成同构 touch 事件喂给 `.slider-track`（`touchstart` 置拖动标志、document `touchmove` 更新 React 时间、`touchend` 才真正 seek），所以原生进度条与时间文字会一起跟手；松手先清标志再写 `video.currentTime` 兜底，合成失败也能跳转。
- 音量 / 亮度走 `KVideoAndroid` JS 桥在 UI 线程执行，同步回传百分比给中央浮层。按**手指按下时的左右半屏**分区，中途跨屏不改变功能。
- **上锁后**触摸被原生覆盖层吞掉，所有滑动手势都不生效。
- 直播流等无时长视频（`duration` 非有限值或 ≤1 秒）**不启用任何滑动手势**（含音量 / 亮度），请直接用系统音量键。

## 防误触锁

- **出现条件**：横屏且网页上报有 `<video>`；锁钮在**画面左侧中部**，40dp、10% 白底 + 20% 白描边、白色线性锁图标，与底栏圆钮同款。
- **静止 3 秒自动淡出**，任意触摸或按键立即唤出并重新计时。即便上锁后覆盖层吞了触摸，Activity 的 `dispatchTouchEvent` 仍先收到事件，所以触屏一定能把锁钮唤回来。
- **上锁时**：透明覆盖层吞掉所有触摸；`dispatchKeyEvent` 吞方向键、数字键、媒体键；**音量键与 HOME 放行**，视频照常播放。上锁瞬间画面中央只显示「已锁定」，2.5 秒后淡出。
- **解锁**：点锁钮，或按 OK / 返回 / MENU / SETTINGS，保证遥控器用户不会被锁死。
- **自动解锁**：退出系统全屏、进设置页、页面重载、离开播放页；锁状态**不持久化**。手势调过的窗口亮度在离开播放页时交还给系统亮度。

## 加载、全屏与导航

- 顶部 3dp 蓝色进度条，由 `onProgressChanged` 驱动，100% 自动收起。
- **加载失败页**：只对主框架触发（`onReceivedError` / `onReceivedHttpError` 判断 `isForMainFrame`，子资源 404 不误报），显示错误原因 + 完整地址，给「重试」和「修改地址」两个出口，不再对着黑屏连按返回。
- 网页视频进入系统全屏时自动转横屏、隐藏状态栏 / 导航栏、保持屏幕常亮；退出后恢复 `unspecified` 方向与系统栏。
- 支持画中画（PiP，设备支持时）：桥 `enterPictureInPicture()` 与 `kvideo-android-pip-change` 事件。
- **返回键优先级**：退出全屏 → 网页后退 → 回到设置页；设置页再按返回退出 App。
- WebView 允许双指缩放（不显示 +/- 按钮）、DOM 存储、媒体自动播放、明文 HTTP 与混合内容。

## 构建 APK

### GitHub Actions（推荐）

`Actions → Android Phone APK → Run workflow`，三个可选输入：

| 输入 | 说明 |
| --- | --- |
| `kvideo_url` | 预置进 APK 的默认地址，留空则首次启动手填 |
| `release_tag` | 填了才创建 / 更新 Release（如 `v1.1.30`） |
| `release_title` | Release 标题，留空用默认 |

产物：`kvideo-android-phone-apk-<versionName>-r<run_number>` artifact；填了 `release_tag` 时，Release 资产名为 `KVideo-Android-<versionName>.apk`。`versionName` 直接从 `app/build.gradle.kts` 读取，不用手工同步。

### 签名与升级（重要）

- 固定 debug 密钥以 base64 随仓库版本化在 `.github/debug-keystore.b64`，构建时还原到 `app/debug.keystore`，由 `build.gradle.kts` 的 `signingConfigs.debug` **显式引用**。既不依赖 7 天未访问即被清理的 Actions 缓存，也不依赖 Gradle 自动生成的默认 debug key（实测后者每次构建都会换一把）。
- 证书 SHA-256 指纹 `8D:63:BD:AC:74:59:93:8F:0A:8F:A2:2A:98:9B:64:90:DB:37:CD:AD:D0:C9:91:A1:1F:DD:9B:3B:8A:44:F3:C4`。任意两次构建签名一致，**可直接覆盖安装、不会丢已保存的地址**。
- **`v1.1.4` 起才使用这把固定 key**。`v1.1.3` 及更早每次构建签名都不同且未保留，从那些版本升级需**先卸载一次旧包**并重新填地址，此后所有版本都能直接覆盖安装。
- 这把 key 只用于开发签名，密码是公开的 `android`，**不能用于应用商店分发**。

### 本地命令行

需要 JDK 17 + Android SDK Platform 34 + Build Tools 34.0.0：

```bash
cd android-tv
base64 -d ../.github/debug-keystore.b64 > app/debug.keystore
./gradlew assembleDebug -PkvideoUrl="http://192.168.1.250:81"
adb install app/build/outputs/apk/debug/app-debug.apk
```

> 本地构建不会自动还原密钥，不先解码会被 Gradle 报找不到 keystore；也不要用 `~/.android/debug.keystore`，签名会不一致、无法覆盖安装。

### 发版规则

每次发布在 `android-tv/app/build.gradle.kts` 递增 `versionCode`（只增不减，Android 靠它判断能否覆盖安装）并更新 `versionName`；Release tag 与 `versionName` 对齐（如 `v1.1.30`）。**不要删除或重新生成 `.github/debug-keystore.b64`** —— 换密钥等于要求所有已安装用户卸载重装。

## 实现要点 / 排障

| 文件 | 职责 |
| --- | --- |
| `app/src/main/java/com/kvideo/tv/MainActivity.kt` | 全部原生逻辑 + 内嵌 `CHROME_INJECT_JS`（注入网页的 CSS/JS 都在这里） |
| `app/src/main/res/layout/activity_main.xml` | WebView、设置页、进度条、错误页、锁覆盖层 / 锁钮 / 提示 |
| `app/src/main/res/values/strings.xml` | 全部界面文案 |
| `app/build.gradle.kts` | 版本号、签名配置、`DEFAULT_KVIDEO_URL` |
| `.github/workflows/android-tv-apk.yml` | 构建与发布流程 |
| `.github/debug-keystore.b64` | 固定签名密钥 |

- 顶栏重排与播放页样式都由 `onPageFinished` 注入的 JS/CSS 完成，功能通过 `click()` 转发给网页原生按钮，**一个功能都没删**。回壳层走 `KVideoAndroid` 桥：`openAppSettings()`、`onPlaybackState()`、`adjustVolume()`、`adjustBrightness()`、`setAppSettingsDocked()` 与 PiP 相关方法。
- 网页由 React 挂载 / 重渲染：注入逻辑挂在 `MutationObserver`（监听 `class` / `aria-label`，刻意不监听 `style` 以免自触发）+ **1 秒低频补扫** + `pageshow` + `resize` / `orientationchange` 上。只放 `syncMenu()` 会在播放页（无 nav）提前返回时失效。
- **移动网页控件只能改外层定位容器的样式，不要 `appendChild` 搬 DOM 节点**：React 下次更新会在原父节点上 `removeChild` 抛 `NotFoundError`。弹层是 Portal + `getBoundingClientRect()`，容器一动会自动跟随。
- 上游改版导致菜单插不上时，1.5 秒后移除 `kvideo-menu-ready`、加 `kvideo-dock-failed`，让原生按钮与悬浮钮回落，入口不会丢。
- 排查注入是否命中：在页面执行 `window.KVideoChrome.debug()`，常用字段 `menu / menuReady / buttons / seps / sepsVisible / themeButtons / seekSizes / progressBar / gestureHud / videoDuration / rateButton / ratePos / speedMenu / moreButton / morePos / playbackActive`；`window.KVideoChrome.sync()` 可手动重扫。

## 已知限制 / 注意

- 这是 WebView 壳，网页端功能取决于你的 KVideo 服务端版本；上游改文案或 DOM 结构可能影响注入用的选择器。
- Manifest 必须保留 `android:usesCleartextTraffic="true"`，否则局域网 `http://` 在 Android 9+ 连不上。
- 上游网页全屏时会调用 `screen.orientation.lock('landscape')`，Android WebView 一般不支持该 API（调用处已有异常捕获），实际方向由本壳 `onShowCustomView` 控制。
- 当前 APK 是 **debug 签名**（`debuggable`、未启用 release 混淆）：功能与 release 构建相同，但**不能用于应用商店分发**。
- 锁钮 3 秒空闲是代码常量 `LOCK_TOGGLE_IDLE_MS`，暂未在设置页开放调整；系统状态栏平时显示，只在系统全屏时隐藏。
- 顶部黑色区域是系统状态栏 + 网页自身留白，不是原生按钮造成的。

## 版本与许可

当前版本以 `android-tv/app/build.gradle.kts` 为准，已发布版本见 [Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases)。上游 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 遵循 MIT，见 [LICENSE](LICENSE)，Copyright (c) 2025 Kuek Hao Yang。
