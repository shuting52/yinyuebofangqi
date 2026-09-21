package com.landeting.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.landeting.R;

import java.io.File;

/**
 * 版本更新检测工具。
 *
 * 配套后台更新控制台使用：
 *   - 服务器返回 /api/version 的 JSON：
 *     { "versionCode": 2, "versionName": "2.0", "url": "/apk/app-v2.0.apk",
 *       "changelog": "更新说明", "size": 123456 }
 *   - App 发现 versionCode 大于本地版本时，提示用户下载并安装。
 *
 * 部署后请把 UPDATE_SERVER 改成你的服务器地址（含端口）。
 */
public class UpdateChecker {

    /** TODO: 改成你的后台更新服务器地址，例如 "http://192.168.1.100:8000" */
    public static final String UPDATE_SERVER = "http://127.0.0.1:8000";

    /** 更新信息模型 */
    public static class UpdateInfo {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String changelog = "";
        public long size;

        /** 生成完整下载地址 */
        public String fullDownloadUrl() {
            if (url.startsWith("http")) {
                return url;
            }
            return UPDATE_SERVER + url;
        }
    }

    private UpdateChecker() {
    }

    /**
     * 检查服务器上的最新版本（网络操作，需在子线程调用）。
     *
     * @return 若服务器版本高于本地版本，返回更新信息；否则返回 null
     */
    public static UpdateInfo check(Context context) {
        try {
            int localCode;
            android.content.pm.PackageInfo pkg = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                localCode = (int) pkg.getLongVersionCode();
            } else {
                localCode = pkg.versionCode;
            }

            String body = HttpUtils.get(UPDATE_SERVER + "/api/version");
            if (body == null) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            UpdateInfo info = new UpdateInfo();
            info.versionCode = obj.get("versionCode").getAsInt();
            info.versionName = obj.has("versionName")
                    ? obj.get("versionName").getAsString() : "";
            info.url = obj.has("url") ? obj.get("url").getAsString() : "";
            info.changelog = obj.has("changelog")
                    ? obj.get("changelog").getAsString() : "";
            info.size = obj.has("size") ? obj.get("size").getAsLong() : 0;

            if (info.versionCode > localCode && !info.url.isEmpty()) {
                return info;
            }
        } catch (Exception e) {
            // 网络异常或解析失败：静默忽略（不打扰用户）
        }
        return null;
    }

    /**
     * 下载 APK 到应用缓存目录（子线程调用）。
     *
     * @return 下载后的文件；失败返回 null
     */
    public static File download(Context context, UpdateInfo info) {
        File dir = new File(context.getCacheDir(), "update");
        File target = new File(dir, "app-update.apk");
        boolean ok = HttpUtils.download(info.fullDownloadUrl(), target.getAbsolutePath());
        return ok ? target : null;
    }

    /**
     * 调起系统安装器安装 APK（主线程调用）。
     */
    public static void install(Context context, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 是否允许安装未知来源应用（Android 8+ 需要）。
     */
    public static boolean canRequestInstall(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /**
     * 跳转到系统设置开启“允许安装未知应用”。
     */
    public static void openInstallSetting(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
}
