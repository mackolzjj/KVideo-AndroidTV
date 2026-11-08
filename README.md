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
| 设置页入口 | 遥控器菜单键 / D-pad | 右下角 48dp 齿轮悬浮按钮（上滑隐藏 / 下滑显示），或连按返回键 |
| 按键逻辑 | D-pad 中键映射 Enter、废弃的 `onBackPressed()` | 删除 D-pad 映射，改用 `OnBackPressedDispatcher` |
| 界面尺寸 | 30sp 标题 / 32dp 内边距 / 680dp 卡片 | 22sp / 20dp / 480dp，适配手机竖屏 |
| WebView 缩放 | 固定视口 | 允许双指缩放（`builtInZoomControls`，不显示 +/- 按钮） |
| 明文 HTTP | 已允许 | 保留（局域网 `http://` 地址必需） |

保留：PiP 画中画与 `KVideoAndroid` JS 桥接、`configChanges`（旋转不重建 Activity、WebView 不重载）、`windowSoftInputMode=adjustResize`、debug 签名构建。

> 目录 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是路径/标识名，不代表只能在 TV 运行；改目录名会连带工作流与引用一起变，没有必要。

## 使用

1. 安装到 Android 8.0（API 26）及以上的手机。
2. 首次打开输入 KVideo 地址，如 `http://192.168.1.250:81`，点「保存并重载」。构建时用 `kvideo_url` 预置了地址则直接打开。
3. 在网页上**上滑**可同时收起顶部胶囊工具条和右下角齿轮，**下滑**再展开；显隐只听手势，横屏竖屏都有效。点**右下角齿轮**可随时改服务器地址；连按返回键到网页首页后再按一次返回也能进设置页。
4. 视频点全屏 → 自动横屏 + 隐藏状态栏与所有悬浮按钮；退出全屏 → 恢复竖屏、系统栏与手势控制的工具条。返回键优先退出全屏，其次网页后退，最后回到设置页。

### 手机端交互习惯

- **上滑隐藏、下滑显示**：在网页内容上向上滑超过约 48dp，会同时收起**网页顶部那排胶囊工具条**和**右下角齿轮**；向下滑再展开。和 Chrome / Safari 的地址栏是同一个习惯。横屏竖屏都生效，**显隐只听手势，不跟随屏幕方向或播放画面**。
- 原生层用 `WebView.setOnTouchListener` 只观察手势、**不消费事件**，网页自身的滚动、点击、下拉刷新、横向切换和拖动进度条都不受影响；只有明显垂直的滑动才会触发。
- **网页顶部工具条**由 `onPageFinished` 注入的一段 JS/CSS 控制：自动识别贴顶的 `nav` / `header`（`sticky` 或 `top-0`，即上游 `Navbar` / `PlayerNavbar`），打上 `data-kvideo-topbar` 标记，再用 `<html class="kvideo-chrome-hidden">` 做 `translateY(-120%)` + 淡出。`sticky` 元素仍占位，内容不会突然跳动。带 `MutationObserver`，Next.js 站内换页重新渲染也能重新命中；KVideo 改版若换了标签，只需改 `CHROME_INJECT_JS` 里的选择器。
- **右下角齿轮 = 改 KVideo 服务器地址**（原生设置页）；**网页里的齿轮 = 站内设置**。两者分工不变，原生按钮放在右下角（Android FAB 常见位置），不占右上角、不压网页控件。
- 齿轮仍是 48dp 触摸目标、圆形半透明底 + 白色细描边、点击水波纹、`contentDescription`；**视频全屏和设置页仍强制隐藏**，不挡画面。横屏时不再自动隐藏，但可用上滑收起，或用返回键 / 外接键盘菜单键进设置页。
- 顶部黑色区域是系统状态栏 + 网页自身的顶部留白，不是原生按钮造成的；本壳平时保留状态栏（能看时间、电量、下拉通知），只在全屏播放时隐藏。

## 构建 APK

### GitHub Actions（推荐）

**Actions → Android Phone APK → Run workflow**：

- `kvideo_url`：预置到 APK 的 KVideo 地址（例如 `http://192.168.1.250:81`），留空则首次启动手输
- `release_tag` / `release_title`：填写后把 APK 发到 Releases

产物：`kvideo-android-phone-apk` artifact（debug APK），Release 中文件名为 `KVideo-Android.apk`。

### 本地命令行

需要 JDK 17 + Android SDK Platform 34 + Build Tools 34.0.0：

```bash
cd android-tv
./gradlew assembleDebug -PkvideoUrl="http://192.168.1.250:81"
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 注意

- **明文 HTTP**：Manifest 已设 `android:usesCleartextTraffic="true"`，局域网 `http://` 可用；Android 9+ 默认禁止明文，这一项不能删。
- 上游网页端在播放器全屏时会调用 `screen.orientation.lock('landscape')`（`lib/hooks/mobile/useScreenOrientation.ts` 等）。Android WebView 一般不支持该 API 且调用有异常捕获，实际方向由本壳 `onShowCustomView` 控制。
- 这是 WebView 壳，网页端功能取决于你的 KVideo 服务端版本。
