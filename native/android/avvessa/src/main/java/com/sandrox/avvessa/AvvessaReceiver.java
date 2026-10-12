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
    private static final java.util.Map<String, Decision> decisions = new java.util.LinkedHashMap<>();
    private static String identity(NotificationMessage message) { return message.platform + ":" + message.msgId + ":" + message.notificationId; }
    private static synchronized void remember(NotificationMessage message, String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Iterator<Decision> iterator = decisions.values().iterator();
        while (iterator.hasNext()) if (now - iterator.next().time > 60000) iterator.remove();
        if (decisions.size() >= 256) decisions.remove(decisions.keySet().iterator().next());
        decisions.put(identity(message), new Decision(reason, now));
    }
    private static synchronized String takeReason(NotificationMessage message) {
        Decision decision = decisions.remove(identity(message));
        return decision != null && android.os.SystemClock.elapsedRealtime() - decision.time <= 60000 ? decision.reason : "sdk";
    }
    private static final class Decision {
        final String reason; final long time;
        Decision(String reason, long time) { this.reason = reason; this.time = time; }
    }
    @Override public boolean isNeedShowNotification(Context context, NotificationMessage message, String source) {
        Avvessa client = Avvessa.getInstance(context);
        if (!client.mayReceive()) return false;
        String reason = client.suppressionReason(PushPayloads.extras(client, message.notificationExtras), PushNotifications.foreground());
        if (reason != null) {
            // Freeze the decision before the page/context can change; the SDK then calls onNotifyMessageUnShow.
            remember(message, reason);
        }
        return reason == null;
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
        PushPayloads.receiveNotification(Avvessa.getInstance(context), "notificationReceived", PushPayloads.notification(message), presentation(true, takeReason(message)));
    }
    private static JSONObject presentation(boolean suppressed, String reason) {
        JSONObject result = new JSONObject(); Json.put(result, "suppressed", suppressed); Json.put(result, "reason", reason); return result;
    }
    private void notification(Context context, String type, NotificationMessage message) {
        PushPayloads.receiveNotification(Avvessa.getInstance(context), type, PushPayloads.notification(message),
                type.equals("notificationReceived") ? presentation(false, null) : null);
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
        // getConnectionState reports 0/1 as connected/disconnected, not success/failure.
        // onConnected delivers transitions; querying again here would repeat this callback.
        if (message.cmd == 2004 && (message.errorCode == 0 || message.errorCode == 1)) return;
        if (message.errorCode != 0) Avvessa.getInstance(context).report(new PushException(
                "sdk_error", "JPush command " + message.cmd + " failed", message.errorCode, null));
    }
    @Override public void onNotificationSettingsCheck(Context context, boolean enabled, int source) { Avvessa.getInstance(context).stateChanged(); }
    @Override public void onAliasOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("alias", message); }
    @Override public void onTagOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("tags", message); }
    @Override public void onCheckTagOperatorResult(Context context, JPushMessage message) { Avvessa.getInstance(context).operationResult("checkTag", message); }
}
