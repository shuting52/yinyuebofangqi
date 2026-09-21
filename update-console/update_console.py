#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
懒得听（Landeting）· 后台更新控制台
====================================
纯 Python 3.8+ 标准库实现，零第三方依赖。负责「懒得听」安卓项目的维护与版本更新，
覆盖一条完整链路：

    提版本号 → 构建 APK → 发布到自建更新服务器 → 发布到 GitHub Releases → 查看状态

命令总览：
  init      初始化控制台（生成 data/ 配置与管理密钥）
  config    查看 / 修改控制台配置
  version   查看当前项目版本（读取 app/build.gradle）
  bump      提升版本号（versionCode / versionName）
  build     调用 Gradle 构建 APK
  serve     启动自建更新服务器（版本 API + APK 下载 + 网页控制台）
  publish   发布新版本到自建更新服务器（本地 data/ 或远程服务器）
  release   发布新版本到 GitHub Releases（需要环境变量 GITHUB_TOKEN）
  status    查看本地 / 自建服务器 / GitHub 的最新版本信息
  history   查看发布历史

快速上手：
  1. python3 update_console.py init
  2. python3 update_console.py bump --version-name 2.0 --version-code 2
  3. python3 update_console.py build          # 需要本机 JDK 17 + Android SDK
  4. python3 update_console.py serve          # 启动自建更新服务器
  5. python3 update_console.py publish --apk app/build/outputs/apk/debug/app-debug.apk \
        --changelog "1. 修复xx；2. 新增xx"
  6. GITHUB_TOKEN=xxx python3 update_console.py release --apk ... --changelog "..."

安全说明：
  · GitHub 令牌只从环境变量 GITHUB_TOKEN 读取，绝不写入任何配置文件。
  · 自建服务器的管理密钥可从环境变量 UPDATE_ADMIN_TOKEN 指定，否则首次运行自动生成。
