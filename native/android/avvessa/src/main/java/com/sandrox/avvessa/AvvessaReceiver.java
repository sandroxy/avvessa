package com.sandrox.avvessa;

import android.app.Notification;
import android.content.Context;
import cn.jpush.android.api.CustomMessage;
import cn.jpush.android.api.CmdMessage;
import cn.jpush.android.api.JPushMessage;
import cn.jpush.android.api.NotificationMessage;
import cn.jpush.android.api.VoipDataMessage;
import cn.jpush.android.service.JPushMessageReceiver;
import org.json.JSONObject;

public final class AvvessaReceiver extends JPushMessageReceiver {
    @Override public boolean isNeedShowNotification(Context context, NotificationMessage message, String source) {
        Avvessa client = Avvessa.getInstance(context);
        return client.mayReceive() && (client.configuration().presentInForeground || !PushNotifications.foreground());
    }
    @Override public Notification getNotification(Context context, NotificationMessage message) {
        Avvessa client = Avvessa.getInstance(context);
        if (!client.mayReceive()) return null;
        try { return PushNotifications.build(context, client.configuration(), message); }
        catch (RuntimeException error) {
            client.report(new PushException("notification_build", "Cannot build notification", error));
            return null;
        }
    }
    @Override public void onNotifyMessageArrived(Context context, NotificationMessage message) {
        notification(context, "notificationReceived", message);
    }
    @Override public void onNotifyMessageOpened(Context context, NotificationMessage message) {
        Avvessa client = Avvessa.getInstance(context);
        notification(context, "notificationOpened", message);
        if (client.mayReceive() && client.configuration().openApplicationOnClick) {
            try { PushNotifications.openApplication(context); }
            catch (RuntimeException error) { client.report(new PushException("notification_open", "Cannot open application", error)); }
        }
    }
    @Override public void onNotifyMessageDismiss(Context context, NotificationMessage message) {
        notification(context, "notificationDismissed", message);
    }
    @Override public void onNotifyMessageUnShow(Context context, NotificationMessage message) {
        notification(context, "notificationSuppressed", message);
    }
    private void notification(Context context, String type, NotificationMessage message) {
        PushPayloads.receiveNotification(Avvessa.getInstance(context), type, PushPayloads.notification(message));
    }
    @Override public void onMessage(Context context, CustomMessage message) {
        Avvessa client = Avvessa.getInstance(context);
        client.receive("customMessage", message.messageId, PushPayloads.source(message.platform),
                PushPayloads.extras(client, message.extra), PushPayloads.custom(message), message.title, message.message);
    }
    @Override public void onVoipMessage(Context context, VoipDataMessage message) {
        JSONObject raw = new JSONObject(); Json.put(raw, "messageId", message.getMessageId());
        Json.put(raw, "extraData", message.getExtraData()); Json.put(raw, "platform", message.getPlatform());
        Json.put(raw, "voipSupportStatus", message.getVoipSupportStatus());
        Avvessa client = Avvessa.getInstance(context);
        client.receive("voipMessage", message.getMessageId(), PushPayloads.source(message.getPlatform()),
                PushPayloads.extras(client, message.getExtraData()), raw, null, null);
    }
    @Override public void onRegister(Context context, String id) { Avvessa.getInstance(context).registrationChanged(id); }
    @Override public void onConnected(Context context, boolean connected) { Avvessa.getInstance(context).stateChanged(); }
    @Override public void onCommandResult(Context context, CmdMessage message) {
        if (message.errorCode != 0) Avvessa.getInstance(context).report(new PushException(
                "sdk_error", "JPush command " + message.cmd + " failed", message.errorCode, null));
    }
    @Override public void onNotificationSettingsCheck(Context context, boolean enabled, int source) { Avvessa.getInstance(context).stateChanged(); }
    @Override public void onAliasOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("alias", message); }
    @Override public void onTagOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("tags", message); }
    @Override public void onCheckTagOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("checkTag", message); }
}
