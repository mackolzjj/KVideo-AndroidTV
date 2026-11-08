# KVideo Android（手机端）

从 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 拆出的 **Android WebView 壳应用**，本仓库只面向**安卓手机 / 平板**，已移除 Android TV 专属内容。APK 内不含 KVideo 服务端代码，需要你自己的 KVideo 实例地址（例如 `http://192.168.1.250:81`）。

> 上游许可证：MIT，见 [LICENSE](LICENSE)，Copyright (c) 2025 Kuek Hao Yang。

## 相比 TV 原版改了什么

| 项目 | TV 原版 | 本仓库 |
| --- | --- | --- |
| 启动入口 | `LEANBACK_LAUNCHER` + `LAUNCHER` | 仅标准 `LAUNCHER`，手机桌面可见 |
| uses-feature | leanback / touchscreen 都声明为可选 | touchscreen 必需，去掉 leanback |
| TV 横幅 | `android:banner` + `tv_banner.xml` | 已删除 |
| 屏幕方向 | 强制 `landscape` | `unspecified` 跟随系统旋转锁；**仅全屏播放时**自动转横屏，退出恢复 |
| 系统栏 | 全程沉浸式隐藏状态栏/导航栏 | 平时显示，仅全屏视频时隐藏 |
| 屏幕常亮 | 一进 App 就常亮 | 仅全屏播放时常亮 |
| 设置页入口 | 遥控器菜单键 / D-pad | 顶部条「更多 → 应用设置」；备用：右下角齿轮（仅回落时显示）、连按返回键 |
| 按键逻辑 | D-pad 中键映射 Enter、废弃的 `onBackPressed()` | 删除 D-pad 映射，改用 `OnBackPressedDispatcher` |
| 界面尺寸 | 30sp 标题 / 32dp 内边距 / 680dp 卡片 | 22sp / 20dp / 480dp，适配手机竖屏 |
| 顶部菜单 | 原生一排 8+ 个圆钮，手机上会挤到 logo | 方案 A 分组菜单：内容组 3 钮 + 分隔线 + 工具组 2 钮 + 「更多」弹出层 |
| WebView 缩放 | 固定视口 | 允许双指缩放（`builtInZoomControls`，不显示 +/- 按钮） |
| 明文 HTTP | 已允许 | 保留（局域网 `http://` 地址必需） |

保留：PiP 画中画与 `KVideoAndroid` JS 桥接、`configChanges`（旋转不重建 Activity、WebView 不重载）、`windowSoftInputMode=adjustResize`、debug 签名构建。

> 目录 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是路径/标识名，不代表只能在 TV 运行；改目录名会连带工作流与引用一起变，没有必要。

## 使用

1. 安装到 Android 8.0（API 26）及以上的手机。
2. 首次打开输入 KVideo 地址，如 `http://192.168.1.250:81`，点「保存并重载」。构建时用 `kvideo_url` 预置了地址则直接打开。
3. 网页顶部条是重排后的分组菜单，一行 5 个元素：`直播 · 我的收藏 · 观看历史` ｜ `更多 · 切换主题`。点「更多」展开 `退出登录 / 网站设置 / 应用设置`。**「应用设置」才是改本壳服务器地址的地方**，「网站设置」是 KVideo 站内设置。
4. 在网页上**上滑**收起整条顶部菜单，**下滑**恢复；显隐只听手势，横屏竖屏都有效。连按返回键到网页首页后再按一次返回，也能回到服务器地址设置页。
5. 视频点全屏 → 自动横屏 + 隐藏状态栏与顶部工具条；退出全屏 → 恢复竖屏、系统栏与手势控制的工具条。返回键优先退出全屏，其次网页后退，最后回到设置页。

### 手机端交互习惯

