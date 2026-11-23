# KVideo Android（手机 / 平板）

KVideo 服务端的**原生 Android 客户端**，界面按 Netflix 那套语言重画。App 里不含服务端，需要你自己的 KVideo 部署地址。

- 最新版：[Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases) · `2.0.17`（`versionCode 217`）
- 运行要求：Android 8.0（API 26）以上；手机与服务端网络互通
- 目录 `android-tv/` 与包名 `com.kvideo.tv` 沿用上游，只是标识，**不代表只能跑在 TV 上**
- 上游 MIT，见 [LICENSE](LICENSE)

## 2.0 是什么

1.1.x 是 WebView 壳 + 一大坨注入 CSS/JS（4900 行，靠选择器猜网页结构）。2.0 把那层整体删掉，
改成 **Kotlin + Jetpack Compose + Media3** 的原生 App，直接读服务端接口：

| 接口 | 用途 |
| --- | --- |
| `POST /api/auth` | 访问密码登录，服务端下发会话 cookie |
| `GET /api/config` | 订阅源列表（`subscriptionSources`） |
| `GET /api/douban/recommend` | 首页内容流 |
| `POST /api/search-parallel` | 搜索，SSE 流式返回（`start / videos / progress`） |
| `POST /api/detail` | 详情 + 剧集播放地址 |
| `GET /api/iptv` `/api/iptv/stream` | 直播列表 / 直播流代理 |

## 界面

- **首页**：大剧照 hero + 白色「播放」、继续观看、今日热播、正在直播
- **搜索**：搜片名，多源并行，边搜边出结果
- **详情**：剧照 hero、集数网格、收藏
- **播放**：ExoPlayer 自绘控制层 —— 红色细进度条、`←10s 播放 10s→ 时间`、倍速、锁定、全屏
- **直播**：分类 chip + 频道列表
- **片单**：收藏 / 历史
- **设置**：服务器地址、访问密码、直播源

视觉规则：纯黑底，唯一强调色 `#e50914` 只给进度；圆角最大 4px；字重只有两档。

## 构建

```bash
cd android-tv
./gradlew assembleDebug           # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug -PkvideoUrl="http://192.168.1.2:81"   # 预置服务器地址
```

推送 `v*` 标签会触发 workflow 建 Release，资产名 `KVideo-Android-<versionName>.apk`。

## 回滚

1.1.x 的 WebView 壳仍在 git 历史里，最后一个可用版本是标签 `v1.1.144`（`versionCode 146`）。
