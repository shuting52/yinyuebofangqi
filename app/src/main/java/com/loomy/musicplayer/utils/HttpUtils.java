package com.loomy.musicplayer.utils;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 轻量 HTTP 请求工具（基于 OkHttp 的同步封装）。
 * 所有耗时网络操作都应放到子线程中执行。
 */
public class HttpUtils {

    private static final String TAG = "HttpUtils";
    private static final int TIMEOUT_SECONDS = 15;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    private HttpUtils() {
    }

    /**
     * 同步 GET 请求，返回 UTF-8 字符串。
     *
     * @param url 请求地址
     * @return 响应文本；失败返回 null
     */
    public static String get(String url) {
        return get(url, null);
    }

    /**
     * 同步 GET 请求，支持自定义 Referer（部分音乐接口需要）。
     */
    public static String get(String url, String referer) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*");
        if (referer != null) {
            builder.header("Referer", referer);
        }
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "请求失败 code=" + response.code() + " url=" + url);
                return null;
            }
            return response.body() != null ? response.body().string() : null;
        } catch (IOException e) {
            Log.e(TAG, "网络请求异常 url=" + url, e);
            return null;
        }
    }

    /**
     * 下载文件到指定路径（用于在线歌曲缓存）。
     *
     * @param url  下载地址
     * @param dest 目标文件路径
     * @return 是否成功
     */
    public static boolean download(String url, String dest) {
        Request.Builder builder = new Request.Builder().url(url);
        try (Response response = CLIENT.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                Log.w(TAG, "下载失败 code=" + response.code());
                return false;
            }
            if (response.body() == null) {
                return false;
            }
            java.io.File file = new java.io.File(dest);
            java.io.File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (java.io.InputStream in = response.body().byteStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
                out.flush();
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "下载异常", e);
            return false;
        }
    }
}
