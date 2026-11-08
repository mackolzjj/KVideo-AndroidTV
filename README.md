# KVideo Android TV

从 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 拆出的 **Android TV WebView 壳应用**，只保留构建 APK 所需的 `android-tv/` 工程与对应 GitHub Actions 工作流，不包含 Next.js 主站源码。

> 上游许可证：MIT，见 [LICENSE](LICENSE)。Copyright (c) 2025 Kuek Hao Yang。
> 本仓库只是 WebView 壳，APK 内不含 KVideo 服务端代码，需要你自己的 KVideo 实例地址。

## 与上游的差别

1. 仅保留 `android-tv/`、`.github/workflows/android-tv-apk.yml`、`LICENSE`；目录结构原样保留（工作流里 `working-directory: android-tv` 是硬编码的）。
2. `android-tv/app/src/main/AndroidManifest.xml`：`android:screenOrientation="landscape"` 改为 `"unspecified"`，**不再强制横屏**，方向跟随系统旋转设置。
3. `.gitignore` 换成 Android/Gradle 专用版本；新增本 README。

### 想改方向

编辑 `android-tv/app/src/main/AndroidManifest.xml` 里 `<activity>` 的 `android:screenOrientation`：

| 值 | 效果 |
| --- | --- |
| `unspecified`（当前） | 跟随系统旋转设置自动旋转 |
| `landscape` | 强制横屏（上游默认） |
| `portrait` | 强制竖屏 |
| `fullSensor` | 跟随重力传感器，四个方向都允许 |
| `user` | 跟随用户的旋转锁设置 |

`android:configChanges` 已包含 `orientation|screenSize|...`，旋转不重建 Activity，WebView 不重载、播放不中断。
注意：真正的 Android TV / 电视盒子通常系统层面就锁定横屏，改成 `unspecified` 后在电视上看起来仍是横屏，属正常；在手机、平板等支持旋转的设备上才会看到区别。

上游网页端另有两处在播放器全屏时调用 `screen.orientation.lock('landscape')`（`lib/hooks/mobile/useScreenOrientation.ts`、`components/player/hooks/desktop/useFullscreenControls.ts`），Android WebView 一般不支持该 API 且调用有异常捕获，通常不生效。

## 构建 APK

### 方式一：GitHub Actions（推荐）

**Actions → Android TV APK → Run workflow**，可填：

- `kvideo_url`：预置到 APK 的 KVideo 地址（例如 `http://192.168.1.250:81`），留空则首次启动手动输入
- `release_tag` / `release_title`：填写后把 APK 上传到对应 GitHub Release

产物：`kvideo-android-tv-apk` artifact（debug APK）。

### 方式二：本地命令行

需要 JDK 17 + Android SDK Platform 34 + Build Tools 34.0.0：

```bash
cd android-tv
./gradlew assembleDebug -PkvideoUrl="http://YOUR-KVIDEO-HOST:3000"
adb install app/build/outputs/apk/debug/app-debug.apk
```

最低系统要求 Android 8.0（API 26）。首次打开输入 KVideo 地址，保存后下次直接进入。
