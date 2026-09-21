# 懒得听 · 后台更新控制台

纯 Python 3.8+ 标准库实现（**零第三方依赖**）的一站式更新维护工具，
负责「懒得听」安卓项目的构建、版本管理与发布。

```
update-console/
├── update_console.py   # 主程序（控制台 + 自建更新服务器）
└── data/               # 首次运行自动生成：config.json / version.json / history.json / apks/ / admin_token.txt
```

## 命令一览

| 命令 | 说明 |
|------|------|
| `init` | 初始化控制台（生成配置与管理密钥） |
| `config` | 查看 / 修改配置（仓库、服务器地址、项目目录等） |
| `version` | 查看当前项目版本（读 `app/build.gradle`） |
| `bump` | 提升版本号（versionCode / versionName） |
| `build` | 调用 Gradle 构建 APK |
| `serve` | 启动自建更新服务器（版本 API + APK 下载 + 网页控制台） |
| `publish` | 发布新版本到自建更新服务器（本地 data/ 或远程） |
| `release` | 发布新版本到 GitHub Releases（需 `GITHUB_TOKEN`） |
| `status` | 查看本地 / 远程服务器 / GitHub 最新版本 |
| `history` | 查看发布历史 |

## 完整发布流程

环境要求：Python 3.8+；`build` 需要本机 JDK 17 + Android SDK（Gradle 8.2+ 或先
用 Android Studio 打开项目生成 `gradlew`）。

```bash
cd update-console

# 1. 初始化
python3 update_console.py init

# 2. 查看当前版本
python3 update_console.py version

# 3. 提升版本号（改 app/build.gradle）
python3 update_console.py bump --version-name 2.0 --version-code 2
python3 update_console.py bump --dry-run --version-name 2.1   # 预览

# 4. 构建 APK（默认 assembleDebug，产物可直接安装；正式发布请配置签名后加 --task assembleRelease）
python3 update_console.py build

# 5a. 自建服务器方式发布（推荐内网/自有服务器）
python3 update_console.py serve --port 8000                  # 终端 A：启动服务器
python3 update_console.py publish \
    --apk ../app/build/outputs/apk/debug/app-debug.apk \
    --changelog "1. 修复xxx；2. 新增xxx"                       # 终端 B：发布到本地 data/

# 5b. 远程服务器方式发布（发布到另一台机器的 update_console serve）
python3 update_console.py publish --server http://1.2.3.4:8000 \
    --apk ../app/build/outputs/apk/debug/app-debug.apk \
    --version-code 2 --version-name 2.0 --changelog "..."

# 5c. GitHub Releases 方式发布（公开分发，需要令牌）
GITHUB_TOKEN=ghp_xxx python3 update_console.py release \
    --apk ../app/build/outputs/apk/debug/app-debug.apk \
    --changelog "1. 修复xxx；2. 新增xxx" --changelog-file notes.md

# 6. 查看状态与历史
python3 update_console.py status --github
python3 update_console.py history
```

## App 端对接（自建服务器方式）

1. 修改 `app/src/main/java/com/landeting/utils/UpdateChecker.java` 中的
   `UPDATE_SERVER` 常量为你服务器的地址（含端口）。
2. 重新构建打包。
3. 之后 App 每次启动都会静默请求 `{server}/api/version`，
   发现 versionCode 高于本地时弹出更新对话框，下载后自动调起安装器。

## GitHub Releases 方式（App 端不用改）

如果你用 `release` 发布到 GitHub，用户可以直接从 Release 页面下载 APK。
若希望 App 也自动检测 GitHub 上的新版本，可以把 `UpdateChecker.UPDATE_SERVER`
指向一个返回同样 JSON 的接口（例如你的 GitHub Pages / 静态 JSON），
或把 `serve` 与 GitHub 发布结合使用（推荐）。

## 安全说明（必读）

1. **GitHub 令牌**：只通过环境变量 `GITHUB_TOKEN` 提供，绝不写入任何配置文件。
2. **管理密钥**：自建服务器的 `/api/publish` 接口带密钥校验。建议用环境变量设置强密钥：
   ```bash
   export UPDATE_ADMIN_TOKEN="足够长的随机字符串"
   ```
   否则首次 `init` 会自动生成并保存到 `data/admin_token.txt`（权限 600）。
3. **HTTPS**：生产环境请用 Nginx / Caddy 反向代理并启用 HTTPS，
   不要直接把 8000 端口暴露到公网。
4. **环境变量**：`UPDATE_DATA_DIR` 可改数据目录；`UPDATE_PORT` / `UPDATE_HOST` 可改监听。

## 配置项（`data/config.json`）

| 配置 | 说明 | 默认 |
|------|------|------|
| `app_name` | 应用名 | 懒得听 |
| `repo` | GitHub 仓库 owner/name | shuting52/yinyuebofangqi |
| `server` | 自建服务器地址 | http://127.0.0.1:8000 |
| `project_dir` | 项目根目录 | 控制台上一级目录 |
| `build_task` | Gradle 构建任务 | assembleDebug |
| `port` | serve 监听端口 | 8000 |

修改：`python3 update_console.py config --set repo=user/repo`
