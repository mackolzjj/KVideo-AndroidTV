# KVideo Android

**给自建 [KVideo](https://github.com/KuekHaoYang/KVideo) 服务端用的原生 Android 客户端。**
Kotlin · Jetpack Compose · Media3，一套代码同时吃竖屏手机、横屏和 TV 遥控。

[![Release](https://img.shields.io/github/v/release/mackolzjj/KVideo-AndroidTV?label=release)](https://github.com/mackolzjj/KVideo-AndroidTV/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/mackolzjj/KVideo-AndroidTV/android-tv-apk.yml?label=CI)](https://github.com/mackolzjj/KVideo-AndroidTV/actions)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3ddc84)
![License](https://img.shields.io/github/license/mackolzjj/KVideo-AndroidTV)

> 本仓库**只有客户端**，不含服务端、不含任何影视内容。你需要自己部署一份 KVideo 服务端，App 里填它的地址。

---

## 装

1. 到 [Releases](https://github.com/mackolzjj/KVideo-AndroidTV/releases/latest) 下载 `KVideo-Android-<版本>.apk`
2. 直接安装（Android 8.0 / API 26 以上）
3. 所有版本共用同一张签名证书，**新版可直接覆盖安装**，不用先卸载

## 用（首次三步）

| 步骤 | 在哪 | 填什么 |
|---|---|---|
| 1 | 首次启动 | 服务端地址，如 `http://192.168.1.250:81` |
| 2 | 同上 | 访问密码（服务端的 `PREMIUM_PASSWORD` 或管理员密码） |
| 3 | 设置 → 高级设置 | 直播源 M3U 地址、节目单 EPG 地址（可选）、播放代理模式 |

**高级设置是隐藏入口**：设置页连点「版本」7 次 → 输入访问 PIN（4–8 位，首次自己设）。

---

## 功能

### 直播（这一版重点）

播放页内直接换台，不用退回列表：

- **顶栏「频道」按钮**：右侧滑出抽屉，竖屏单列、横屏多列，宽度按最长台名自适应
- **搜索框**：输台名或台号，输 `5` 直达 CCTV5，精确命中置顶
- **台标**：解析 M3U 的 `tvg-logo`，磁盘缓存，加载失败退回文字台号
- **节目名**：有 EPG 时每行第二行显示「正在播」，没有就留空
- **手势切台**：画面上左右滑切上一台 / 下一台（点播仍是拖进度，阈值 12% 屏宽）
- **回看**：底栏回看面板，7 天 × 24 小时，生成 `?playseek=` 地址
- **多线路**：同一频道有多条线路时长按频道名选线路
- **收藏**：抽屉内直接点 ☆，「收藏」分组置顶
- **其他**：自动滚到当前台、锁屏时抽屉禁用、抽屉 3 秒自动收起、换台不进返回栈（返回键仍回直播列表）

### 搜索

- 多源并行（SSE 流式），边搜边出结果，350ms 防抖
- 官方检索逻辑：最小匹配（标题至少命中一对连续两字）、屏蔽分类（默认「伦理」）、相关度打分排序
- 三排筛选：源 / 类型 / 语言；「只看相关」「合并同名」两个开关，合并后可按实测延迟选线路
- **源健康度**：详情或播放连续失败的源会阶梯临时停用（30 分钟 → 24 小时 → 7 天），停用期内搜索直接跳过，到期自动恢复；成功播放一次即清零。高级设置里能看到当前被停用的源，可手动恢复

### 播放

- 自绘控制层：红色细进度条、`←10s 播放 10s→`、时间、剧集、倍速、锁定、全屏
- 手势：单击显隐、双击左右 ±10s、横滑跟手快进、左半屏上下滑亮度、右半屏上下滑音量
- 竖屏 / 横屏自适应，沉浸式全屏
- 换集不断片，退出重进自动续播
- 线路自动降级：直连失败 → 服务器代理 → 备用线路

### 首页与片库

- 半屏 hero（横滑 3 张）+ 继续观看（真实进度）+ 今日热播「换一批」
- 分类入口：电影 / 剧集 / 综艺 / 动漫 / 纪录片，点进各自豆瓣片单（3 列网格、触底自动加载）
- 片单：收藏 / 历史，历史显示「看到 mm:ss」；同一部剧只占一格，多源标「N 个源」
- 普通用户搜索不含成人 / 伦理源，这些源只在「高级内容」里，需管理员或高级密码解锁
- 崩溃日志屏：崩过就开在最前面，可直接拷 `/Android/data/com.kvideo.tv/files/last_crash.txt`

### 设置

- 普通设置页只有三样：连接状态（只读）、清除历史、版本
- 高级设置：服务器地址 / 访问密码 / 直播源 / EPG / 播放代理（自动·直连·代理）/ 重新探活 / 清除图片缓存 / 屏蔽分类 / 高级内容 / 修改 PIN

---

## 已知限制

| 限制 | 说明 |
|---|---|
| 探活会永久剔除死源 | `aliveSources` 非空后不会自动恢复，源挂了要到高级设置点「重新探活」 |
| 直播源质量取决于你填的 M3U | 404、SSL 断流、返回 JSON 而不是 m3u8 都属于源侧问题 |
| EPG 要自己填 | 且要能对上 `tvg-id` 或台名，对不上的只显示频道名 |
| 分类页海报是按片名搜 | 豆瓣条目没有 `vod_id`，只能这样落到片源 |
| 同名片聚合键是片名 | 「庆余年」和「庆余年 第二季」不会被合并 |
| 源健康度只识别请求级失败 | 「能搜到结果但内容为空」这类脏源识别不了，需要服务端支持 |

## 服务端接口

```
POST /api/auth                GET  /api/config
GET  /api/douban/recommend    POST /api/search-parallel
POST /api/detail              GET  /api/iptv
GET  /api/iptv/stream         POST /api/premium/types
POST /api/premium/category
```

接口契约以上游 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 的 `app/api/*/route.ts` 为准；
客户端参考的检索与分流逻辑见上游 `lib/utils/search.ts`、`lib/hooks/useSubscriptionSync.ts`、`lib/hooks/usePremiumHomePage.ts`。

---

## 构建

```bash
cd android-tv
./gradlew bumpVersion     # 版本号 +1（唯一来源 version.properties）
./gradlew assembleDebug
./gradlew lintDebug       # CI 也跑；历史 warning 不挡，新引入的错误会失败
```

- 版本号只改 `android-tv/version.properties`，`app/build.gradle.kts` 不再手写 `versionCode` / `versionName`
- 推任意分支 → CI 出 `kvideo-android-phone-apk-<版本>-r<run>` artifact
- 推 `v*` 标签 → 自动建 Release，资产名 `KVideo-Android-<versionName>.apk`
- Debug 包用 `.github/debug-keystore.b64` 里的固定证书签名（base64 解码成 `app/debug.keystore`），所以每个版本都能覆盖升级

## 目录

```
android-tv/
├── app/src/main/java/com/kvideo/tv/
│   ├── data/      Api（SSE 搜索、详情、IPTV）、Model、Store（本地存储）
│   ├── ui/        Home / Live / Detail / Player / PlayerChrome / Settings / Advanced
│   └── vm/        AppVm（单 ViewModel，状态与业务入口）
├── app/src/main/res/mipmap-*/  图标
└── version.properties          版本号唯一来源
```

## 许可

上游 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 以 MIT 发布，本项目沿用同一许可，见 [LICENSE](LICENSE)。
