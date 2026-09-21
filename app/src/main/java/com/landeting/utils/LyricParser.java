package com.landeting.utils;

import com.landeting.model.LyricLine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器。
 * 支持标准格式： [mm:ss.xx]歌词文本
 * 支持一行多时间戳： [00:10.00][00:20.00]重复唱的词
 * 忽略元信息行： [ar:歌手] [ti:标题] [al:专辑] [by:某某] [offset:100]
 */
public class LyricParser {

    /** 匹配 [mm:ss.xx] 时间戳 */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:\\.(\\d{1,3}))?]");

    /** 匹配 [mm:ss] 或 [mm:ss.xx] 的时间戳（用于正则校验） */
    private static final Pattern TAG_PATTERN = Pattern.compile("^\\[[^]]+]");

    private LyricParser() {
    }

    /**
     * 解析 LRC 文本。
     *
     * @param lrcText 原始 LRC 内容，可为 null
     * @return 按时间升序排列的歌词行；无歌词时返回空列表
     */
    public static List<LyricLine> parse(String lrcText) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrcText == null || lrcText.trim().isEmpty()) {
            return lines;
        }

        int offsetMs = 0;
        String[] rows = lrcText.split("\r?\n");
        for (String row : rows) {
            if (row == null || row.trim().isEmpty()) {
                continue;
            }
            String line = row.trim();

            // 解析 offset 偏移（正值表示整体提前，负值表示整体延后）
            Matcher offsetMatcher = Pattern.compile("\\[offset:([+-]?\\d+)]").matcher(line);
            if (offsetMatcher.find()) {
                offsetMs = -Integer.parseInt(offsetMatcher.group(1));
                continue;
            }

            // 找出所有时间戳
            List<Long> times = new ArrayList<>();
            Matcher timeMatcher = TIME_PATTERN.matcher(line);
            while (timeMatcher.find()) {
                long minutes = Long.parseLong(timeMatcher.group(1));
                long seconds = Long.parseLong(timeMatcher.group(2));
                long millis = 0;
                if (timeMatcher.group(3) != null) {
                    String frac = timeMatcher.group(3);
                    // 支持 .x / .xx / .xxx 三种精度
                    if (frac.length() == 1) millis = Long.parseLong(frac) * 100;
                    else if (frac.length() == 2) millis = Long.parseLong(frac) * 10;
                    else millis = Long.parseLong(frac);
                }
                times.add(minutes * 60_000 + seconds * 1000 + millis);
            }

            if (times.isEmpty()) {
                // 纯元信息行（如 [ar:xx] 或 [00:00] 无文本），跳过
                continue;
            }

            // 去掉所有 [xx:xx.xx] 标签后剩余的是歌词文本
            String text = TIME_PATTERN.matcher(line).replaceAll("").trim();
            // 清理可能残留的 [ar:...] 等元信息标签
            text = text.replaceAll(TAG_PATTERN.pattern(), "").trim();

            for (long t : times) {
                long adjusted = Math.max(0, t + offsetMs);
                lines.add(new LyricLine(adjusted, text));
            }
        }

        // 按时间排序，时间相同保持原顺序
        Collections.sort(lines, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine a, LyricLine b) {
                return Long.compare(a.getTime(), b.getTime());
            }
        });
        return lines;
    }

    /**
     * 在给定播放进度下查找当前应高亮的歌词行下标。
     *
     * @param lines   歌词行列表（需已按时间排序）
     * @param position 播放进度（毫秒）
     * @return 当前歌词下标；无匹配返回 0
     */
    public static int findIndex(List<LyricLine> lines, long position) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int index = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getTime() <= position) {
                index = i;
            } else {
                break;
            }
        }
        return index;
    }
}
