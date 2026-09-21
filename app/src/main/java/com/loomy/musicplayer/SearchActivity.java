package com.loomy.musicplayer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.loomy.musicplayer.adapter.SongAdapter;
import com.loomy.musicplayer.api.KuWoApi;
import com.loomy.musicplayer.model.Song;
import com.loomy.musicplayer.utils.HttpUtils;
import com.loomy.musicplayer.utils.PlayerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线音乐搜索界面。
 * 通过酷我开放接口搜索歌曲，支持：
 *  1. 点击歌曲在线播放（自动获取真实播放地址）
 *  2. 右侧按钮下载歌曲到应用私有目录
 *  3. 滚动到底部自动加载下一页
 */
public class SearchActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 20;

    private EditText etKeyword;
    private RecyclerView rvResult;
    private ProgressBar progressBar;
    private TextView tvTip;

    private final PlayerManager player = PlayerManager.get();
    private final List<Song> results = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 列表回调：播放 & 下载（必须在 adapter 之前声明） */
    private final SongAdapter.OnSongClickListener listener = new SongAdapter.OnSongClickListener() {
        @Override
        public void onSongClick(Song song, int position) {
            // 在线播放整列表，从点击处开始
            PlaybackService.start(SearchActivity.this);
            player.play(new ArrayList<>(results), position);
            startActivity(new Intent(SearchActivity.this, PlayerActivity.class));
        }

        @Override
        public boolean onActionClick(Song song, int position) {
            downloadSong(song);
            return true;
        }
    };

    private final SongAdapter adapter = new SongAdapter(listener);

    private int currentPage = 1;
    private boolean loading = false;
    private boolean hasMore = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("在线音乐");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        etKeyword = findViewById(R.id.et_keyword);
        rvResult = findViewById(R.id.rv_result);
        progressBar = findViewById(R.id.progress_bar);
        tvTip = findViewById(R.id.tv_tip);
        ImageButton btnSearch = findViewById(R.id.btn_search);

        rvResult.setLayoutManager(new LinearLayoutManager(this));
        rvResult.setAdapter(adapter);

        // 键盘“搜索”按钮
        etKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        btnSearch.setOnClickListener(v -> doSearch());

        // 滚动到底部自动加载下一页
        rvResult.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3) {
                    loadMore();
                }
            }
        });

        // 尝试预填上一次关键词（简单演示：直接聚焦）
        etKeyword.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ==================== 搜索 ====================

    private void doSearch() {
        String keyword = etKeyword.getText().toString().trim();
        if (TextUtils.isEmpty(keyword)) {
            Toast.makeText(this, "请输入歌曲名或歌手哦~", Toast.LENGTH_SHORT).show();
            return;
        }
        // 收起键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etKeyword.getWindowToken(), 0);
        }

        currentPage = 1;
        hasMore = true;
        results.clear();
        adapter.setData(results);
        searchPage(keyword, 1, true);
    }

    private void loadMore() {
        if (loading || !hasMore || results.isEmpty()) {
            return;
        }
        searchPage(etKeyword.getText().toString().trim(), currentPage + 1, false);
    }

    private void searchPage(String keyword, int page, boolean firstPage) {
        if (loading || TextUtils.isEmpty(keyword)) {
            return;
        }
        loading = true;
        if (firstPage) {
            progressBar.setVisibility(View.VISIBLE);
            tvTip.setVisibility(View.GONE);
        }

        executor.execute(() -> {
            List<Song> pageSongs = KuWoApi.search(keyword, page, PAGE_SIZE);
            mainHandler.post(() -> {
                loading = false;
                progressBar.setVisibility(View.GONE);

                if (pageSongs.isEmpty()) {
                    hasMore = false;
                    if (firstPage) {
                        tvTip.setVisibility(View.VISIBLE);
                        tvTip.setText("没有找到相关歌曲，换个关键词试试~");
                    }
                    return;
                }
                if (firstPage) {
                    tvTip.setVisibility(View.GONE);
                }
                currentPage = page;
                results.addAll(pageSongs);
                adapter.setData(results);
            });
        });
    }

    // ==================== 下载 ====================

    private void downloadSong(Song song) {
        Toast.makeText(this, "正在解析下载地址：" + song.getTitle(), Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            // 1. 解析播放地址
            String playUrl = KuWoApi.resolvePlayUrl(String.valueOf(song.getId()));
            if (playUrl == null) {
                mainHandler.post(() -> Toast.makeText(this,
                        "获取下载地址失败，可能是版权限制~", Toast.LENGTH_SHORT).show());
                return;
            }
            // 2. 下载到应用私有外部目录（无需额外权限）
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "MusicPlayer");
            String safeName = song.getTitle().replaceAll("[\\\\/:*?\"<>|]", "_");
            java.io.File target = new java.io.File(dir, safeName + ".mp3");
            boolean ok = HttpUtils.download(playUrl, target.getAbsolutePath());
            mainHandler.post(() -> {
                if (ok) {
                    Toast.makeText(this, "已下载到：" + target.getAbsolutePath(),
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "下载失败，请稍后重试~", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }
}
