package com.landeting;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.landeting.model.Song;
import com.landeting.utils.PlayerManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台播放服务。
 *
 * 职责：
 *  1. 以“媒体播放”前台服务方式常驻后台，保证锁屏/切后台继续播放
 *  2. 维护通知栏控制（播放/暂停、上一首、下一首、关闭）
 *  3. 接收通知栏按钮的广播并转发给 PlayerManager
 */
public class PlaybackService extends Service {

    // ---- 动作常量 ----
    public static final String ACTION_PLAY = "com.landeting.action.PLAY";
    public static final String ACTION_TOGGLE = "com.landeting.action.TOGGLE";
    public static final String ACTION_NEXT = "com.landeting.action.NEXT";
    public static final String ACTION_PREV = "com.landeting.action.PREV";
    public static final String ACTION_STOP = "com.landeting.action.STOP";
    public static final String ACTION_OPEN_PLAYER = "com.landeting.action.OPEN_PLAYER";

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final PlayerManager player = PlayerManager.get();
    private NotificationManager notificationManager;
    private ExecutorService coverExecutor;

    /**
     * 启动播放服务（UI 在开始播放前调用）。
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, PlaybackService.class);
        intent.setAction(ACTION_PLAY);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        coverExecutor = Executors.newSingleThreadExecutor();
        createNotificationChannel();

        // 监听播放状态，随时同步通知栏
        player.addListener(stateListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handleAction(intent.getAction());
        }
        // 立即进入前台（必须在 startForegroundService 后 5 秒内完成）
        startForegroundCompat();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        player.removeListener(stateListener);
        if (coverExecutor != null) {
            coverExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    // ==================== 动作处理 ====================

    private void handleAction(String action) {
        switch (action) {
            case ACTION_TOGGLE:
                player.toggle();
                break;
            case ACTION_NEXT:
                player.next();
                break;
            case ACTION_PREV:
                player.prev();
                break;
            case ACTION_STOP:
                // 关闭按钮：暂停播放并退出前台服务
                if (player.isPlaying()) {
                    player.toggle();
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
            case ACTION_OPEN_PLAYER:
                openPlayer();
                break;
            case ACTION_PLAY:
            default:
                // 启动服务即刷新通知
                updateNotification();
                break;
        }
    }

    private void openPlayer() {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    // ==================== 前台服务 ====================

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：声明前台服务类型（媒体播放）
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "音乐播放",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("正在播放的音乐");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    // ==================== 通知栏 ====================

    private void updateNotification() {
        // 服务可能因 STOP 正在退出，若已销毁则忽略
        if (notificationManager == null) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Song song = player.getCurrentSong();
        String title = song != null ? song.getTitle() : "未在播放";
        String text = song != null ? song.getArtist() : "打开音乐播放器开始享受音乐吧";

        // 内容点击 → 打开播放页
        PendingIntent openPi = PendingIntent.getBroadcast(
                this, 0,
                new Intent(this, NotificationReceiver.class).setAction(ACTION_OPEN_PLAYER),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 播放/暂停按钮
        boolean playing = player.isPlaying();
        int playIcon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        PendingIntent togglePi = PendingIntent.getBroadcast(
                this, 1,
                new Intent(this, NotificationReceiver.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 上一首 / 下一首
        PendingIntent prevPi = PendingIntent.getBroadcast(
                this, 2,
                new Intent(this, NotificationReceiver.class).setAction(ACTION_PREV),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextPi = PendingIntent.getBroadcast(
                this, 3,
                new Intent(this, NotificationReceiver.class).setAction(ACTION_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 关闭按钮
        PendingIntent stopPi = PendingIntent.getBroadcast(
                this, 4,
                new Intent(this, NotificationReceiver.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(playing)                    // 播放中不可滑动清除
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(new NotificationCompat.Action(R.drawable.ic_prev, "上一首", prevPi))
                .addAction(new NotificationCompat.Action(playIcon, playing ? "暂停" : "播放", togglePi))
                .addAction(new NotificationCompat.Action(R.drawable.ic_next, "下一首", nextPi))
                .addAction(new NotificationCompat.Action(R.drawable.ic_close, "关闭", stopPi));

        // 封面图：有 URL 则异步加载，否则使用默认封面
        if (song != null && song.getCoverUrl() != null && !song.getCoverUrl().isEmpty()) {
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_album_placeholder));
            loadCoverAsync(song.getCoverUrl(), builder);
        } else {
            builder.setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_album_placeholder));
        }
        return builder.build();
    }

    /** 异步加载封面并更新通知（失败时静默忽略） */
    private void loadCoverAsync(String url, NotificationCompat.Builder builder) {
        coverExecutor.execute(() -> {
            try {
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
                try (okhttp3.Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return;
                    }
                    Bitmap bitmap = BitmapFactory.decodeStream(response.body().byteStream());
                    if (bitmap != null) {
                        builder.setLargeIcon(bitmap);
                        updateNotification();
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ==================== 状态监听 ====================

    private final PlayerManager.PlayerListener stateListener = new PlayerManager.PlayerListener() {
        @Override
        public void onSongChanged(Song song) {
            updateNotification();
        }

        @Override
        public void onPlayStateChanged(boolean isPlaying) {
            updateNotification();
        }

        @Override
        public void onBuffering(boolean buffering) {
            // 缓冲中不需要更新通知
        }

        @Override
        public void onPlayListChanged() {
        }
    };
}
