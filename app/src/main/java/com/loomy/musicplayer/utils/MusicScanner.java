package com.loomy.musicplayer.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import android.util.Log;

import com.loomy.musicplayer.model.Song;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地音乐扫描器：通过 MediaStore 读取设备上的音频文件。
 * Android 13+（API 33）需要 READ_MEDIA_AUDIO 权限，低版本需要 READ_EXTERNAL_STORAGE。
 */
public class MusicScanner {

    private static final String TAG = "MusicScanner";

    private MusicScanner() {
    }

    /**
     * 扫描全部本地音乐。
     *
     * @param context 上下文
     * @return 歌曲列表（已按标题排序）
     */
    public static List<Song> scan(Context context) {
        List<Song> songs = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();

        // 查询字段：ID、标题、歌手、专辑、时长、路径
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA
        };

        // 只查可播放的音频，排除铃声、通知音等系统音频
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";

        try (Cursor cursor = resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, null, null)) {

            if (cursor == null) {
                Log.w(TAG, "MediaStore 查询失败，可能未授予存储权限");
                return songs;
            }

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE));
                String artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST));
                String album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION));
                String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA));

                // 过滤损坏的条目（无路径或时长为 0）
                if (path == null || path.isEmpty() || duration <= 0) {
                    continue;
                }
                songs.add(Song.fromLocal(id, title, artist, album, path, duration));
            }
        } catch (Exception e) {
            Log.e(TAG, "扫描本地音乐失败", e);
        }
        return songs;
    }
}
