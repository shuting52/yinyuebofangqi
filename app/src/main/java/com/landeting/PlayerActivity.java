package com.landeting;

import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.landeting.api.KuWoApi;
import com.landeting.model.LyricLine;
import com.landeting.model.Song;
import com.landeting.utils.LyricParser;
import com.landeting.utils.PlayerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 全屏播放页：旋转封面 + 歌词滚动 + 进度控制 + 播放模式。
 * 类似酷我音乐的“正在播放”页。
 */
public class PlayerActivity extends AppCompatActivity {

    private final PlayerManager player = PlayerManager.get();

    private ImageView ivCover;
    private TextView tvTitle;
    private TextView tvArtist;
    private SeekBar seekBar;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private ImageButton btnMode;
    private ImageButton btnPrev;
    private ImageButton btnPlay;
    private ImageButton btnNext;
    private ImageButton btnList;

    private ScrollView svLyric;
    private LinearLayout llLyric;
    private TextView tvNoLyric;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 300);
        }
    };

    private ObjectAnimator rotationAnimator;

    private List<LyricLine> lyrics = new ArrayList<>();
    private List<TextView> lyricViews = new ArrayList<>();
    private int currentLyricIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        bindViews();
        setupControls();
        player.addListener(stateListener);

        // 封面旋转动画：无限匀速旋转
        rotationAnimator = ObjectAnimator.ofFloat(ivCover, "rotation", 0f, 360f);
        rotationAnimator.setDuration(20000);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        rotationAnimator.setRepeatMode(ObjectAnimator.RESTART);
        if (player.isPlaying()) {
            rotationAnimator.start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        handler.post(progressTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(progressTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.removeListener(stateListener);
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
        }
    }

    // ==================== 初始化 ====================

    private void bindViews() {
        ivCover = findViewById(R.id.iv_cover);
        tvTitle = findViewById(R.id.tv_title);
        tvArtist = findViewById(R.id.tv_artist);
        seekBar = findViewById(R.id.seek_bar);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        btnMode = findViewById(R.id.btn_mode);
        btnPrev = findViewById(R.id.btn_prev);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnList = findViewById(R.id.btn_list);
        svLyric = findViewById(R.id.sv_lyric);
        llLyric = findViewById(R.id.ll_lyric);
        tvNoLyric = findViewById(R.id.tv_no_lyric);

        // 返回按钮（全屏页左上角）
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupControls() {
        // 进度条拖动
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvCurrentTime.setText(formatTime(progress));
                    player.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        btnPlay.setOnClickListener(v -> {
            PlaybackService.start(this);
            player.toggle();
        });
        btnPrev.setOnClickListener(v -> {
            PlaybackService.start(this);
            player.prev();
        });
        btnNext.setOnClickListener(v -> {
            PlaybackService.start(this);
            player.next();
        });
        btnMode.setOnClickListener(v -> switchPlayMode());
        btnList.setOnClickListener(v -> showPlaylistDialog());
    }

    // ==================== 状态刷新 ====================

    /** 切换歌曲或回到本页时刷新全部 UI */
    private void refreshAll() {
        Song song = player.getCurrentSong();
        if (song == null) {
            finish();
            return;
        }
        tvTitle.setText(song.getTitle());
        tvArtist.setText(song.getArtist() + (song.getAlbum() != null && !song.getAlbum().isEmpty()
                ? " · " + song.getAlbum() : ""));

        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
            Glide.with(this)
                    .load(song.getCoverUrl())
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(ivCover);
        } else {
            ivCover.setImageResource(R.drawable.ic_album_placeholder);
        }

        updatePlayIcon();
        updateModeIcon();
        loadLyric(song);
    }

    private void updateProgress() {
        Song song = player.getCurrentSong();
        if (song == null) {
            return;
        }
        long position = player.getPosition();
        long duration = player.getDuration();
        if (duration > 0) {
            seekBar.setMax((int) duration);
        }
        seekBar.setProgress((int) position);
        tvCurrentTime.setText(formatTime(position));
        if (duration > 0) {
            tvTotalTime.setText(formatTime(duration));
        }
        updateLyric(position);
    }

    private void updatePlayIcon() {
        btnPlay.setImageResource(player.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
        if (player.isPlaying()) {
            if (rotationAnimator != null && !rotationAnimator.isRunning()) {
                rotationAnimator.start();
            }
        } else {
            if (rotationAnimator != null) {
                rotationAnimator.pause();
            }
        }
    }

    private void updateModeIcon() {
        switch (player.getPlayMode()) {
            case PlayerManager.MODE_SINGLE:
                btnMode.setImageResource(R.drawable.ic_mode_single);
                break;
            case PlayerManager.MODE_RANDOM:
                btnMode.setImageResource(R.drawable.ic_mode_random);
                break;
            case PlayerManager.MODE_SEQUENCE:
            default:
                btnMode.setImageResource(R.drawable.ic_mode_sequence);
                break;
        }
    }

    private void switchPlayMode() {
        int next = (player.getPlayMode() + 1) % 3;
        player.setPlayMode(next);
        updateModeIcon();
        String hint;
        switch (next) {
            case PlayerManager.MODE_SINGLE:
                hint = "单曲循环";
                break;
            case PlayerManager.MODE_RANDOM:
                hint = "随机播放";
                break;
            default:
                hint = "列表循环";
                break;
        }
        Toast.makeText(this, hint, Toast.LENGTH_SHORT).show();
    }

    /** 弹出播放队列对话框 */
    private void showPlaylistDialog() {
        List<Song> playlist = player.getPlaylist();
        if (playlist.isEmpty()) {
            Toast.makeText(this, "播放列表是空的~", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[playlist.size()];
        for (int i = 0; i < playlist.size(); i++) {
            Song s = playlist.get(i);
            items[i] = (i + 1) + ". " + s.getTitle() + " - " + s.getArtist();
        }
        new AlertDialog.Builder(this)
                .setTitle("播放列表（共" + playlist.size() + "首）")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        player.play(playlist, which);
                        refreshAll();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ==================== 歌词 ====================

    /** 异步加载歌词：在线走酷我接口，本地读内嵌/同目录 lrc 文件 */
    private void loadLyric(final Song song) {
        currentLyricIndex = -1;
        lyrics = new ArrayList<>();
        lyricViews.clear();
        llLyric.removeAllViews();
        tvNoLyric.setVisibility(View.VISIBLE);
        tvNoLyric.setText("歌词加载中…");

        new Thread(() -> {
            String lrcText = "";
            if (song.isOnline()) {
                lrcText = KuWoApi.fetchLyric(String.valueOf(song.getId()));
            } else {
                lrcText = readLocalLyric(song);
            }
            final List<LyricLine> lines = LyricParser.parse(lrcText);
            runOnUiThread(() -> showLyrics(lines));
        }).start();
    }

    /** 读取本地歌词：优先同目录 .lrc，其次内嵌歌词 */
    private String readLocalLyric(Song song) {
        try {
            // 1. 同目录 .lrc 文件（把扩展名替换为 .lrc）
            String path = song.getUrl();
            String lrcPath = path.replaceAll("(?i)\\.(mp3|flac|wav|m4a|ogg|aac|wma)$", ".lrc");
            File lrcFile = new File(lrcPath);
            if (lrcFile.exists()) {
                return readFileToString(lrcFile);
            }
            // 2. 媒体文件内嵌歌词（ID3 / Vorbis 等标签）
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(path);
                // METADATA_KEY_LYRICS 常量在部分 SDK 平台缺失，用反射安全读取
                String embedded = null;
                try {
                    java.lang.reflect.Field f = MediaMetadataRetriever.class
                            .getField("METADATA_KEY_LYRICS");
                    int key = f.getInt(null);
                    embedded = mmr.extractMetadata(key);
                } catch (Exception ignored) {
                }
                if (embedded != null && !embedded.trim().isEmpty()) {
                    return embedded;
                }
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            // 读取失败则返回空歌词
        }
        return "";
    }

    private String readFileToString(File file) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 把歌词行渲染到界面上 */
    private void showLyrics(List<LyricLine> lines) {
        lyrics = lines;
        llLyric.removeAllViews();
        lyricViews.clear();
        currentLyricIndex = -1;

        if (lines.isEmpty()) {
            tvNoLyric.setVisibility(View.VISIBLE);
            tvNoLyric.setText("暂无歌词，纯享音乐吧~");
            return;
        }
        tvNoLyric.setVisibility(View.GONE);

        for (LyricLine line : lines) {
            TextView tv = new TextView(this);
            tv.setText(line.getText());
            tv.setTextSize(15);
            tv.setTextColor(Color.parseColor("#B3B3B3"));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(16), dp(12), dp(16), dp(12));
            llLyric.addView(tv);
            lyricViews.add(tv);
        }
        // 初始滚动到中间位置
        svLyric.post(() -> {
            if (lyricViews.size() > 1) {
                svLyric.scrollTo(0, Math.max(0,
                        lyricViews.get(0).getTop()
                                - (svLyric.getHeight() - lyricViews.get(0).getHeight()) / 2));
            }
        });
    }

    /** 每 300ms 检查歌词高亮与滚动 */
    private void updateLyric(long position) {
        if (lyrics.isEmpty()) {
            return;
        }
        int index = LyricParser.findIndex(lyrics, position);
        if (index == currentLyricIndex) {
            return;
        }
        // 恢复上一行样式
        if (currentLyricIndex >= 0 && currentLyricIndex < lyricViews.size()) {
            TextView old = lyricViews.get(currentLyricIndex);
            old.setTextSize(15);
            old.setTextColor(Color.parseColor("#B3B3B3"));
        }
        currentLyricIndex = index;
        if (index < lyricViews.size()) {
            final TextView current = lyricViews.get(index);
            current.setTextSize(18);
            current.setTextColor(ContextCompat.getColor(this, R.color.lyric_highlight));
            // 平滑滚动到当前行居中
            svLyric.post(() -> {
                int top = current.getTop();
                int target = top - (svLyric.getHeight() - current.getHeight()) / 2;
                svLyric.smoothScrollTo(0, Math.max(0, target));
            });
        }
    }

    // ==================== 播放状态监听 ====================

    private final PlayerManager.PlayerListener stateListener = new PlayerManager.PlayerListener() {
        @Override
        public void onSongChanged(Song song) {
            if (song != null) {
                refreshAll();
            }
        }

        @Override
        public void onPlayStateChanged(boolean isPlaying) {
            updatePlayIcon();
        }

        @Override
        public void onBuffering(boolean buffering) {
        }

        @Override
        public void onPlayListChanged() {
        }
    };

    // ==================== 小工具 ====================

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        return String.format(java.util.Locale.CHINA, "%02d:%02d",
                totalSec / 60, totalSec % 60);
    }
}
