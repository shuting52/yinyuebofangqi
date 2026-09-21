package com.loomy.musicplayer;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.loomy.musicplayer.adapter.SongAdapter;
import com.loomy.musicplayer.model.Song;
import com.loomy.musicplayer.utils.MusicScanner;
import com.loomy.musicplayer.utils.PlayerManager;
import com.loomy.musicplayer.utils.UpdateChecker;

import java.io.File;
import java.util.List;

/**
 * 主界面：本地音乐列表 + 底部迷你播放条。
 * 类似于酷我“我的音乐”页，点击歌曲即可播放，底部常驻迷你播放条。
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSION = 1001;

    /** 本次进程是否已检查过更新（避免反复弹窗） */
    private static boolean updateChecked = false;

    private RecyclerView rvSongs;
    private SongAdapter adapter;
    private View miniBar;
    private ImageView ivMiniCover;
    private TextView tvMiniTitle;
    private TextView tvMiniArtist;
    private ImageView ivMiniPlay;
    private TextView tvEmpty;

    private final PlayerManager player = PlayerManager.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // 右上角：进入在线音乐搜索
        findViewById(R.id.iv_online).setOnClickListener(v ->
                startActivity(new Intent(this, SearchActivity.class)));

        rvSongs = findViewById(R.id.rv_songs);
        miniBar = findViewById(R.id.mini_bar);
        ivMiniCover = findViewById(R.id.iv_mini_cover);
        tvMiniTitle = findViewById(R.id.tv_mini_title);
        tvMiniArtist = findViewById(R.id.tv_mini_artist);
        ivMiniPlay = findViewById(R.id.iv_mini_play);
        tvEmpty = findViewById(R.id.tv_empty);

        setupList();
        setupMiniBar();

        // 检查并申请存储权限
        if (hasAudioPermission()) {
            loadLocalMusic();
        } else {
            requestAudioPermission();
        }

        // 静默检查新版本（仅首次进入时）
        if (!updateChecked) {
            updateChecked = true;
            checkForUpdate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页返回时同步迷你条状态
        player.addListener(uiListener);
        refreshMiniBar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        player.removeListener(uiListener);
    }

    // ==================== 列表 ====================

    private void setupList() {
        adapter = new SongAdapter(new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(Song song, int position) {
                playAt(position);
            }

            @Override
            public boolean onActionClick(Song song, int position) {
                // 本地列表无右侧按钮
                return false;
            }
        });
        rvSongs.setLayoutManager(new LinearLayoutManager(this));
        rvSongs.setAdapter(adapter);
    }

    /** 播放列表中第 position 首 */
    private void playAt(int position) {
        List<Song> all = adapterSongs();
        if (all.isEmpty() || position >= all.size()) {
            return;
        }
        // 先启动后台服务（保证通知栏控制可用），再播放
        PlaybackService.start(this);
        player.play(all, position);
        // 直接进入播放页（此时异步播放队列中 currentSong 即将就绪）
        startActivity(new Intent(this, PlayerActivity.class));
    }

    @SuppressWarnings("unchecked")
    private List<Song> adapterSongs() {
        return adapter.getSongsForPlayback();
    }

    // ==================== 迷你播放条 ====================

    private void setupMiniBar() {
        // 点击迷你条 → 打开全屏播放页
        miniBar.setOnClickListener(v -> openPlayerActivity());

        // 播放/暂停按钮：先确保服务存在，再切换
        ivMiniPlay.setOnClickListener(v -> {
            if (player.getCurrentSong() == null) {
                return;
            }
            PlaybackService.start(this);
            player.toggle();
        });

        ImageView ivMiniNext = findViewById(R.id.iv_mini_next);
        ivMiniNext.setOnClickListener(v -> {
            if (player.getCurrentSong() == null) {
                return;
            }
            PlaybackService.start(this);
            player.next();
        });
    }

    /** 根据 PlayerManager 当前状态刷新迷你条 */
    private void refreshMiniBar() {
        Song song = player.getCurrentSong();
        if (song == null) {
            miniBar.setVisibility(View.GONE);
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        tvMiniTitle.setText(song.getTitle());
        tvMiniArtist.setText(song.getArtist());

        if (song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
            Glide.with(this)
                    .load(song.getCoverUrl())
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .circleCrop()
                    .into(ivMiniCover);
        } else {
            ivMiniCover.setImageResource(R.drawable.ic_album_placeholder);
        }
        ivMiniPlay.setImageResource(player.isPlaying()
                ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void openPlayerActivity() {
        if (player.getCurrentSong() == null) {
            Toast.makeText(this, "还没有播放的歌曲哦~", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, PlayerActivity.class));
    }

    // ==================== 权限 ====================

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+：音乐权限 + 通知权限一起请求
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.READ_MEDIA_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                    }, REQ_PERMISSION);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSION) {
            // 第一个权限是音频读取（通知权限被拒绝也不影响播放，只是通知栏不显示）
            boolean audioGranted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (audioGranted) {
                loadLocalMusic();
            } else {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText("未授予存储权限，无法扫描本地音乐\n点击右上角在线音乐也能听歌哦~");
            }
        }
    }

    // ==================== 本地音乐加载 ====================

    private void loadLocalMusic() {
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText("正在扫描本地音乐…");

        new Thread(() -> {
            List<Song> songs = MusicScanner.scan(this);
            runOnUiThread(() -> {
                if (songs.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    tvEmpty.setText("没有找到本地音乐\n点击右上角在线音乐也能听歌哦~");
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
                adapter.setData(songs);
            });
        }).start();
    }

    // ==================== 版本更新 ====================

    private void checkForUpdate() {
        new Thread(() -> {
            final UpdateChecker.UpdateInfo info = UpdateChecker.check(this);
            if (info == null) {
                return;
            }
            runOnUiThread(() -> showUpdateDialog(info));
        }).start();
    }

    private void showUpdateDialog(UpdateChecker.UpdateInfo info) {
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + info.versionName)
                .setMessage("更新说明：\n" + info.changelog)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(info))
                .setNegativeButton("稍后再说", null)
                .show();
    }

    private void downloadAndInstall(UpdateChecker.UpdateInfo info) {
        if (!UpdateChecker.canRequestInstall(this)) {
            Toast.makeText(this, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show();
            UpdateChecker.openInstallSetting(this);
            return;
        }
        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("正在下载更新…");
        dialog.setCancelable(false);
        dialog.show();
        new Thread(() -> {
            final File apk = UpdateChecker.download(this, info);
            runOnUiThread(() -> {
                dialog.dismiss();
                if (apk != null) {
                    UpdateChecker.install(this, apk);
                } else {
                    Toast.makeText(this, "下载失败，请稍后重试~", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // ==================== 播放状态监听 ====================

    private final PlayerManager.PlayerListener uiListener = new PlayerManager.PlayerListener() {
        @Override
        public void onSongChanged(Song song) {
            refreshMiniBar();
        }

        @Override
        public void onPlayStateChanged(boolean isPlaying) {
            ivMiniPlay.setImageResource(isPlaying
                    ? R.drawable.ic_pause : R.drawable.ic_play);
        }

        @Override
        public void onBuffering(boolean buffering) {
        }

        @Override
        public void onPlayListChanged() {
        }
    };
}
