---
AIGC:
  Label: "1"
  ContentProducer: 001191340100MA8QP9WJ5400000
  ProduceID: 1c2084d8-18f0-45cf-9649-3e56c0321668:art_59bccfa2ca8acfcdac3273c03fb228d8
  ReservedCode1: ""
  ContentPropagator: 001191340100MA8QP9WJ5400000
  PropagateID: 1c2084d8-18f0-45cf-9649-3e56c0321668:art_59bccfa2ca8acfcdac3273c03fb228d8
  ReservedCode2: ""
---
# 乐听音乐（MusicPlayer）

一个功能完整的安卓音乐播放器，界面与交互参考酷我音乐：**本地音乐 + 在线音乐 + 歌词滚动 + 后台播放 + 通知栏控制**。

## 功能清单

| 功能 | 说明 |
|------|------|
| 本地音乐 | 扫描设备全部音频（MediaStore），点击即播 |
| 在线音乐 | 通过酷我音乐公开接口搜索歌曲/歌手/专辑，在线试听 |
| 在线下载 | 搜索结果右侧下载按钮，保存到应用私有目录 |
| 滚动歌词 | 在线歌曲自动加载 LRC；本地歌曲读取同目录 `.lrc` 或内嵌歌词 |
| 后台播放 | 前台服务（mediaPlayback 类型），锁屏/切后台不断播 |
| 通知栏控制 | 播放/暂停、上一首、下一首、关闭 |
| 迷你播放条 | 列表底部常驻，显示当前歌曲与快捷控制 |
| 播放模式 | 列表循环 / 单曲循环 / 随机播放 |
| 全屏播放页 | 旋转封面动画、进度拖动、播放队列弹窗 |
| 自动更新 | 启动时静默检测新版本，弹窗提示下载安装（配套后台更新控制台） |

## 项目结构

```
app/src/main/
├── AndroidManifest.xml              # 权限、Activity、Service、Receiver 声明
├── java/com/loomy/musicplayer/
│   ├── MainActivity.java            # 主界面：本地音乐列表 + 迷你播放条
│   ├── SearchActivity.java          # 在线音乐搜索 + 下载
│   ├── PlayerActivity.java          # 全屏播放页：封面旋转 + 歌词滚动 + 播放模式
│   ├── PlaybackService.java         # 前台服务：通知栏 + 后台播放
│   ├── NotificationReceiver.java    # 通知栏按钮广播接收
│   ├── adapter/SongAdapter.java     # 通用歌曲列表适配器
│   ├── api/KuWoApi.java             # 酷我搜索/播放地址/歌词接口封装
│   ├── model/Song.java              # 歌曲模型（兼容本地/在线）
│   ├── model/LyricLine.java         # 歌词行模型
│   └── utils/
│       ├── PlayerManager.java       # 全局播放管理（单例，封装 MediaPlayer）
│       ├── LyricParser.java         # LRC 歌词解析
│       ├── MusicScanner.java        # 本地音乐扫描
│       └── HttpUtils.java           # HTTP 请求封装（OkHttp）
└── res/
    ├── layout/                      # 界面布局
    ├── drawable/                    # 图标与背景（全部矢量图）
    └── values/                      # 颜色 / 主题 / 字符串
```

## 环境要求

- Android Studio（Hedgehog 2023.1.1 或更新）
- JDK 17
- Gradle 8.2+（Android Studio 会自动下载）
- 真机或模拟器：Android 7.0（API 24）及以上

## 构建与运行

方式一：Android Studio（推荐）

1. 用 Android Studio 打开本项目根目录（`MusicPlayer/`）
2. 等待 Gradle 同步完成（首次会自动下载依赖，需要联网）
3. 连接设备后点击 ▶ Run，或构建 APK：`Build → Build APK(s)`

方式二：命令行构建

