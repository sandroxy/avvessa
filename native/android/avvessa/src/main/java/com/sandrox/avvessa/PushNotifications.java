package com.sandrox.avvessa;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import cn.jpush.android.api.BasicPushNotificationBuilder;
import cn.jpush.android.api.NotificationMessage;
import java.util.UUID;

final class PushNotifications {
    static final String PAYLOAD = "com.sandrox.avvessa.NOTIFICATION";
    private PushNotifications() {}

    static boolean foreground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }
    static void ensureDefaultChannel(Context context, PushConfiguration config) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager.getNotificationChannel(config.notificationChannelId) == null)
                manager.createNotificationChannel(new NotificationChannel(config.notificationChannelId,
                        config.notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT));
        }
    }
    static Notification build(Context context, PushConfiguration config, NotificationMessage message) {
        ensureDefaultChannel(context, config);
        BasicPushNotificationBuilder sdk = new BasicPushNotificationBuilder(context);
        sdk.statusBarDrawable = config.smallIcon;
        Notification notification = sdk.buildNotification(context, message);
        if (notification == null) throw new PushException("notification_build", "JPush could not build this notification");
        String identity = message.msgId == null || message.msgId.isEmpty() ? UUID.randomUUID().toString() : message.msgId;
        Intent intent = new Intent(context, AvvessaNotificationActivity.class)
                .setData(new Uri.Builder().scheme("avvessa").authority("notification").appendPath(identity).build())
                .putExtra(PAYLOAD, PushPayloads.notification(message).toString());
        notification.contentIntent = PendingIntent.getActivity(context, message.notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification.flags |= Notification.FLAG_AUTO_CANCEL;
        if (config.color != 0) notification.color = config.color;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            String channel = message.notificationChannelId;
            if (channel == null || manager.getNotificationChannel(channel) == null) channel = config.notificationChannelId;
            notification = Notification.Builder.recoverBuilder(context, notification).setChannelId(channel).build();
        }
        return notification;
    }
    static void openApplication(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (intent == null) throw new PushException("host_unavailable", "The application has no launcher Activity");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            try {
                // JPush can open the host before delivering the click callback. Restore that
                // task without relaunching a transient launcher or removing its destination.
                if (intent.getComponent().equals(task.getTaskInfo().baseIntent.getComponent())) {
                    task.moveToFront();
                    return;
                }
            } catch (IllegalArgumentException removedTask) {
                // A task can disappear between enumeration and the platform operation.
            }
        }
        // A launcher may forward to another Activity. CLEAR_TOP/SINGLE_TOP can destroy
        // that page and reuse the launcher while it is already finishing.
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
