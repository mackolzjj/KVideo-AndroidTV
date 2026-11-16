# KVideo Android（手机 / 平板）

从 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 拆出的 **Android WebView 壳应用**：把 KVideo 网页包装成手机 App，并针对触屏重排顶栏、加入播放手势与防误触锁。APK 内**不含服务端代码**，需要你自己的 KVideo 地址（例如 `http://192.168.1.250:81`）。

- 最新版：[Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases) · `1.1.44`（`versionCode 46`）
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
- 顶栏原生右侧按钮组、logo 旁的站名文字（保留 logo，改为同款圆形线性标）、画面中间可拖动的收藏/历史悬浮钮、右下角齿轮悬浮钮全部隐藏。齿轮已彻底停用，设置入口由「更多 → 应用设置」和返回键兜底。
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
| `release_tag` | 填了才创建 / 更新 Release（如 `v1.1.37`） |
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

每次发布在 `android-tv/app/build.gradle.kts` 递增 `versionCode`（只增不减，Android 靠它判断能否覆盖安装）并更新 `versionName`；Release tag 与 `versionName` 对齐（如 `v1.1.37`）。**不要删除或重新生成 `.github/debug-keystore.b64`** —— 换密钥等于要求所有已安装用户卸载重装。

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

## 1.1.44 变更（相对 1.1.43）

修「1x 比画中画/全屏晚一拍（约 1 秒）」：

- **根因**：显隐同步原来只挂在 `class` / `aria-label` 的 MutationObserver 上，另加每秒一次的全量扫描。而这个播放器是直接写**内联 `style.opacity`** 来淡出控制条的——只监听 class 就收不到通知，1x 只能等 1 秒后的扫描才跟着消失，看起来就是它自己慢半拍、还跳一下。
- 现在把 `style` 也纳入监听，并且任何一次 DOM 变化都立刻唤起逐帧跟随（`requestAnimationFrame`）。实测：控制条开始淡出后 **33ms（约 2 帧）** 1x 就跟上了，和相邻图标同步走完。
- 自己的写入不会引起死循环：跟随循环有「运行中」闸门去重，样式写入靠「值相同就不写」收尾。
- 仍只读写样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.43 变更（相对 1.1.42）

修「点屏幕、控制条淡出/淡入那一下，1x 会闪一下」：

- 原因是**采样台阶 + 容器过渡**叠在一起。上一版每 60ms 采一次控制条透明度再写一次，200ms 的淡出只采到三四个点；容器上又挂着 0.1s 的 opacity 过渡，台阶和过渡互相追，那一下看起来就是跳了一下。
- 现在淡入淡出期间改用 `requestAnimationFrame` **逐帧跟随**控制条的实测透明度，容器上的 opacity 过渡整条去掉：控制条走到哪一帧，1x 就是那一帧的值，没有台阶也没有滞后。停在 0 / 1 时 rAF 自动收工，不空转。
- 同时把「有效透明度」的取值收敛成一处（祖先链最小值 + 组内按钮中位数取小），两条路径写的是同一个值，不会再出现两个来源打架。
- 仍只读写样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.42 变更（相对 1.1.41）

修 1x 一直闪的问题：

- **外观只采样一次**：1.1.41 是每个 tick 都把相邻按钮的底色/描边「照抄」成内联样式。相邻按钮的底色会随焦点、悬停变化（全屏钮拿到焦点时会变白底），抄一次写一次，再叠上 `background-color` 的过渡，1x 的底色就在「有」和「没有」之间来回渐变——看起来就是一直在闪。
- 现在改为：采样一次 → 生成一条 `button[data-kvideo-rate="1"]` 样式规则 → 内容不变就绝不重写。规则写在独立的 `<style>` 里，React 重渲染擦不掉。
- 底色取组内**众数**、基准节点取「未被聚焦的按钮」：触屏上 `:hover` 会粘在刚点过的按钮上，单取某一个会把高亮态当成常态抄过来。
- 底色/描边不再挂 CSS 过渡（只保留 `transform`），避免任何一次重写被播成一段渐变。
- **透明度改为连续跟随**：不再用「隐藏 / 显示」二值判断（实测值在阈值附近抖动会连续翻转，一次淡出闪好几下），而是把 1x 的 opacity 直接映射成控制条实测值，跟着一起淡、一起亮。组内按钮自身挂 opacity 淡出的播放器也覆盖到了（取中位数，避免被禁用态带偏）。
- 仍只读写样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.41 变更（相对 1.1.40）

