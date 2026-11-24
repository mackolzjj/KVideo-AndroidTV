# KVideo Android

**上游项目：[KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo)（MIT）** —— 本仓库是它的 Android 原生客户端，
服务端接口、搜索规则、权限口径、播放器行为都以该仓库源码为准；App 本身不含服务端，需要你自己的 KVideo 部署地址。

- 客户端实现：[Kotlin](https://kotlinlang.org/) + [Jetpack Compose](https://developer.android.com/jetpack/compose) + [Media3](https://developer.android.com/media/media3)
- 参考的源码：`lib/utils/search.ts`（检索过滤与打分）、`lib/hooks/useSubscriptionSync.ts`（源分流）、`lib/hooks/usePremiumHomePage.ts`（高级内容检索）、`app/api/*/route.ts`（接口契约）

[下载 2.0.52](https://github.com/mackolzjj/KVideo-AndroidTV/releases/latest) · Android 8.0 (API 26)+ · 包名 `com.kvideo.tv`

## 功能

**搜索**
- 多源并行（SSE 流式），边搜边出结果，350ms 防抖
- 官方检索逻辑：最小匹配（标题至少命中查询词里一对连续两字）、屏蔽分类（默认「伦理」）、相关度打分排序
- 三排筛选：源 / 类型 / 语言；「只看相关」「合并同名」两个开关，同名合并后可按实测延迟选线路

**播放**
- 自绘控制层：红色细进度条、`←10s 播放 10s→`、时间、剧集、倍速、锁定、全屏
- 手势：单击显隐、双击左右 ±10s、横滑跟手快进、左半屏上下滑亮度、右半屏上下滑音量
- 竖屏/横屏自适应；沉浸式全屏；换集不断片，退出重进自动续播
- 线路自动降级：直连失败 → 服务器代理 → 备用线路

**直播**
- 自己在「高级设置」里填 M3U 地址，App 不内置任何频道清单
- 频道线路失败自动跳下一个台（最多 3 次）

**首页**
- 半屏 hero，可横滑 3 张（纯 LazyRow，无实验 API）+ 继续观看（真实进度）+ 今日热播「换一批 ↻」
- 底栏选中态上浮；崩溃日志屏（崩过就开在最前面，可直接拷 /Android/data/com.kvideo.tv/files/last_crash.txt）


**内容与权限**
- 首页豆瓣推荐、继续观看（真实进度）、今日热播
- 片单：收藏 / 历史，历史显示「看到 mm:ss」
- 普通用户搜索**不含**成人 / 伦理源；这些源只在「高级内容」里，需管理员账号或高级密码解锁
- 高级内容支持分类切换与分页

**设置**
- 普通设置页只放：连接状态（只读）、清除历史、版本
- 隐藏入口：连点「版本」7 次 → 访问 PIN（4–8 位，加盐 SHA-256 存储）→ 高级设置
- 高级设置里：服务器地址 / 访问密码 / 直播源 / 播放代理（自动·直连·代理）/ 重新探活 / 屏蔽分类 / 高级内容 / 修改 PIN

## 服务端接口

`POST /api/auth` · `GET /api/config` · `GET /api/douban/recommend` · `POST /api/search-parallel` · `POST /api/detail` · `GET /api/iptv` · `GET /api/iptv/stream` · `POST /api/premium/types` · `POST /api/premium/category`

## 构建

```bash
cd android-tv
./gradlew assembleDebug
```

推 `v*` 标签触发 GitHub Actions 建 Release，资产名 `KVideo-Android-<versionName>.apk`。

## 许可

上游 [KuekHaoYang/KVideo](https://github.com/KuekHaoYang/KVideo) 以 MIT 发布，本项目沿用同一许可，见 [LICENSE](LICENSE)。
本仓库只包含客户端代码，不含上游服务端与任何影视内容。
