package com.landeting.model;

/**
 * 一行歌词：时间点 + 文本。
 */
public class LyricLine {

    /** 该行歌词开始时间（毫秒） */
    private long time;

    /** 歌词文本 */
    private String text;

    public LyricLine(long time, String text) {
        this.time = time;
        this.text = text;
    }

    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