- **撤掉胶囊方案**：1.1.39 / 1.1.40 把「1x / 画中画 / 网页全屏 / 全屏」收成一条共用底色的长条，同时把三个图标自己的圆底抹平——这等于改掉了播放器原有的按钮样式，视觉上变成「一排裸图标套一个框」。现在整段撤除：底栏按钮的圆底、描边、阴影一律不动。
- **改成「第四个同款圆钮」**：1x 的底色、描边、圆角、阴影、字色、尺寸全部照抄相邻图标钮（内联 important），间距取组内实测值，插在画中画左边。四个按钮长得一模一样、间距一致，看起来天然属于同一排。
- 控制条淡出时 1x 跟着一起淡出，逻辑不变（只认 opacity / display / visibility）。
- 仍只读计算样式、只写内联样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.40 变更（相对 1.1.39）

修 1.1.39 引入的两个回归：

- **倍速钮消失**：判断「控制条是否隐藏」时把 `pointer-events-none` 也算作隐藏信号，而这类播放器为了让点击穿透到视频，控制层是**常驻** `pointer-events-none`（靠子按钮自己 `pointer-events:auto` 接事件）。于是判定永远为「隐藏」，倍速容器与胶囊垫层被永久写成 `opacity:0`。现在只看 `opacity` / `display` / `visibility`，且只沿 anchor 的祖先链取值，不再采样组内兄弟钮的透明度（兄弟可能存在 0.3 的禁用态）。
- **胶囊垫层不可见 + 图标看着不对称**：同上一因。控制条右侧的圆底已被胶囊方案抹平，胶囊一透明就只剩三个裸图标，与左侧两个圆底按钮对比显得不齐。修复后胶囊正常显示。
- **倍速钮插到图标上**：横向插入点误用了「组内最后一个图标」的左沿，会压在倒数第二个图标上；改为「第一个图标左沿 − 尺寸 − 间距」。
- **胶囊留白对称**：垫层改为以最后一个图标的右沿 +4px 收尾、倍速钮左沿 −4px 起头，左右余量相等；组的 `padding` 不再被算进胶囊宽度。
- 仍只读计算样式、只写内联样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.39 变更（相对 1.1.38）

- **倍速钮不再「自己闪」**：控制条淡出时，倍速钮原来会把相邻图标钮当时的透明度（0.3、0.6 这种中间值）抄成静态内联值，于是每点一次屏幕它就闪一下，且不会跟着整条控制条一起消失。现在透明度不再抄写，改为按整条控制条的状态（`opacity-0` 类与计算透明度）统一淡出淡入，和画中画、全屏完全同步。
- **四个按钮收进同一条胶囊**：1x、画中画、网页全屏、全屏原本各画一个圆底，排在一起仍是「四个独立圆钮」。现在整段共用一条圆角底色，各按钮自身的圆底抹平，视觉上是一个控件组。
- 仍然只读计算样式、只写内联样式，不搬 DOM 节点、不改事件绑定，播放路径未改。

## 1.1.38 变更（相对 1.1.37）

只动播放页倍速钮的排布，播放逻辑、导航、注入时机一律未改：

- **倍速钮并入底部按钮组**：原来在右侧按钮组左边留 12px 间距、自绘 10% 白底 + 1px 描边，看起来是浮在旁边的独立小件。现在间距取组内相邻按钮的实测值，外观直接照抄组内图标钮的计算样式（底色、描边、圆角、字色、尺寸），排在「画中画 / 全屏」左边成为同一排里的第一个。
- 只读计算样式、只写内联样式，不移动 DOM 节点、不改事件绑定，因此不影响播放器自身的点击与播放。

## 版本与许可

当前版本以 `android-tv/app/build.gradle.kts` 为准，已发布版本见 [Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases)。上游 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 遵循 MIT，见 [LICENSE](LICENSE)，Copyright (c) 2025 Kuek Hao Yang。
