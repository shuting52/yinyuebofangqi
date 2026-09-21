# 乐听音乐 · 后台更新控制台

一套轻量、零依赖（纯 Python 标准库）的 **App 版本更新服务器 + 命令行管理工具**，
与安卓端"乐听音乐"的自动更新功能（`UpdateChecker.java`）配套使用。

## 文件说明

```
UpdateServer/
├── update_server.py      # 主程序（服务器 + 命令行管理工具）
└── data/                 # 运行时自动生成：version.json / apks/ / admin_token.txt
```

## 快速开始

环境：Python 3.8+（服务器需能对外访问 8000 端口）。

```bash
# 1. 初始化数据目录（自动生成管理密钥，请妥善保管）
python3 update_server.py init

# 2. 启动服务器
python3 update_server.py serve --port 8000
```

启动后：
- 版本信息 API：`GET http://服务器IP:8000/api/version`
- 下载 APK：`GET http://服务器IP:8000/apk/文件名`
- 网页控制台：浏览器打开 `http://服务器IP:8000/` 可查看当前版本与下载链接

## 发布新版本

```bash
python3 update_server.py publish \
    --server http://服务器IP:8000 \
    --token 管理密钥 \
    --apk ./乐听音乐-v2.0.apk \
    --version-code 2 \
    --version-name 2.0 \
    --changelog "1. 新增XX功能；2. 修复XX问题"
```

发布成功后，手机上已安装的 App 下次启动（或点击检查更新）就会弹出更新提示，
下载完成后自动调起系统安装器。

查看当前线上版本：

```bash
python3 update_server.py status --server http://服务器IP:8000
```

## 安全配置（生产环境必读）

1. **管理密钥**：建议用环境变量设置强随机密钥，避免使用自动生成的弱密钥：
   ```bash
   export UPDATE_ADMIN_TOKEN="一个足够长的随机字符串"
   ```
2. **HTTPS**：不要直接暴露 8000 端口到公网，请用 Nginx / Caddy 做反向代理并启用 HTTPS，
   同时把 App 端 `UpdateChecker.UPDATE_SERVER` 改为你的 HTTPS 地址。
3. **防火墙**：只开放必要的端口，`/api/publish` 接口默认带 Token 校验，切勿泄露。

## App 端对接

1. 将 `app/src/main/java/com/loomy/musicplayer/utils/UpdateChecker.java` 中的
   `UPDATE_SERVER` 常量改为你的服务器地址。
2. 重新打包 APK 即完成对接。App 启动时会静默检测版本，
   服务器 `versionCode` 大于本地时弹出更新对话框。

## 其他命令

| 命令 | 说明 |
|------|------|
| `python3 update_server.py init` | 初始化数据目录与版本信息 |
| `python3 update_server.py serve --host 0.0.0.0 --port 8000` | 启动服务器 |
| `python3 update_server.py publish ...` | 发布新版本 |
| `python3 update_server.py status --server URL` | 查看线上版本 |

## 数据目录结构

```
data/
├── version.json        # 当前线上版本信息（App 的 /api/version 读取它）
├── admin_token.txt     # 自动生成的管理密钥（可用环境变量覆盖）
└── apks/               # 历史 APK 存档，文件名含版本号与 versionCode
```