"""

import argparse
import datetime
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------- 常量

APP_NAME = "懒得听"
DEFAULT_REPO = "shuting52/yinyuebofangqi"
DEFAULT_SERVER = "http://127.0.0.1:8000"
DEFAULT_PORT = 8000
DEFAULT_BUILD_TASK = "assembleDebug"   # 默认打 debug 包（可直接安装）；正式发布请配置签名后改用 assembleRelease
VERSION_FILE = "version.json"
HISTORY_FILE = "history.json"
CONFIG_FILE = "config.json"
APK_DIR = "apks"
GITHUB_API = "https://api.github.com"
UA = "Landeting-UpdateConsole/1.0"


# ---------------------------------------------------------------- 基础路径

def console_dir():
    return os.path.dirname(os.path.abspath(__file__))


def data_dir():
    env = os.environ.get("UPDATE_DATA_DIR", "").strip()
    base = env if env else os.path.join(console_dir(), "data")
    os.makedirs(base, exist_ok=True)
    return base


def project_dir(cfg):
    p = cfg.get("project_dir") or os.path.dirname(console_dir())
    return os.path.abspath(os.path.expanduser(p))


def gradle_file(project):
    return os.path.join(project, "app", "build.gradle")


# ---------------------------------------------------------------- 配置

DEFAULT_CONFIG = {
    "app_name": APP_NAME,
    "repo": DEFAULT_REPO,            # GitHub 仓库 owner/name
    "server": DEFAULT_SERVER,        # 自建更新服务器地址
    "project_dir": "",               # 项目根目录（留空 = 控制台上一级目录）
    "build_task": DEFAULT_BUILD_TASK,
    "port": DEFAULT_PORT,
}


def load_config():
    path = os.path.join(data_dir(), CONFIG_FILE)
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            cfg.update(json.load(f))
    return cfg


def save_config(cfg):
    with open(os.path.join(data_dir(), CONFIG_FILE), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def admin_token():
    """管理密钥：优先环境变量，其次本地文件，最后自动生成。"""
    token = os.environ.get("UPDATE_ADMIN_TOKEN", "").strip()
    if token:
        return token
    token_file = os.path.join(data_dir(), "admin_token.txt")
    if os.path.exists(token_file):
        return open(token_file, encoding="utf-8").read().strip()
    token = "admin_" + os.urandom(16).hex()
    with open(token_file, "w", encoding="utf-8") as f:
        f.write(token)
    os.chmod(token_file, 0o600)
    print("⚠ 已自动生成管理密钥，请妥善保管：")
    print("   %s" % token_file)
    return token


# ---------------------------------------------------------------- 版本信息读写

def read_version(project=None, cfg=None):
    """读取 data/version.json（自建服务器当前线上版本）；没有则返回 None。"""
    path = os.path.join(data_dir(), VERSION_FILE)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return None


def save_version(v):
    with open(os.path.join(data_dir(), VERSION_FILE), "w", encoding="utf-8") as f:
        json.dump(v, f, ensure_ascii=False, indent=2)


def read_history():
    path = os.path.join(data_dir(), HISTORY_FILE)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return []


def save_history(records):
    with open(os.path.join(data_dir(), HISTORY_FILE), "w", encoding="utf-8") as f:
        json.dump(records, f, ensure_ascii=False, indent=2)


def append_history(rec):
    records = read_history()
    records.insert(0, rec)
    save_history(records)


# ---------------------------------------------------------------- 项目版本（app/build.gradle）

def gradle_version(project):
    """从 app/build.gradle 解析 versionCode / versionName。"""
    path = gradle_file(project)
    if not os.path.exists(path):
        return None
    text = open(path, encoding="utf-8").read()
    m_code = re.search(r"versionCode\s+(\d+)", text)
    m_name = re.search(r'versionName\s+"([^"]+)"', text)
    if not (m_code and m_name):
        return None
    return {"versionCode": int(m_code.group(1)), "versionName": m_name.group(1)}


def bump_version(project, version_code=None, version_name=None, dry_run=False):
    """原地修改 app/build.gradle 的版本号。"""
    path = gradle_file(project)
    if not os.path.exists(path):
        print("✗ 找不到 %s" % path)
        sys.exit(1)
    text = open(path, encoding="utf-8").read()
    old = gradle_version(project)
    new_text = text
    new_code = old["versionCode"]
    new_name = old["versionName"]
    if version_code is not None:
        new_code = version_code
        new_text = re.sub(r"(versionCode\s+)\d+", r"\g<1>%d" % version_code, new_text)
    if version_name is not None:
        new_name = version_name
        new_text = re.sub(r'(versionName\s+")[^"]*(")', r"\g<1>%s\g<2>" % version_name, new_text)
    if dry_run:
        print("（dry-run）将修改 %s：" % path)
        print("  versionCode : %s → %s" % (old["versionCode"], new_code))
        print("  versionName : %s → %s" % (old["versionName"], new_name))
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(new_text)
    print("✓ 版本号已更新：%s (code %s)" % (new_name, new_code))


# ---------------------------------------------------------------- 构建

def find_apk(project, variant=None):
    """在构建输出目录里找最新的 APK；variant: debug / release。"""
    candidates = []
    for root, _dirs, files in os.walk(os.path.join(project, "app", "build", "outputs", "apk")):
        for f in files:
            if f.endswith(".apk"):
                if variant and ("/" + variant + "/") not in root.replace("\\", "/") + "/":
                    continue
                candidates.append(os.path.join(root, f))
    if not candidates:
        return None
    return max(candidates, key=os.path.getmtime)


def cmd_build(args, cfg):
    project = project_dir(cfg)
    if not os.path.exists(gradle_file(project)):
        print("✗ 找不到项目构建文件：%s" % gradle_file(project))
        print("  可用 `config --set project_dir=...` 指定项目根目录")
        sys.exit(1)
    task = args.task or cfg.get("build_task") or DEFAULT_BUILD_TASK
    variant = args.variant or ("release" if "release" in task else "debug")
    gradlew = os.path.join(project, "gradlew")
    if os.path.exists(gradlew):
        cmd = [gradlew, task]
    else:
        cmd = ["gradle", task]
    print("正在构建：%s（%s）" % (" ".join(cmd), project))
    print("=" * 56)
    try:
        subprocess.run(cmd, cwd=project, check=True)
    except FileNotFoundError:
        print("✗ 找不到 Gradle。请先安装 Gradle 8.2+（或先用 Android Studio 打开项目生成 gradlew）")
        sys.exit(1)
    except subprocess.CalledProcessError:
        print("✗ 构建失败，请查看上方日志")
        sys.exit(1)
    apk = find_apk(project, variant)
    if not apk:
        print("✗ 构建完成但未找到 APK 产物")
        sys.exit(1)
    size_mb = os.path.getsize(apk) / 1024 / 1024
    print("=" * 56)
    print("✓ 构建成功：%s（%.1f MB）" % (apk, size_mb))
    print("  可用 --apk 指定该文件继续 publish / release")


# ---------------------------------------------------------------- HTTP 基础

def http_request(url, method="GET", data=None, headers=None, timeout=60):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("User-Agent", UA)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except urllib.error.URLError as e:
        # 网络不可达 / 代理拦截等：status=0 表示网络错误
        return 0, str(getattr(e, "reason", e)).encode("utf-8", "ignore")


def json_response(body, default=None):
    try:
        return json.loads(body.decode("utf-8"))
    except Exception:
        return default


# ---------------------------------------------------------------- 命令：init / config / version

def cmd_init(args, cfg):
    cfg = load_config()
    save_config(cfg)
    os.makedirs(os.path.join(data_dir(), APK_DIR), exist_ok=True)
    token = admin_token()
    v = read_version()
    print("✓ 初始化完成")
    print("  控制台目录 : %s" % console_dir())
    print("  数据目录   : %s" % data_dir())
    print("  应用名     : %s" % cfg["app_name"])
    print("  项目目录   : %s" % project_dir(cfg))
    print("  管理密钥   : %s" % token)
    if v:
        print("  当前线上   : v%s (code %s)" % (v["versionName"], v["versionCode"]))
    print()
    print("下一步：")
    print("  python3 update_console.py version   # 查看项目版本")
    print("  python3 update_console.py build     # 构建 APK")
    print("  python3 update_console.py serve     # 启动自建更新服务器")


def cmd_config(args, cfg):
    if args.set:
        key, _, value = args.set.partition("=")
        key = key.strip()
        if key not in DEFAULT_CONFIG and key not in cfg:
            print("✗ 未知配置项：%s" % key)
            sys.exit(1)
        cfg[key] = value.strip()
        save_config(cfg)
        print("✓ 已设置 %s = %s" % (key, cfg[key]))
    print("当前配置：")
    for k, v in cfg.items():
        print("  %-12s: %s" % (k, v))


def cmd_version(args, cfg):
    project = project_dir(cfg)
    gv = gradle_version(project)
    if not gv:
        print("✗ 无法解析 %s（请确认 --set project_dir 指向项目根目录）" % gradle_file(project))
        sys.exit(1)
    print("当前项目版本（app/build.gradle）：")
    print("  versionName : %s" % gv["versionName"])
    print("  versionCode : %s" % gv["versionCode"])
    v = read_version()
    if v:
        print("自建服务器线上版本（data/version.json）：")
        print("  %s (code %s)" % (v.get("versionName"), v.get("versionCode")))


def cmd_bump(args, cfg):
    project = project_dir(cfg)
    if args.version_code is None and args.version_name is None:
        print("✗ 请至少指定 --version-code 或 --version-name 之一")
        sys.exit(1)
    bump_version(project, args.version_code, args.version_name, dry_run=args.dry_run)


# ---------------------------------------------------------------- 命令：serve（自建更新服务器）

class UpdateHandler(http.server.BaseHTTPRequestHandler):
    server_version = "LandetingUpdateServer/1.0"

    def log_message(self, fmt, *args):
        sys.stdout.write("[%s] %s\n" % (
            datetime.datetime.now().strftime("%H:%M:%S"), fmt % args))
        sys.stdout.flush()

    def _load_version(self):
        v = read_version()
        return v or {
            "appName": APP_NAME,
            "versionCode": 0,
            "versionName": "0.0",
            "apkFile": "",
            "url": "",
            "changelog": "尚未发布任何版本",
            "releaseDate": datetime.date.today().isoformat(),
            "size": 0,
        }

    def _send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            self._serve_dashboard()
        elif path == "/api/version":
            self._send_json(self._load_version())
        elif path.startswith("/apk/"):
            self._serve_apk(path)
        else:
            self.send_error(404, "Not Found")

    def _serve_apk(self, path):
        name = os.path.basename(path)
        filepath = os.path.join(data_dir(), APK_DIR, name)
        if not os.path.exists(filepath):
            self.send_error(404, "APK 不存在")
            return
        size = os.path.getsize(filepath)
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Disposition", "attachment; filename=\"%s\"" % name)
        self.end_headers()
        with open(filepath, "rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def _serve_dashboard(self):
        cfg = load_config()
        v = self._load_version()
        name = v.get("versionName", "?")
        code = v.get("versionCode", "?")
        changelog = (v.get("changelog", "") or "").replace("<", "&lt;").replace("\n", "<br>")
        date = v.get("releaseDate", "")
        size_mb = v.get("size", 0) / 1024 / 1024
        url = v.get("url", "")
        app = cfg.get("app_name", APP_NAME)
        dl = ('<a class="dl" href="%s">下载最新 APK</a>' % url) if url else ""
        html = f"""<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{app} · 更新控制台</title><style>
