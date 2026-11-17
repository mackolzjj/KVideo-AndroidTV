# KVideo Android（手机 / 平板）

把 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 的网页包成 Android App 的 **WebView 壳**：顶栏按触屏重排，补上播放手势与防误触锁。APK 内**不含服务端**，需要你自己的 KVideo 地址。

- 最新版：[Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases) · `1.1.111`（`versionCode 113`）
- 运行要求：Android 8.0（API 26）以上；手机与服务端网络互通
- 目录 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是标识，**不代表只能跑在 TV 上**
- 上游 MIT，见 [LICENSE](LICENSE)

## 快速开始

1. 下载 APK 安装（需允许「安装未知来源应用」）。
2. 首次打开填地址 →「保存并重载」。只填域名会自动补 `https://`，末尾 `/` 去掉；只接受 `http` / `https` 且带主机名的地址。地址存本地，下次直接进。
3. 改地址：顶栏齿轮 → **应用设置**；也可在网页首页连按返回键。

> 「应用设置」改的是**壳的服务器地址**，「网站设置」是 KVideo 站内设置，两者别混。

## 界面

### 顶栏（首页 / 播放页一致）

logo 右侧 6 个图标，分两组：

`收藏` `历史` `直播` `重新加载` `设置（齿轮）` ｜ `主题循环钮`

- 图标 20px，按钮视觉 34px（≥640dp 40px、≤430dp 32px、≤360dp 30px）；组内间距 8px（≤430dp 6px）。**命中区撑到 48dp**（`::after` 上下各 +9px，横向只 +3px，再大相邻按钮会互压）。
- **三档主题合成一个循环钮**：图标永远等于当前档位（太阳 / 月亮 / 半圆），点一下按 `浅色 → 深色 → 跟随系统` 循环。蓝色只给浅色与深色两档，跟随系统用中性色；首次切档浮一条提示说明循环顺序，2.5 秒淡出、只出现一次。播放页没有主题按钮时该钮自动隐藏。
- 「重新加载」直接 `location.reload()`，用于页面卡住或注入未生效时自救。
- 齿轮弹层：网站设置 / 应用设置 /（分隔线）/ 退出登录（标红、压最底）。当前账号或页面不可用的项自动隐藏。
- 弹层位置**按实测几何居中**到齿轮，并夹住屏幕边缘，30 / 34 / 40 三档按钮下都对齐。
- **上滑 ≥48dp 收起整条顶栏，下滑恢复**；只听手势，横屏播放时不触发；原生层只观察、不消费触摸事件。
- **站名文字显示在 logo 右侧**（15sp / 600 字重 / 最长 96px 超出省略）；≤360dp 为给 6 个图标让位会自动隐藏。网页自带的右侧按钮组、可拖动的收藏 / 历史悬浮钮仍然隐藏。

### 播放页

| 控件 | 位置 |
| --- | --- |
| 「更多」三点 | 右上角，自动避开刘海安全区 |
| 「1x」倍速 | 底栏右侧按钮组最左，外观照抄相邻圆钮 |
| 快退 / 快进圆钮 | 默认隐藏，改由滑动手势接管（网页原生双击 ±10 秒仍在） |

## 手势与锁

画面中段单指滑动（避开顶部 12% / 底部 22%、按钮、进度条、输入框、弹层）。首次位移 >10px 时按主轴判定方向，一次手势只走一种。**在播放器卡片范围内即可生效**（视频画面、上下黑边、控制区都算，页面内播放器不要求全屏）；卡片之外只在全屏播放时接管。

| 手势 | 效果 |
| --- | --- |
| 左右滑 | 跟手拖进度，中央浮层显示「目标时间 / 总时长」与 ±偏移，**松手才定位** |
| 左半屏上下滑（仅全屏） | 媒体音量 ±（每 24px 一档） |
| 右半屏上下滑（仅全屏） | 窗口亮度 ±（不改系统设置，离开播放页自动还原） |

