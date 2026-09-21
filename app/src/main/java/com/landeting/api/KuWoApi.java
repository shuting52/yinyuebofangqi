package com.landeting.api;

import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.landeting.model.Song;
import com.landeting.utils.HttpUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 酷我音乐开放接口封装。
 * 接口说明：
 *  1. 搜索：GET search.kuwo.cn/r.s
 *  2. 播放地址：GET antiserver.kuwo.cn/anti.s (convert_url3)
 *  3. 歌词：GET m.kuwo.cn/newh5/singles/songinfoandlrc
 *
 * 注意：酷我接口属公开接口，若某日失效，只需修改本类中对应的 URL 模板即可。
 * 所有方法均为同步调用，请在工作线程中使用。
 */
public class KuWoApi {

    private static final String TAG = "KuWoApi";

    /** 搜索接口模板 */
    private static final String SEARCH_URL =
            "http://search.kuwo.cn/r.s?all=%s&ft=music&itemset=web_2013&client=kt"
                    + "&rformat=json&encoding=utf8&pn=%d&rn=%d&vipver=MUSIC_9.1.1.2_W4&ver=mbox";

    /** 播放地址接口模板 */
    private static final String PLAY_URL =
            "http://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=%s&format=mp3&response=url";

    /** 歌词接口模板 */
    private static final String LYRIC_URL =
            "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=%s";

    /** 封面兜底模板（当搜索接口未返回封面时使用） */
    private static final String COVER_FALLBACK =
            "http://img1.kuwo.cn/star/albumcover/%s_album_mobile.jpg";

    private KuWoApi() {
    }

    /**
     * 在线搜索歌曲。
     *
     * @param keyword  搜索关键词
     * @param page     页码（从 1 开始）
     * @param pageSize 每页数量（建议 20~30）
     * @return 歌曲列表；失败返回空列表
     */
    public static List<Song> search(String keyword, int page, int pageSize) {
        List<Song> result = new ArrayList<>();
        String url = String.format(java.util.Locale.US, SEARCH_URL,
                java.net.URLEncoder.encode(keyword, java.nio.charset.StandardCharsets.UTF_8),
                page - 1, pageSize);
        String body = HttpUtils.get(url);
        if (body == null) {
            return result;
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray list = null;
            // 兼容两种字段结构：abslist（web_2013） / data.list
            if (root.has("abslist") && root.get("abslist").isJsonArray()) {
                list = root.getAsJsonArray("abslist");
            } else if (root.has("data") && root.get("data").isJsonObject()
                    && root.getAsJsonObject("data").has("list")) {
                list = root.getAsJsonObject("data").getAsJsonArray("list");
            }
            if (list == null) {
                Log.w(TAG, "搜索响应无歌曲列表");
                return result;
            }
            for (JsonElement e : list) {
                JsonObject item = e.getAsJsonObject();
                Song song = parseSearchItem(item);
                if (song != null) {
                    result.add(song);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "解析搜索结果失败", ex);
        }
        return result;
    }

    /** 解析单条搜索结果（不同字段版本均兼容） */
    private static Song parseSearchItem(JsonObject item) {
        try {
            // RID 可能有 MUSIC_123 与 123 两种形式
            String ridRaw = firstNonEmpty(item, "MUSICRID", "rid");
            if (ridRaw == null) {
                return null;
            }
            long rid = normalizeRid(ridRaw);

            String title = firstNonEmpty(item, "SONGNAME", "name", "songname");
            String artist = firstNonEmpty(item, "ARTIST", "artist");
            String album = firstNonEmpty(item, "ALBUM", "album");
            String cover = firstNonEmpty(item, "web_albumpic_short",
                    "albumpic", "pic", "cover");

            long duration = 0;
            JsonElement durEl = item.has("duration") ? item.get("duration") : null;
            if (durEl != null && !durEl.isJsonNull()) {
                try {
                    duration = (long) (Double.parseDouble(durEl.getAsString()) * 1000);
                } catch (Exception ignored) {
                }
            }

            Song song = Song.fromOnline(rid, title, artist, album, cover, duration);
            if (cover == null || cover.isEmpty()) {
                song.setCoverUrl(String.format(java.util.Locale.US, COVER_FALLBACK, ridRaw));
            }
            return song;
        } catch (Exception ex) {
            Log.w(TAG, "忽略无法解析的搜索结果项", ex);
            return null;
        }
    }

    /**
     * 获取可播放的 MP3 直链。
     *
     * @param rid 歌曲 RID（如 MUSIC_123456 或纯数字）
     * @return 播放 URL；失败返回 null
     */
    public static String resolvePlayUrl(String rid) {
        String url = String.format(java.util.Locale.US, PLAY_URL, rid);
        // 酷我防盗链要求带 Referer
        String body = HttpUtils.get(url, "http://www.kuwo.cn/");
        if (body == null) {
            return null;
        }
        // 响应可能有多行 URL，取第一行
        String[] lines = body.trim().split("\\s+");
        for (String line : lines) {
            String candidate = line.trim();
            if (candidate.startsWith("http")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 获取歌曲 LRC 歌词文本。
     *
     * @param rid 歌曲 RID
     * @return 标准 LRC 文本；无歌词或失败返回空字符串
     */
    public static String fetchLyric(String rid) {
        String url = String.format(java.util.Locale.US, LYRIC_URL, rid);
        String body = HttpUtils.get(url, "http://www.kuwo.cn/");
        if (body == null) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return "";
            }
            JsonObject data = root.getAsJsonObject("data");
            StringBuilder sb = new StringBuilder();

            // 方式一：lrclist 数组 [{time:"00:01.50", line:"..."}]
            if (data.has("lrclist") && data.get("lrclist").isJsonArray()) {
                JsonArray lrcList = data.getAsJsonArray("lrclist");
                for (JsonElement e : lrcList) {
                    JsonObject o = e.getAsJsonObject();
                    String time = o.has("time") ? o.get("time").getAsString() : "[00:00.00]";
                    String line = o.has("line") ? o.get("line").getAsString() : "";
                    sb.append('[').append(time).append(']').append(line).append('\n');
                }
                return sb.toString();
            }

            // 方式二：data.lrc 直接是完整 LRC 文本
            if (data.has("lrc")) {
                return data.get("lrc").getAsString();
            }
        } catch (Exception ex) {
            Log.e(TAG, "解析歌词失败", ex);
        }
        return "";
    }

    // ---------- 小工具 ----------

    /** 从多个候选字段中取第一个非空字符串 */
    private static String firstNonEmpty(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                String v = obj.get(key).getAsString();
                if (v != null && !v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** 将 MUSIC_123456 归一化为纯数字 123456 */
    private static long normalizeRid(String rid) {
        String digits = rid.replaceAll("\\D", "");
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
