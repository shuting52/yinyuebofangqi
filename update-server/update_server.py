#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
乐听音乐 · 后台更新控制台（Update Server）
=============================================
纯 Python 标准库实现，无需安装任何第三方依赖（Python 3.8+）。

功能：
  1. serve   —— 启动更新服务器（提供版本信息 API + APK 下载 + 网页控制台）
  2. publish —— 命令行发布新版本（上传 APK + 更新版本信息）
  3. status  —— 查看当前线上版本信息
  4. init    —— 初始化数据目录

用法示例：
  # 1. 初始化（生成数据目录与默认 version.json）
  python3 update_server.py init

  # 2. 启动服务器（默认 0.0.0.0:8000）
  python3 update_server.py serve --port 8000

  # 3. 发布新版本（App 内的"检查更新"会立刻发现新版本）
  python3 update_server.py publish \
      --server http://你的服务器IP:8000 \
      --token 你的管理密钥 \
      --apk 乐听音乐-v2.0.apk \
      --version-code 2 --version-name 2.0 \
      --changelog "新增XX功能，修复XX问题"

App 端更新检测地址：GET {server}/api/version
"""
import argparse
import datetime
import http.server
import json
import os
import shutil
import socketserver
import sys
import urllib.parse
import urllib.request

# ---------------- 常量与默认配置 ----------------

APP_NAME = "乐听音乐"
DEFAULT_PORT = 8000
VERSION_FILE = "version.json"
APK_DIR = "apks"

DEFAULT_VERSION = {
    "appName": APP_NAME,
    "versionCode": 1,
    "versionName": "1.0",
    "apkFile": "",
    "url": "",
    "changelog": "首个版本",
    "releaseDate": datetime.date.today().isoformat(),
    "size": 0,
}


# ---------------- 配置管理 ----------------

def data_dir():
    """数据目录：优先环境变量，其次脚本所在目录下的 data/"""
    env = os.environ.get("UPDATE_DATA_DIR")
    if env:
        os.makedirs(env, exist_ok=True)
        return env
    d = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
    os.makedirs(d, exist_ok=True)
    return d


def admin_token():
    """管理密钥：优先环境变量，其次提示用户设置（serve/publish 时校验）"""
    token = os.environ.get("UPDATE_ADMIN_TOKEN", "").strip()
    if token:
        return token
    # 首次运行自动生成，并保存到 data/admin_token.txt（请妥善保管）
    token_file = os.path.join(data_dir(), "admin_token.txt")
    if os.path.exists(token_file):
        return open(token_file, encoding="utf-8").read().strip()
    token = "admin_" + os.urandom(16).hex()
    with open(token_file, "w", encoding="utf-8") as f:
        f.write(token)
    os.chmod(token_file, 0o600)
    print("⚠ 已自动生成管理密钥并保存到: %s" % token_file)
    return token


def load_version():
    path = os.path.join(data_dir(), VERSION_FILE)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    return dict(DEFAULT_VERSION)


def save_version(v):
    with open(os.path.join(data_dir(), VERSION_FILE), "w", encoding="utf-8") as f:
        json.dump(v, f, ensure_ascii=False, indent=2)


# ---------------- 命令：init ----------------

def cmd_init(_args):
    v = load_version()
    save_version(v)
    os.makedirs(os.path.join(data_dir(), APK_DIR), exist_ok=True)
    print("✓ 初始化完成")
    print("  数据目录 : %s" % data_dir())
    print("  管理密钥 : %s" % admin_token())
    print("  当前版本 : v%s (code %s)" % (v["versionName"], v["versionCode"]))


# ---------------- 命令：publish ----------------

def cmd_publish(args):
    if not args.apk or not os.path.exists(args.apk):
        print("✗ APK 文件不存在: %s" % args.apk)
        sys.exit(1)
    if not args.version_code or not args.version_name:
        print("✗ 必须指定 --version-code 和 --version-name")
        sys.exit(1)
    server = args.server.rstrip("/")
    url = "%s/api/publish?token=%s&versionCode=%s&versionName=%s&changelog=%s" % (
        server,
        urllib.parse.quote(args.token, safe=""),
        args.version_code,
        urllib.parse.quote(args.version_name, safe=""),
        urllib.parse.quote(args.changelog or "", safe=""),
    )
    size = os.path.getsize(args.apk)
    print("正在上传 APK（%.1f MB）..." % (size / 1024 / 1024))
    with open(args.apk, "rb") as f:
        data = f.read()
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/octet-stream")
    req.add_header("X-Apk-Filename", urllib.parse.quote(os.path.basename(args.apk), safe=""))
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            body = resp.read().decode("utf-8")
        print("✓ 发布成功：")
        print(json.dumps(json.loads(body), ensure_ascii=False, indent=2))
    except urllib.error.HTTPError as e:
        print("✗ 发布失败 (HTTP %s): %s" % (e.code, e.read().decode("utf-8", "ignore")))
        sys.exit(1)
    except Exception as e:
        print("✗ 发布失败: %s" % e)
        sys.exit(1)


# ---------------- 命令：status ----------------

def cmd_status(args):
    server = args.server.rstrip("/")
    try:
        with urllib.request.urlopen(server + "/api/version", timeout=15) as resp:
            info = json.loads(resp.read().decode("utf-8"))
        print("线上版本信息：")
        for k, v in info.items():
            print("  %-12s: %s" % (k, v))
    except Exception as e:
        print("✗ 获取失败: %s" % e)
        sys.exit(1)


# ---------------- HTTP 服务端 ----------------

class UpdateHandler(http.server.BaseHTTPRequestHandler):
    server_version = "MusicUpdateServer/1.0"

    def log_message(self, fmt, *args):  # 精简日志
        sys.stdout.write("[%s] %s\n" % (
            datetime.datetime.now().strftime("%H:%M:%S"), fmt % args))
        sys.stdout.flush()

    # ---------- GET ----------
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            self._serve_dashboard()
        elif path == "/api/version":
            self._serve_version()
        elif path.startswith("/apk/"):
            self._serve_apk(path)
        else:
            self.send_error(404, "Not Found")

    def _serve_version(self):
        v = load_version()
        body = json.dumps(v, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

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
        self.send_header("Content-Disposition",
                         "attachment; filename=\"%s\"" % name)
        self.end_headers()
        with open(filepath, "rb") as f:
            shutil.copyfileobj(f, self.wfile)

    def _serve_dashboard(self):
        v = load_version()
        name = v.get("versionName", "?")
        code = v.get("versionCode", "?")
        changelog = (v.get("changelog", "") or "").replace("<", "&lt;")
        date = v.get("releaseDate", "")
        size_mb = v.get("size", 0) / 1024 / 1024
        url = v.get("url", "") if v.get("url") else ""

        dl_link = ("<a class=\"dl\" href=\"%s\">下载最新 APK</a>" % url) if url else ""
        html = (
            "<!DOCTYPE html>\n<html lang=\"zh\"><head><meta charset=\"utf-8\">"
            "<title>%s · 更新控制台</title><style>" % APP_NAME
            + "body{font-family:system-ui,sans-serif;background:#f4f6fb;margin:0;padding:40px 16px;color:#333}"
            + ".card{max-width:680px;margin:0 auto;background:#fff;border-radius:14px;padding:28px 32px;box-shadow:0 6px 24px rgba(60,80,140,.12)}"
            + "h1{font-size:22px;color:#3F51B5;margin:0 0 6px}"
            + ".sub{color:#888;font-size:13px;margin-bottom:22px}"
            + "table{width:100%;border-collapse:collapse;font-size:14px}"
            + "td{padding:9px 4px;border-bottom:1px solid #eef1f7}"
            + "td:first-child{color:#888;width:120px}"
            + ".tag{display:inline-block;background:#e8f0fe;color:#3F51B5;border-radius:20px;padding:2px 12px;font-size:13px}"
            + ".dl{display:inline-block;margin-top:18px;background:#3F51B5;color:#fff;text-decoration:none;padding:10px 22px;border-radius:8px;font-size:14px}"
            + "a.dl:hover{background:#303F9F}"
            + ".hint{margin-top:26px;font-size:12px;color:#aaa;line-height:1.8}"
            + "</style></head><body><div class=\"card\">"
            + "<h1>%s · 后台更新控制台</h1>" % APP_NAME
            + "<div class=\"sub\">版本信息 API：<code>/api/version</code> · 管理发布：命令行工具 <code>publish</code></div>"
            + "<table><tr><td>当前版本</td><td><span class=\"tag\">v%s (code %s)</span></td></tr>" % (name, code)
            + "<tr><td>更新说明</td><td>%s</td></tr>" % changelog
            + "<tr><td>发布时间</td><td>%s</td></tr>" % date
            + "<tr><td>APK 大小</td><td>%.1f MB</td></tr></table>" % size_mb
            + dl_link
            + "<div class=\"hint\">安全提示：生产环境请务必置于 HTTPS 反向代理（Nginx/Caddy）之后，"
            + "并通过环境变量 UPDATE_ADMIN_TOKEN 设置强管理密钥。</div>"
            + "</div></body></html>"
        )
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
        # 校验管理密钥
        import urllib.parse
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
        given = query.get("token", [""])[0]
        if given != admin_token():
            self._json_error(403, "管理密钥错误")
            return
        try:
            version_code = int(query.get("versionCode", ["0"])[0])
            version_name = query.get("versionName", [""])[0]
            changelog = query.get("changelog", [""])[0]
        except ValueError:
            self._json_error(400, "参数格式错误")
            return
        if version_code <= 0 or not version_name:
            self._json_error(400, "缺少 versionCode / versionName")
            return

        # 读取 APK 原始字节
        length = int(self.headers.get("Content-Length", 0))
        if length <= 0:
            self._json_error(400, "缺少 APK 数据")
            return
        raw = self.rfile.read(length)

        # 保存 APK
        filename = urllib.parse.unquote(
            self.headers.get("X-Apk-Filename", "") or "app-v%s.apk" % version_name)
        apk_dir = os.path.join(data_dir(), APK_DIR)
        os.makedirs(apk_dir, exist_ok=True)
        apk_name = "app-v%s-code%s.apk" % (version_name, version_code)
        apk_path = os.path.join(apk_dir, apk_name)
        with open(apk_path, "wb") as f:
            f.write(raw)

        # 更新版本信息
        v = load_version()
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

        print("✓ 新版本已发布: v%s (code %s), %s, %.1f MB" % (
            version_name, version_code, apk_name, len(raw) / 1024 / 1024))
        body = json.dumps(v, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json_error(self, code, msg):
        body = json.dumps({"error": msg}, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ThreadingServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def cmd_serve(args):
    port = args.port or int(os.environ.get("UPDATE_PORT", DEFAULT_PORT))
    host = args.host or os.environ.get("UPDATE_HOST", "0.0.0.0")
    v = load_version()
    print("=" * 56)
    print("  %s 后台更新控制台 已启动" % APP_NAME)
    print("  地址      : http://%s:%d" % (host, port))
    print("  版本 API  : http://%s:%d/api/version" % (host, port))
    print("  控制台页  : http://%s:%d/          （浏览器打开可查看当前版本）" % (host, port))
    print("  当前版本  : v%s (code %s)" % (v.get("versionName"), v.get("versionCode")))
    print("  管理密钥  : %s" % admin_token())
    print("=" * 56)
    server = ThreadingServer((host, port), UpdateHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止")


# ---------------- 入口 ----------------

def main():
    parser = argparse.ArgumentParser(description="乐听音乐 · 后台更新控制台")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_init = sub.add_parser("init", help="初始化数据目录")
    p_init.set_defaults(func=cmd_init)

    p_serve = sub.add_parser("serve", help="启动更新服务器")
    p_serve.add_argument("--host", default=None, help="监听地址，默认 0.0.0.0")
    p_serve.add_argument("--port", type=int, default=None, help="监听端口，默认 8000")
    p_serve.set_defaults(func=cmd_serve)

    p_pub = sub.add_parser("publish", help="发布新版本（上传 APK + 更新版本信息）")
    p_pub.add_argument("--server", required=True, help="服务器地址，如 http://1.2.3.4:8000")
    p_pub.add_argument("--token", default=None, help="管理密钥（不填则读取本地配置）")
    p_pub.add_argument("--apk", required=True, help="APK 文件路径")
    p_pub.add_argument("--version-code", type=int, required=True, help="版本号（正整数，递增）")
    p_pub.add_argument("--version-name", required=True, help="版本名，如 2.0")
    p_pub.add_argument("--changelog", default="", help="本次更新说明")
    p_pub.set_defaults(func=cmd_publish)

    p_st = sub.add_parser("status", help="查看线上版本信息")
    p_st.add_argument("--server", required=True, help="服务器地址，如 http://1.2.3.4:8000")
    p_st.set_defaults(func=cmd_status)

    args = parser.parse_args()
    # publish 未显式给 token 时，用本地配置的管理密钥
    if args.cmd == "publish" and not args.token:
        args.token = admin_token()
    args.func(args)


if __name__ == "__main__":
    main()