纵向的音量 / 亮度只在全屏播放时接管；页面内播放器只做左右拖进度，上下滑交还给网页滚动。

全屏播放走原生 `onShowCustomView` 时 WebView 会被隐藏、注入层收不到触摸，同一套手势由壳层用原生触摸事件实现（读写 `video.currentTime`，浮层复用同一张卡片）。

- 直播等无时长视频不启用手势，用系统音量键。
- 滑到 0% / 100% 有触感反馈；锁钮切换也有。
- **防误触锁**：横屏且网页有 `<video>` 时出现在画面左侧中部，静止 3 秒淡出、触摸即唤回。上锁后吞掉触摸与方向 / 数字 / 媒体键（音量键与 HOME 放行）；点锁钮或按 OK / 返回 / MENU 解锁。

## 主题与系统栏

- 注入层判定网页实际亮暗（主题选中档 → body 底色 → 保持上次值），写到 `<html data-kv-light>`，CSS 据此做深浅分支。
- 状态栏 / 导航栏跟**当前最上面那层**走：网页露着跟网页，设置页或失败页盖着跟系统深浅色。
- 原生页有浅 / 深两套配色（`values/colors.xml` + `values-night/colors.xml`）；HUD 与锁钮永远深底白字，因为它们压在视频画面上。

## 加载 / 全屏 / 返回键

- 顶部 3dp 进度条，100% 自动收起。
- 失败页只对主框架触发（子资源 404 不误报）：显示原因 + 完整地址 +「重试」（主按钮）/「复制地址」「修改地址」。
- 网页视频进全屏自动转横屏、隐藏系统栏、保持常亮；退出后恢复。
- 画中画：桥 `enterPictureInPicture()` 与 `kvideo-android-pip-change` 事件。
- 返回键优先级：退出全屏 → 网页后退 → 设置页 → 退出 App。
- 刘海：取 `displayCutout`，原生锁钮平移，网页侧用 `--kv-safe-right` 变量参与 `calc()`。

## 构建

### GitHub Actions（推荐）

`Actions → Android Phone APK → Run workflow`，三个可选输入：

| 输入 | 说明 |
| --- | --- |
| `kvideo_url` | 预置进 APK 的默认地址，留空则首次手动填 |
| `release_tag` | 填了才创建 / 更新 Release，如 `v1.1.52` |
| `release_title` | Release 标题，留空用默认 |

产物 artifact 名 `kvideo-android-phone-apk-<versionName>-r<run>`；带 tag 时 Release 资产为 `KVideo-Android-<versionName>.apk`。`versionName` 从 `app/build.gradle.kts` 读，无需手工同步。**推 `v*` 标签会自动构建并发 Release。**

### 本地

需要 JDK 17 + Android SDK Platform 34 + Build Tools 34.0.0：

