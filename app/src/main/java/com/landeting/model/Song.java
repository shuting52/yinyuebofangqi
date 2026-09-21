package com.landeting.model;

import android.net.Uri;

/**
 * 歌曲数据模型，兼容本地音乐与在线音乐两种来源。
 */
public class Song {

    /** 本地：MediaStore 中的歌曲 ID；在线：酷我音乐的 RID（musicId） */
    private long id;

    /** 歌曲标题 */
    private String title;

    /** 歌手名（多个歌手用 / 分隔） */
    private String artist;

    /** 专辑名 */
    private String album;

    /** 本地：音频文件路径；在线：最终可播放的 URL */
    private String url;

    /** 封面图 URL（在线歌曲有值；本地歌曲通常为空） */
    private String coverUrl;

    /** 时长（毫秒） */
    private long duration;

    /** 是否为在线歌曲（false 表示本地文件） */
    private boolean online;

    /** 在线歌曲：歌词 URL 或 LRC 文本（预留字段） */
    private String lyricUrl;

    public Song() {
    }

    /** 构造一个本地歌曲 */
    public static Song fromLocal(long id, String title, String artist, String album,
                                 String path, long duration) {
        Song s = new Song();
        s.id = id;
        s.title = title == null || title.isEmpty() ? "未知歌曲" : title;
        s.artist = artist == null || artist.isEmpty() ? "未知歌手" : artist;
        s.album = album == null ? "" : album;
        s.url = path;
        s.duration = duration;
        s.online = false;
        return s;
    }

    /** 构造一个在线歌曲 */
    public static Song fromOnline(long rid, String title, String artist, String album,
                                  String coverUrl, long duration) {
        Song s = new Song();
        s.id = rid;
        s.title = title == null || title.isEmpty() ? "未知歌曲" : title;
        s.artist = artist == null || artist.isEmpty() ? "未知歌手" : artist;
        s.album = album == null ? "" : album;
        s.coverUrl = coverUrl;
        s.duration = duration;
        s.online = true;
        return s;
    }

    /** 播放时使用：本地为 file:// URI，在线为 http(s) URL */
    public Uri toUri() {
        if (online) {
            return Uri.parse(url);
        }
        return Uri.fromFile(new java.io.File(url));
    }

    /** 格式化时长 mm:ss */
    public String durationText() {
        long totalSec = duration / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public String getLyricUrl() {
        return lyricUrl;
    }

    public void setLyricUrl(String lyricUrl) {
        this.lyricUrl = lyricUrl;
    }
}