```bash
cd MusicPlayer
# 需要本机已安装 JDK 17 与 Android SDK（配置好 ANDROID_HOME）
# 若没有 gradlew，先用 Android Studio 打开项目同步一次（会自动生成），
# 或在本机安装 Gradle 8.x 后执行：
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. **首次启动**：会请求“读取媒体文件”权限（Android 13+ 还会请求通知权限），授权后自动扫描本地音乐。
2. **播放本地音乐**：点击列表任意歌曲，进入全屏播放页；底部迷你播放条常驻，随时返回列表。
3. **在线听歌**：右上角放大镜图标进入在线音乐，输入关键词搜索；点击结果即可在线播放（自动解析真实播放地址）。
4. **下载歌曲**：搜索结果的下载按钮，保存到 `Android/data/com.loomy.musicplayer/files/MusicPlayer/`。
5. **歌词**：播放页中部自动滚动高亮；在线歌曲自动加载，本地歌曲尝试读取同名 `.lrc` 文件或内嵌歌词。
6. **通知栏**：播放后下拉通知栏即可控制，锁屏界面同样可操作。

## 在线音乐接口说明

在线功能使用酷我音乐的公开接口（无需 API Key），代码集中在 `api/KuWoApi.java`：

| 用途 | 接口 |
|------|------|
| 搜索 | `search.kuwo.cn/r.s?all=关键词&ft=music&...` |
| 播放地址 | `antiserver.kuwo.cn/anti.s?type=convert_url3&rid=歌曲ID&...` |
| 歌词 | `m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=歌曲ID` |

> ⚠️ 公开接口可能随酷我调整而变动。若搜索/播放失效，只需修改 `KuWoApi.java` 顶部的 URL 模板，
> 或替换为其他可用音乐源（如网易云、QQ 音乐的开源接口），响应解析在 `search()` / `resolvePlayUrl()` / `fetchLyric()` 中，结构清晰、易替换。

## 技术要点

- **后台播放**：`PlaybackService` 以前台服务（`foregroundServiceType="mediaPlayback"`）常驻，
  通过静态注册的 `NotificationReceiver` 接收通知栏按钮广播并转发给 `PlayerManager`。
- **线程模型**：`PlayerManager` 单例，所有 `MediaPlayer` 操作经主线程 `Handler` 串行执行；
  网络请求（搜索、解析地址、歌词、下载）全部在子线程完成。
- **在线歌曲播放流程**：搜索得 RID → 解析真实 MP3 地址（防盗链带 Referer）→ `prepareAsync` 缓冲 → 播放。
- **歌词解析**：`LyricParser` 支持多时间戳、`[offset:]` 偏移、元信息行过滤，时间升序排序。
- **封面旋转**：`ObjectAnimator` 无限匀速旋转，暂停时动画同步暂停。
- **图片加载**：Glide（网络封面），本地歌曲使用矢量“黑胶唱片”占位图。
- **权限适配**：Android 13+ 用 `READ_MEDIA_AUDIO`，旧版本用 `READ_EXTERNAL_STORAGE`，均已处理。

## 版本更新（配套后台控制台）

本项目附带轻量**后台更新控制台**（`outputs/UpdateServer/`，纯 Python 零依赖）：

1. 部署：`python3 update_server.py init && python3 update_server.py serve`
2. 发布新版：`python3 update_server.py publish --server http://IP:8000 --token 密钥 --apk xxx.apk --version-code 2 --version-name 2.0 --changelog "..."`
3. App 端：把 `utils/UpdateChecker.java` 里的 `UPDATE_SERVER` 改为服务器地址，重新打包。
   之后 App 启动时若发现服务器 `versionCode` 更高，会自动提示更新并下载安装。

## 常见问题

- **搜索没结果**：多为酷我接口变动或网络问题，查看 Logcat 中 `KuWoApi` 的日志。
- **通知栏不显示**：Android 13+ 需要在系统设置中允许“乐听音乐”发送通知。
- **本地扫不到歌**：确认已授予存储权限；部分厂商系统（MIUI/HarmonyOS）需在设置中开启“允许访问所有文件”。

内容由AI生成
