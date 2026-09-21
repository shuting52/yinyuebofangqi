package com.landeting.utils;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.landeting.api.KuWoApi;
import com.landeting.model.Song;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局播放管理器（单例）。
 *
 * 职责：
 *  1. 维护播放队列与当前歌曲索引
 *  2. 封装 MediaPlayer 的播放/暂停/上一首/下一首/跳转
 *  3. 在线歌曲先解析真实播放地址再播放
 *  4. 通过监听器把状态变化通知给 UI 与通知栏服务
 *
 * 说明：MediaPlayer 的操作统一通过主线程 Handler 执行，避免线程安全问题。
 */
public class PlayerManager {

    private static final String TAG = "PlayerManager";

    private static final PlayerManager INSTANCE = new PlayerManager();

    public static PlayerManager get() {
        return INSTANCE;
    }

    // ---- 播放模式 ----
    public static final int MODE_SEQUENCE = 0; // 列表顺序循环
    public static final int MODE_SINGLE = 1;   // 单曲循环
    public static final int MODE_RANDOM = 2;   // 随机播放

    private int playMode = MODE_SEQUENCE;
    private final java.util.Random random = new java.util.Random();

    public int getPlayMode() {
        return playMode;
    }

    public void setPlayMode(int mode) {
        playMode = mode;
    }

    /** 播放状态监听器 */
    public interface PlayerListener {
        /** 歌曲切换（播放开始/切歌时触发，song 可能为 null） */
        void onSongChanged(Song song);

        /** 播放/暂停状态变化 */
        void onPlayStateChanged(boolean isPlaying);

        /** 开始解析在线地址 / 缓冲中 */
        void onBuffering(boolean buffering);

        /** 播放列表变化 */
        void onPlayListChanged();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<PlayerListener> listeners = new CopyOnWriteArrayList<>();

    private MediaPlayer mediaPlayer;
    private final List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private boolean buffering = false;

    private PlayerManager() {
        createPlayer();
    }

    // ==================== 对外控制接口 ====================

    /**
     * 播放指定列表中的第 index 首。
     * 会替换当前播放队列。
     */
    public void play(List<Song> songs, int index) {
        if (songs == null || songs.isEmpty() || index < 0 || index >= songs.size()) {
            return;
        }
        playlist.clear();
        playlist.addAll(songs);
        playIndex(index);
    }

    /** 在现有队列基础上插入一首并播放（用于“在线试听”追加播放） */
    public void playNext(Song song) {
        if (song == null) return;
        playlist.add(currentIndex + 1, song);
        notifyPlayListChanged();
        playIndex(currentIndex + 1);
    }

    /** 播放/暂停切换 */
    public void toggle() {
        mainHandler.post(() -> {
            if (currentIndex < 0 || playlist.isEmpty()) {
                return;
            }
            if (mediaPlayer == null) {
                // 播放器曾被释放（如通知栏关闭按钮），重新加载当前歌曲
                playIndex(currentIndex);
                return;
            }
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                notifyPlayState(false);
            } else {
                mediaPlayer.start();
                notifyPlayState(true);
            }
        });
    }

    /** 播放下一首 */
    public void next() {
        mainHandler.post(() -> {
            if (playlist.isEmpty()) return;
            playIndex((currentIndex + 1) % playlist.size());
        });
    }

    /** 播放上一首（当前播放超过 3 秒则回到开头） */
    public void prev() {
        mainHandler.post(() -> {
            if (playlist.isEmpty() || currentIndex < 0) return;
            if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3000) {
                mediaPlayer.seekTo(0);
                return;
            }
            playIndex((currentIndex - 1 + playlist.size()) % playlist.size());
        });
    }

    /** 跳转到指定进度 */
    public void seekTo(long positionMs) {
        mainHandler.post(() -> {
            if (mediaPlayer != null) {
                mediaPlayer.seekTo((int) positionMs);
            }
        });
    }

    /** 停止播放并释放资源（应用退出 / 通知栏关闭时调用） */
    public void release() {
        mainHandler.post(() -> {
            if (mediaPlayer != null) {
                try {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                } catch (Exception ignored) {
                }
                mediaPlayer = null;
            }
            notifyPlayState(false);
        });
    }

    // ==================== 状态查询 ====================

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public boolean isBuffering() {
        return buffering;
    }

    public List<Song> getPlaylist() {
        return new ArrayList<>(playlist);
    }

    public Song getCurrentSong() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return null;
        }
        return playlist.get(currentIndex);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public long getPosition() {
        if (mediaPlayer != null && currentIndex >= 0) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    public long getDuration() {
        if (mediaPlayer != null && currentIndex >= 0) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    // ==================== 监听器 ====================

    public void addListener(PlayerListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PlayerListener listener) {
        listeners.remove(listener);
    }

    // ==================== 内部实现 ====================

    private void createPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mediaPlayer.setOnCompletionListener(mp -> onPlaybackCompleted());
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "播放出错 what=" + what + " extra=" + extra);
            notifyBuffering(false);
            next();
            return true;
        });
        mediaPlayer.setOnPreparedListener(mp -> {
            notifyBuffering(false);
            mp.start();
            notifyPlayState(true);
            notifySongChanged(getCurrentSong());
        });
    }

    /** 播放队列中第 index 首（已在主线程） */
    private void playIndex(int index) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        currentIndex = index;
        Song song = playlist.get(index);
        notifySongChanged(song);
        notifyBuffering(true);

        if (song.isOnline()) {
            // 在线歌曲：解析真实播放地址（子线程），完成后回主线程播放
            final long rid = song.getId();
            executor.execute(() -> {
                String playUrl = KuWoApi.resolvePlayUrl(String.valueOf(rid));
                mainHandler.post(() -> {
                    if (playUrl == null) {
                        Log.w(TAG, "无法获取播放地址: " + song.getTitle());
                        notifyBuffering(false);
                        return;
                    }
                    song.setUrl(playUrl);
                    startPlaying(song.toUri().toString());
                });
            });
        } else {
            startPlaying(song.toUri().toString());
        }
    }

    /** 一首播放完毕，按播放模式决定下一首 */
    private void onPlaybackCompleted() {
        if (playlist.isEmpty()) {
            return;
        }
        switch (playMode) {
            case MODE_SINGLE:
                // 单曲循环：从头重播当前
                playIndex(currentIndex);
                break;
            case MODE_RANDOM:
                // 随机：从除当前外的歌曲中随机选一首
                if (playlist.size() == 1) {
                    playIndex(0);
                } else {
                    int nextIdx;
                    do {
                        nextIdx = random.nextInt(playlist.size());
                    } while (nextIdx == currentIndex);
                    playIndex(nextIdx);
                }
                break;
            case MODE_SEQUENCE:
            default:
                playIndex((currentIndex + 1) % playlist.size());
                break;
        }
    }

    /** 真正开始加载音频（主线程） */
    private void startPlaying(String dataSource) {
        if (mediaPlayer == null) {
            createPlayer();
        }
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(dataSource);
            mediaPlayer.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "加载音频失败", e);
            notifyBuffering(false);
        }
    }

    private void notifySongChanged(Song song) {
        for (PlayerListener l : listeners) {
            l.onSongChanged(song);
        }
    }

    private void notifyPlayState(boolean playing) {
        for (PlayerListener l : listeners) {
            l.onPlayStateChanged(playing);
        }
    }

    private void notifyBuffering(boolean b) {
        buffering = b;
        for (PlayerListener l : listeners) {
            l.onBuffering(b);
        }
    }

    private void notifyPlayListChanged() {
        for (PlayerListener l : listeners) {
            l.onPlayListChanged();
        }
    }
}