```bash
cd android-tv
base64 -d ../.github/debug-keystore.b64 > app/debug.keystore   # 不解码会报找不到 keystore
./gradlew assembleDebug -PkvideoUrl="http://192.168.1.250:81"
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 签名

- 固定 debug key 随仓库版本化在 `.github/debug-keystore.b64`，由 `signingConfigs.debug` 显式引用：**每次构建签名一致，可直接覆盖安装、不丢已保存地址**。自 `v1.1.4` 起生效（更早版本签名不同，升级需先卸载一次）。
- 证书 SHA-256 指纹 `8D:63:BD:AC:74:59:93:8F:0A:8F:A2:2A:98:9B:64:90:DB:37:CD:AD:D0:C9:91:A1:1F:DD:9B:3B:8A:44:F3:C4`。
- 密码是公开的 `android`，**只能开发用，不能上架应用商店**。
- 发版：递增 `versionCode`（只增不减）+ 改 `versionName`，tag 与 `versionName` 对齐。**不要删或重建 `.b64`**——换 key 等于让所有已安装用户卸载重装。

## 代码地图

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 全部原生逻辑 + 内嵌 `CHROME_INJECT_JS`（注入的 CSS / JS 都在这里） |
| `res/layout/activity_main.xml` | WebView、设置页、失败页、锁层、HUD |
| `res/values{,-night}/` | 颜色 token、bool、主题；`strings.xml` 是全部文案 |
| `app/build.gradle.kts` | 版本号、签名配置、`DEFAULT_KVIDEO_URL` |
| `.github/workflows/android-tv-apk.yml` | 构建与发布流程 |

改注入层时必须守的几条：

- **只改样式，不搬 DOM**：React 重渲染会在原父节点 `removeChild`，`appendChild` 必抛 `NotFoundError`。
- 挂 `MutationObserver`（`class` / `aria-label`）+ 1 秒补扫 + `pageshow` / `resize` / `orientationchange`；只挂一次 `syncMenu()` 在播放页会提前返回而失效。
- 菜单插不上时 1.5 秒后撤 `kvideo-menu-ready`、加 `kvideo-dock-failed`，原生入口自动回落。
- 排查：**长按顶栏齿轮 0.6 秒** → 整份诊断 JSON（壳层状态、触摸计数、各闸门拒绝原因）复制到剪贴板；也可在页面里执行 `window.KVideoChrome.debug()`，`window.KVideoChrome.sync()` 可手动重扫。

## 已知限制

- 网页功能取决于你的 KVideo 服务端版本；上游改文案或 DOM 结构会打坏注入用的选择器。
- Manifest 必须保留 `usesCleartextTraffic="true"`，否则局域网 `http://` 在 Android 9+ 连不上。
- 上游全屏时调用 `screen.orientation.lock('landscape')`，WebView 不支持（已捕获异常），实际方向由壳的 `onShowCustomView` 控制。
- APK 是 debug 签名（`debuggable`、未开混淆）：功能与 release 构建一致，**不能上架**。
- 锁钮空闲时长是代码常量 `LOCK_TOGGLE_IDLE_MS`，暂未开放设置项。

## 变更记录

### 1.1.68（相对 1.1.52）

- **原生全屏手势**：全屏走 `onShowCustomView` 时 WebView 被隐藏，改由壳层原生触摸实现——左右跟手拖进度、左半屏音量、右半屏亮度，浮层复用同一张卡片；纵向手势只在全屏启用，页面内播放器上下滑仍交还网页滚动。
- **弹层固定弹出**：倍速 / 三点弹层改为在节点插入的**同一帧**摆好（`hidden` → 写 `left/bottom/zoom` → `visible`），并关掉全部 `animation` / `transition`，消除「出现一次、又挪一次」。
- **1x 与底栏完全同步**：逐帧跟随控制条实测透明度，修掉此前「慢半拍 / 闪一下 / 比邻居早消失」。
- **可见优先抓取**：控制栏、倍速钮、更多钮一律取当前可见的那一个，避免对着隐藏的那套摆位置。
- **弹层与手势不再打架**：网页的播放设置 / 倍速面板是 Portal 出来的高 z-index 面板，按 z-index 与毛玻璃特征识别；面板开着时注入层与壳层原生手势同时让开（新桥 `setWebPanelOpen`），全屏音量/亮度不会再跟面板抢。
- **弹层与手势隔离（原）**：网页的播放设置 / 倍速面板是 Portal 出来的高 z-index 面板，原来认不出来，在菜单上滑动会被当成播放手势（右半屏变成调亮度）。现在按 z-index 与毛玻璃特征识别，面板开着时一律不起手势。
- **诊断通道**：长按齿轮（页面内）或长按锁钮（全屏）复制诊断 JSON，含壳层状态、手势闸门记录、弹层位置轨迹。

更早版本见 [Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases)，逐版本细节在 git log 与各 Release 说明里。