- **上滑隐藏、下滑显示**：在网页内容上向上滑超过约 48dp，会收起顶部工具条（含分组菜单），向下滑再展开。和 Chrome / Safari 的地址栏是同一个习惯。横屏竖屏都生效，**显隐只听手势，不跟随屏幕方向或播放画面**。
- 原生层用 `WebView.setOnTouchListener` 只观察手势、**不消费事件**，网页自身的滚动、点击、下拉刷新、横向切换和拖动进度条都不受影响；只有明显垂直的滑动才会触发。
- **顶部菜单由 `onPageFinished` 注入的一段 JS/CSS 生成**：先给贴顶的 `nav` / `header`（`sticky` + `top-0`，即上游 `Navbar` / `PlayerNavbar`）打 `data-kvideo-topbar` 兜底标记，用 CSS 隐藏 `justify-between` 行里原生的右侧按钮组，再在同一行插入自建的 `data-kvideo-menu` 菜单。所有功能通过 `click()` 转发给原按钮，「应用设置」走 `KVideoAndroid.openAppSettings()`，**一个功能都没删**。
- 菜单就位后给 `<html>` 加 `kvideo-menu-ready`；如果上游改版导致菜单插不上，会移除该 class 让原生按钮自动回落，1.5s 兜底再加 `kvideo-dock-failed` 恢复历史悬浮钮与右下角齿轮，**入口永不丢失**。MutationObserver 用「节流立即扫 + 尾部补扫」处理 Next.js 站内换页，稳态不产生 DOM 变更。
- **右下角齿轮与画面中间的收藏/历史悬浮钮默认不显示**：它们只是重复入口，仅在菜单回落时作为备用出现。上滑收起时「更多」弹出层会自动关闭。
- 圆钮 36px / 图标 24px（宽屏 40px，380px 以下超窄屏 32px），避免挤到紫色 logo。直播受账号 `iptv_access` 权限控制，没有权限时不显示；主题由三钮并列改为单钮循环切换。
- 排查菜单没生效时，在页面里执行 `window.KVideoChrome.debug()`，看 `menu` / `menuReady` / `buttons` / `theme` / `fallback` 的命中数；`window.KVideoChrome.sync()` 可手动重扫。
- 顶部黑色区域是系统状态栏 + 网页自身的顶部留白，不是原生按钮造成的；本壳平时保留状态栏（能看时间、电量、下拉通知），只在全屏播放时隐藏。

## 构建 APK

### GitHub Actions（推荐）

**Actions → Android Phone APK → Run workflow**：

- `kvideo_url`：预置到 APK 的 KVideo 地址（例如 `http://192.168.1.250:81`），留空则首次启动手输
- `release_tag` / `release_title`：填写后把 APK 发到 Releases

产物：`kvideo-android-phone-apk-<versionName>-r<run_number>` artifact（debug APK）；填写 `release_tag` 时，Release 资产名为 `KVideo-Android-<versionName>.apk`。版本号直接从 `app/build.gradle.kts` 读取，不用手工同步。

**签名**：debug 签名密钥以 base64 形式随仓库版本化在 `.github/debug-keystore.b64`，构建时还原到 `~/.android/debug.keystore`。它不依赖 Actions 缓存（缓存超过 7 天未访问会被自动清理），所以任意两次构建的签名一致，可直接覆盖安装、不会丢已保存的服务器地址。这把 key 只用于开发签名，密码是公开的 `android`，不是应用商店正式签名。

### 本地命令行

需要 JDK 17 + Android SDK Platform 34 + Build Tools 34.0.0：

```bash
cd android-tv
./gradlew assembleDebug -PkvideoUrl="http://192.168.1.250:81"
adb install app/build/outputs/apk/debug/app-debug.apk
```

> 本地构建用的是 SDK 自动生成的 `~/.android/debug.keystore`，与 CI 的固定密钥不同，直接覆盖安装 CI 包会提示签名冲突。要保持一致，先按 workflow 里的方式把 `.github/debug-keystore.b64` 还原到本地。

## 版本与发布

- 每次发布前在 `android-tv/app/build.gradle.kts` 递增 `versionCode` 并更新 `versionName`；`versionCode` 只增不减，Android 靠它判断能否覆盖安装。
- Release 的 tag 与 `versionName` 对齐（如 `v1.1.4`），资产名由 workflow 自动带出版本，避免手机里显示一个版本、Release 页写另一个版本。
- 当前版本以 `app/build.gradle.kts` 为准；已发布的版本见 [Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases)。

## 注意

- **明文 HTTP**：Manifest 已设 `android:usesCleartextTraffic="true"`，局域网 `http://` 可用；Android 9+ 默认禁止明文，这一项不能删。
- 上游网页端在播放器全屏时会调用 `screen.orientation.lock('landscape')`（`lib/hooks/mobile/useScreenOrientation.ts` 等）。Android WebView 一般不支持该 API 且调用有异常捕获，实际方向由本壳 `onShowCustomView` 控制。
- 这是 WebView 壳，网页端功能取决于你的 KVideo 服务端版本。
