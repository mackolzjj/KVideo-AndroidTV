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
| 设置页入口 | 遥控器菜单键 / D-pad | 网页右上角「设置」按钮，或连按返回键 |
| 按键逻辑 | D-pad 中键映射 Enter、废弃的 `onBackPressed()` | 删除 D-pad 映射，改用 `OnBackPressedDispatcher` |
| 界面尺寸 | 30sp 标题 / 32dp 内边距 / 680dp 卡片 | 22sp / 20dp / 480dp，适配手机竖屏 |
| WebView 缩放 | 固定视口 | 允许双指缩放（`builtInZoomControls`，不显示 +/- 按钮） |
| 明文 HTTP | 已允许 | 保留（局域网 `http://` 地址必需） |

保留：PiP 画中画与 `KVideoAndroid` JS 桥接、`configChanges`（旋转不重建 Activity、WebView 不重载）、`windowSoftInputMode=adjustResize`、debug 签名构建。

> 目录 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是路径/标识名，不代表只能在 TV 运行；改目录名会连带工作流与引用一起变，没有必要。

## 使用

1. 安装到 Android 8.0（API 26）及以上的手机。
2. 首次打开输入 KVideo 地址，如 `http://192.168.1.250:81`，点「保存并重载」。构建时用 `kvideo_url` 预置了地址则直接打开。
3. 网页右上角半透明「设置」按钮可随时改地址；也可连按返回键到网页首页后再按一次返回。
4. 视频点全屏 → 自动横屏 + 隐藏状态栏；退出全屏 → 恢复竖屏与系统栏。返回键优先退出全屏，其次网页后退，最后回到设置页。

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
