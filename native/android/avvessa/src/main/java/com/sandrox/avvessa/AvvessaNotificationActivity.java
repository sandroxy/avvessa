package com.sandrox.avvessa;

import android.app.Activity;
import android.app.NotificationManager;
import android.os.Bundle;
import cn.jpush.android.api.JPushInterface;
import org.json.JSONObject;

/** A direct PendingIntent Activity avoids notification-to-broadcast launch trampolines. */
public final class AvvessaNotificationActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Avvessa client = Avvessa.getInstance(this);
        try {
            String payload = getIntent().getStringExtra(PushNotifications.PAYLOAD);
            if (payload != null && client.mayReceive()) {
                JSONObject raw = Json.parse(payload);
                PushPayloads.receiveNotification(client, "notificationOpened", raw);
                if (!raw.isNull("msgId")) JPushInterface.reportNotificationOpened(this,
                        raw.optString("msgId"), (byte) raw.optInt("platform"));
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(raw.optInt("notificationId"));
                if (client.configuration().openApplicationOnClick) PushNotifications.openApplication(this);
            }
        } catch (RuntimeException error) {
            client.report(error instanceof PushException ? (PushException) error
                    : new PushException("notification_open", "Cannot process notification click", error));
        } finally { finish(); }
    }
}
