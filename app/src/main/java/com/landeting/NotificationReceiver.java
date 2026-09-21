package com.landeting;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 通知栏按钮广播接收器。
 * 收到按钮点击后，把动作原样转发给 PlaybackService 处理。
 * （Android 8.0+ 要求前台服务必须通过 startForegroundService 启动）
 */
public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        Intent serviceIntent = new Intent(context, PlaybackService.class);
        serviceIntent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