body{{font-family:system-ui,sans-serif;background:#f4f6fb;margin:0;padding:40px 16px;color:#333}}
.card{{max-width:680px;margin:0 auto;background:#fff;border-radius:14px;padding:28px 32px;box-shadow:0 6px 24px rgba(60,80,140,.12)}}
h1{{font-size:22px;color:#3F51B5;margin:0 0 6px}}.sub{{color:#888;font-size:13px;margin-bottom:22px}}
table{{width:100%;border-collapse:collapse;font-size:14px}}
td{{padding:9px 4px;border-bottom:1px solid #eef1f7;vertical-align:top}}
td:first-child{{color:#888;width:110px}}
.tag{{display:inline-block;background:#e8f0fe;color:#3F51B5;border-radius:20px;padding:2px 12px;font-size:13px}}
.dl{{display:inline-block;margin-top:18px;background:#3F51B5;color:#fff;text-decoration:none;padding:10px 22px;border-radius:8px;font-size:14px}}
a.dl:hover{{background:#303F9F}}.hint{{margin-top:26px;font-size:12px;color:#aaa;line-height:1.8}}
</style></head><body><div class="card">
<h1>{app} · 后台更新控制台</h1>
<div class="sub">版本 API：<code>/api/version</code> · 发布：命令行 <code>publish</code> 或 <code>release</code></div>
<table><tr><td>当前版本</td><td><span class="tag">v{name} (code {code})</span></td></tr>
<tr><td>更新说明</td><td>{changelog}</td></tr>
<tr><td>发布时间</td><td>{date}</td></tr>
<tr><td>APK 大小</td><td>{size_mb:.1f} MB</td></tr></table>
{dl}
<div class="hint">安全提示：生产环境请置于 HTTPS 反向代理（Nginx/Caddy）之后，并通过环境变量 UPDATE_ADMIN_TOKEN 设置强管理密钥。</div>
</div></body></html>"""
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---------- POST ----------
    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/publish":
            self._handle_publish()
        else:
            self.send_error(404, "Not Found")

    def _handle_publish(self):
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        given = query.get("token", [""])[0]
        if given != admin_token():
            self._send_json({"error": "管理密钥错误"}, 403)
            return
        try:
            version_code = int(query.get("versionCode", ["0"])[0])
            version_name = query.get("versionName", [""])[0]
            changelog = query.get("changelog", [""])[0]
        except ValueError:
            self._send_json({"error": "参数格式错误"}, 400)
            return
        if version_code <= 0 or not version_name:
            self._send_json({"error": "缺少 versionCode / versionName"}, 400)
            return
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            self._send_json({"error": "缺少 APK 数据"}, 400)
            return
        raw = self.rfile.read(length)
        apk_dir = os.path.join(data_dir(), APK_DIR)
        os.makedirs(apk_dir, exist_ok=True)
        apk_name = "app-v%s-code%s.apk" % (version_name, version_code)
        with open(os.path.join(apk_dir, apk_name), "wb") as f:
            f.write(raw)
        v = self._load_version()
        v.update({
            "versionCode": version_code,
            "versionName": version_name,
            "apkFile": apk_name,
            "url": "/apk/%s" % apk_name,
            "changelog": changelog,
            "releaseDate": datetime.date.today().isoformat(),
            "size": len(raw),
        })
        save_version(v)
        print("✓ 新版本已发布: v%s (code %s), %.1f MB" % (
            version_name, version_code, len(raw) / 1024 / 1024))
        self._send_json(v)


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def cmd_serve(args, cfg):
    port = args.port or int(os.environ.get("UPDATE_PORT", cfg.get("port", DEFAULT_PORT)))
    host = args.host or os.environ.get("UPDATE_HOST", "0.0.0.0")
    v = read_version() or {}
    print("=" * 58)
    print("  %s 后台更新控制台 已启动" % cfg.get("app_name", APP_NAME))
    print("  服务器     : http://%s:%d" % (host, port))
    print("  版本 API   : http://%s:%d/api/version" % (host, port))
    print("  控制台页   : http://%s:%d/" % (host, port))
    print("  当前线上   : v%s (code %s)" % (v.get("versionName", "?"), v.get("versionCode", "?")))
    print("  管理密钥   : %s" % admin_token())
    print("  发布命令   : python3 update_console.py publish --server http://%s:%d --apk <apk> \\" % (host, port))
    print("               --version-code %s --version-name %s --changelog \"...\"" % (
        (v.get("versionCode") or 1) + 1 if v.get("versionCode") else 1, (v.get("versionName") or "1.0")))
    print("=" * 58)
    server = ThreadingServer((host, port), UpdateHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


# ---------------------------------------------------------------- 命令：publish（自建服务器）

def cmd_publish(args, cfg):
    if not args.apk or not os.path.exists(args.apk):
        print("✗ APK 文件不存在：%s" % args.apk)
        sys.exit(1)
    if args.version_code is None:
        gv = gradle_version(project_dir(cfg))
        args.version_code = gv["versionCode"] if gv else 1
    if not args.version_name:
        gv = gradle_version(project_dir(cfg))
        args.version_name = gv["versionName"] if gv else "1.0"
    size = os.path.getsize(args.apk)
    changelog = args.changelog or read_changelog(args)

    # 本地发布：写入 data/
    if not args.server and not args.remote:
        apk_dir = os.path.join(data_dir(), APK_DIR)
        os.makedirs(apk_dir, exist_ok=True)
        apk_name = "app-v%s-code%s.apk" % (args.version_name, args.version_code)
        dst = os.path.join(apk_dir, apk_name)
        shutil.copyfile(args.apk, dst)
        v = {
            "appName": cfg.get("app_name", APP_NAME),
            "versionCode": args.version_code,
            "versionName": args.version_name,
            "apkFile": apk_name,
            "url": "/apk/%s" % apk_name,
            "changelog": changelog,
            "releaseDate": datetime.date.today().isoformat(),
            "size": size,
        }
        save_version(v)
        append_history(dict(v, target="本地服务器", apkPath=dst))
        print("✓ 已发布到本地 data/：")
        print("  %s（%.1f MB）" % (dst, size / 1024 / 1024))
        print("  现在运行 `serve` 即可让 App 检测到新版本。")
        return

    # 远程发布：POST 到服务器
    server = (args.server or cfg.get("server", DEFAULT_SERVER)).rstrip("/")
    token = args.token or admin_token()
    url = "%s/api/publish?token=%s&versionCode=%s&versionName=%s&changelog=%s" % (
        server,
        urllib.parse.quote(token, safe=""),
        args.version_code,
        urllib.parse.quote(args.version_name, safe=""),
        urllib.parse.quote(changelog, safe=""),
    )
    print("正在上传 APK 到 %s（%.1f MB）..." % (server, size / 1024 / 1024))
    with open(args.apk, "rb") as f:
        data = f.read()
    status, body = http_request(url, method="POST", data=data, timeout=600, headers={
        "Content-Type": "application/octet-stream",
        "X-Apk-Filename": urllib.parse.quote(os.path.basename(args.apk), safe=""),
    })
    if status != 200:
        if status == 0:
            print("✗ 网络连接失败（%s），请检查服务器地址与网络" % body.decode("utf-8", "ignore"))
        else:
            print("✗ 发布失败 (HTTP %s): %s" % (status, body.decode("utf-8", "ignore")))
        sys.exit(1)
    info = json_response(body)
    append_history(dict(info, target="远程服务器 " + server))
    print("✓ 发布成功：")
    print(json.dumps(info, ensure_ascii=False, indent=2))


def read_changelog(args):
    if getattr(args, "changelog_file", None):
        with open(args.changelog_file, encoding="utf-8") as f:
            return f.read().strip()
    return ""


# ---------------------------------------------------------------- 命令：release（GitHub Releases）

def cmd_release(args, cfg):
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("✗ 未提供 GitHub 令牌。请通过环境变量提供：")
        print("   GITHUB_TOKEN=xxx python3 update_console.py release ...")
        sys.exit(1)
    repo = args.repo or cfg.get("repo", DEFAULT_REPO)
    if args.apk:
        if not os.path.exists(args.apk):
            print("✗ APK 文件不存在：%s" % args.apk)
            sys.exit(1)
        apk = args.apk
    else:
        apk = find_apk(project_dir(cfg))
        if not apk:
            print("✗ 未找到构建产物，请先运行 `build` 或指定 --apk")
            sys.exit(1)
    gv = gradle_version(project_dir(cfg)) or {}
    version_name = args.version_name or gv.get("versionName", "1.0")
    version_code = args.version_code if args.version_code is not None else gv.get("versionCode", 1)
    tag = args.tag or ("v" + version_name)
    changelog = args.changelog or read_changelog(args) or "懒得听 v%s 发布" % version_name
    size_mb = os.path.getsize(apk) / 1024 / 1024

    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
    }
    # 1) 创建 Release
    payload = {
        "tag_name": tag,
        "name": "%s v%s" % (cfg.get("app_name", APP_NAME), version_name),
        "body": changelog,
        "draft": bool(args.draft),
        "prerelease": bool(args.prerelease),
    }
    print("正在创建 GitHub Release：%s (%s)..." % (repo, tag))
    status, body = http_request(
        "%s/repos/%s/releases" % (GITHUB_API, repo),
        method="POST", data=json.dumps(payload).encode("utf-8"), headers=headers)
    if status not in (200, 201):
        err = json_response(body, {})
        if status == 0:
            print("✗ 网络连接失败（%s），请检查网络 / 代理设置" % body.decode("utf-8", "ignore"))
        else:
            print("✗ 创建 Release 失败 (HTTP %s): %s" % (status, err.get("message", body.decode("utf-8", "ignore"))))
        sys.exit(1)
    release = json_response(body, {})
    release_id = release.get("id")
    print("✓ Release 已创建：%s" % release.get("html_url", tag))

    # 2) 上传 APK 资产
    fname = os.path.basename(apk)
    upload_url = "%s/repos/%s/releases/%s/assets?name=%s" % (
        GITHUB_API, repo, release_id, urllib.parse.quote(fname))
    print("正在上传 APK（%.1f MB）..." % size_mb)
    with open(apk, "rb") as f:
        data = f.read()
    status, body = http_request(upload_url, method="POST", data=data, timeout=600, headers=dict(
        headers, **{"Content-Type": "application/vnd.android.package-archive"}))
    if status not in (200, 201):
        err = json_response(body, {})
        if status == 0:
            print("✗ 网络连接失败（%s），请检查网络 / 代理设置" % body.decode("utf-8", "ignore"))
        else:
            print("✗ 上传 APK 失败 (HTTP %s): %s" % (status, err.get("message", body.decode("utf-8", "ignore"))))
        sys.exit(1)
    asset = json_response(body, {})
    print("✓ APK 已上传：%s" % asset.get("browser_download_url", fname))

    # 3) 记录历史
    rec = {
        "versionCode": version_code,
        "versionName": version_name,
        "tag": tag,
        "apkFile": fname,
        "changelog": changelog,
        "releaseDate": datetime.date.today().isoformat(),
        "size": os.path.getsize(apk),
        "target": "GitHub Releases (%s)" % repo,
        "url": release.get("html_url", ""),
    }
    append_history(rec)
    print("✓ 发布完成：%s" % release.get("html_url", ""))


# ---------------------------------------------------------------- 命令：status / history

def cmd_status(args, cfg):
    gv = gradle_version(project_dir(cfg))
    if gv:
        print("项目版本（app/build.gradle）：v%s (code %s)" % (gv["versionName"], gv["versionCode"]))
    v = read_version()
    if v:
        print("本地服务器版本（data/version.json）：v%s (code %s) %s" % (
            v.get("versionName"), v.get("versionCode"), v.get("releaseDate", "")))
    if args.server or (not args.github and cfg.get("server")):
        server = (args.server or cfg.get("server")).rstrip("/")
        try:
            status, body = http_request(server + "/api/version", timeout=10)
            if status == 200:
                info = json_response(body)
                print("远程服务器 %s：v%s (code %s)" % (server, info.get("versionName"), info.get("versionCode")))
            else:
                print("远程服务器 %s：不可用 (HTTP %s)" % (server, status))
        except Exception as e:
            print("远程服务器 %s：不可用（%s）" % (server, e))
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if args.github and token and cfg.get("repo"):
        status, body = http_request(
            "%s/repos/%s/releases/latest" % (GITHUB_API, cfg["repo"]), timeout=15,
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"})
        if status == 200:
            rel = json_response(body, {})
            print("GitHub 最新 Release：%s (%s)" % (rel.get("tag_name"), rel.get("published_at", "")))
        else:
            print("GitHub：未找到 Release 或无权限（HTTP %s）" % status)
    elif args.github:
        print("GitHub：需要 GITHUB_TOKEN 环境变量")


def cmd_history(args, cfg):
    records = read_history()
    if not records:
        print("（暂无发布记录）")
        return
    print("共 %d 条发布记录（最近在前）：" % len(records))
    for i, r in enumerate(records, 1):
        print("  %d. v%s (code %s) · %s · %s" % (
            i, r.get("versionName", "?"), r.get("versionCode", "?"),
            r.get("releaseDate", ""), r.get("target", "?")))
        if r.get("changelog"):
            print("      更新说明：%s" % r["changelog"].replace("\n", " / "))


# ---------------------------------------------------------------- 入口

def main():
    parser = argparse.ArgumentParser(
        description="懒得听 · 后台更新控制台（构建 / 版本 / 发布 / 服务）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("安全说明：")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("init", help="初始化控制台（生成配置与管理密钥）")
    p.set_defaults(func=cmd_init)

    p = sub.add_parser("config", help="查看 / 修改配置")
    p.add_argument("--set", metavar="key=value", help="修改配置项，如 --set repo=user/repo")
    p.set_defaults(func=cmd_config)

    p = sub.add_parser("version", help="查看当前项目版本")
    p.set_defaults(func=cmd_version)

    p = sub.add_parser("bump", help="提升版本号")
    p.add_argument("--version-code", type=int, help="新 versionCode（正整数）")
    p.add_argument("--version-name", help="新 versionName，如 2.0")
    p.add_argument("--dry-run", action="store_true", help="只预览不修改")
    p.set_defaults(func=cmd_bump)

    p = sub.add_parser("build", help="调用 Gradle 构建 APK")
    p.add_argument("--task", help="Gradle 任务，默认 %s" % DEFAULT_BUILD_TASK)
    p.add_argument("--variant", choices=["debug", "release"], help="APK 输出目录（默认按任务推断）")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("serve", help="启动自建更新服务器")
    p.add_argument("--host", help="监听地址，默认 0.0.0.0")
    p.add_argument("--port", type=int, help="监听端口，默认 %s" % DEFAULT_PORT)
    p.set_defaults(func=cmd_serve)

    p = sub.add_parser("publish", help="发布新版本到自建更新服务器")
    p.add_argument("--apk", help="APK 文件路径（必填）")
    p.add_argument("--server", help="远程服务器地址；不填则发布到本地 data/")
    p.add_argument("--token", help="管理密钥（默认取本地配置）")
    p.add_argument("--version-code", type=int, help="versionCode（默认取 build.gradle）")
    p.add_argument("--version-name", help="versionName（默认取 build.gradle）")
    p.add_argument("--changelog", help="更新说明")
    p.add_argument("--changelog-file", help="从文件读取更新说明")
    p.add_argument("--remote", action="store_true", help="强制远程发布（用 config 里的 server）")
    p.set_defaults(func=cmd_publish)

    p = sub.add_parser("release", help="发布到 GitHub Releases（需 GITHUB_TOKEN 环境变量）")
    p.add_argument("--apk", help="APK 文件路径（不填则用最新构建产物）")
    p.add_argument("--repo", help="GitHub 仓库 owner/name（默认取配置）")
    p.add_argument("--version-code", type=int, help="versionCode（默认取 build.gradle）")
    p.add_argument("--version-name", help="versionName（默认取 build.gradle）")
    p.add_argument("--tag", help="Git tag（默认 v+versionName）")
    p.add_argument("--changelog", help="更新说明")
    p.add_argument("--changelog-file", help="从文件读取更新说明")
    p.add_argument("--draft", action="store_true", help="创建为草稿")
    p.add_argument("--prerelease", action="store_true", help="标记为预发布")
    p.set_defaults(func=cmd_release)

    p = sub.add_parser("status", help="查看本地 / 远程服务器 / GitHub 最新版本")
    p.add_argument("--server", help="远程服务器地址")
    p.add_argument("--github", action="store_true", help="同时查询 GitHub 最新 Release")
    p.set_defaults(func=cmd_status)

    p = sub.add_parser("history", help="查看发布历史")
    p.set_defaults(func=cmd_history)

    args = parser.parse_args()
    cfg = load_config()
    args.func(args, cfg)


if __name__ == "__main__":
    main()
