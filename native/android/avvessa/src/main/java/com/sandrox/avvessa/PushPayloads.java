package com.sandrox.avvessa;

import cn.jpush.android.api.CustomMessage;
import cn.jpush.android.api.NotificationMessage;
import org.json.JSONObject;

final class PushPayloads {
    private PushPayloads() {}
    static JSONObject notification(NotificationMessage message) {
        JSONObject raw = new JSONObject();
        Json.put(raw, "appkey", message.appkey); Json.put(raw, "appId", message.appId);
        Json.put(raw, "msgId", message.msgId); Json.put(raw, "notificationId", message.notificationId);
        Json.put(raw, "notificationTitle", message.notificationTitle); Json.put(raw, "notificationContent", message.notificationContent);
        Json.put(raw, "notificationExtras", message.notificationExtras); Json.put(raw, "platform", message.platform);
        Json.put(raw, "notificationChannelId", message.notificationChannelId); Json.put(raw, "notificationType", message.notificationType);
        Json.put(raw, "notificationSmallIcon", message.notificationSmallIcon); Json.put(raw, "notificationLargeIcon", message.notificationLargeIcon);
        Json.put(raw, "notificationBigText", message.notificationBigText); Json.put(raw, "notificationBigPicPath", message.notificationBigPicPath);
        Json.put(raw, "notificationInbox", message.notificationInbox); Json.put(raw, "notificationStyle", message.notificationStyle);
        Json.put(raw, "notificationAlertType", message.notificationAlertType); Json.put(raw, "notificationPriority", message.notificationPriority);
        Json.put(raw, "notificationImportance", message.notificationImportance); Json.put(raw, "notificationCategory", message.notificationCategory);
        Json.put(raw, "displayForeground", message.displayForeground); Json.put(raw, "deeplink", message.deeplink);
        Json.put(raw, "notificationNormalSmallIcon", message.notificationNormalSmallIcon);
        Json.put(raw, "notificationBuilderId", message.notificationBuilderId); Json.put(raw, "developerArg0", message.developerArg0);
        Json.put(raw, "_webPagePath", message._webPagePath);
        Json.put(raw, "showResourceList", message.showResourceList == null ? null : new org.json.JSONArray(message.showResourceList));
        Json.put(raw, "isRichPush", message.isRichPush); Json.put(raw, "richType", message.richType);
        Json.put(raw, "failedAction", message.failedAction); Json.put(raw, "failedLink", message.failedLink);
        Json.put(raw, "targetPkgName", message.targetPkgName); Json.put(raw, "sspWxAppId", message.sspWxAppId);
        Json.put(raw, "sspWmOriginId", message.sspWmOriginId); Json.put(raw, "sspWmType", message.sspWmType);
        Json.put(raw, "isWmDeepLink", message.isWmDeepLink); Json.put(raw, "inAppMsgType", message.inAppMsgType);
        Json.put(raw, "inAppMsgShowType", message.inAppMsgShowType); Json.put(raw, "inAppMsgShowPos", message.inAppMsgShowPos);
        Json.put(raw, "inAppMsgTitle", message.inAppMsgTitle); Json.put(raw, "inAppMsgContentBody", message.inAppMsgContentBody);
        Json.put(raw, "inAppType", message.inAppType); Json.put(raw, "inAppShowTarget", message.inAppShowTarget);
        Json.put(raw, "inAppClickAction", message.inAppClickAction); Json.put(raw, "inAppExtras", message.inAppExtras);
        Json.put(raw, "notificationTargetEvent", message.notificationTargetEvent); Json.put(raw, "customButtons", message.customButtons);
        return raw;
    }
    static JSONObject custom(CustomMessage message) {
        JSONObject raw = new JSONObject();
        Json.put(raw, "messageId", message.messageId); Json.put(raw, "message", message.message);
        Json.put(raw, "extra", message.extra); Json.put(raw, "title", message.title);
        Json.put(raw, "contentType", message.contentType); Json.put(raw, "platform", message.platform);
        Json.put(raw, "senderId", message.senderId); Json.put(raw, "appId", message.appId);
        return raw;
    }
    static String source(int platform) { return "jpush:" + platform; }
    static JSONObject extras(Avvessa client, String value) {
        try { return Json.extras(value); }
        catch (PushException error) {
            client.report(new PushException("invalid_payload", "SDK extras are not a JSON object; the original value remains in raw", error));
            return null;
        }
    }
    static void receiveNotification(Avvessa client, String type, JSONObject raw) {
        client.receive(type, raw.isNull("msgId") ? null : raw.optString("msgId"), source(raw.optInt("platform")),
                extras(client, raw.isNull("notificationExtras") ? null : raw.optString("notificationExtras")), raw,
                raw.isNull("notificationTitle") ? null : raw.optString("notificationTitle"),
                raw.isNull("notificationContent") ? null : raw.optString("notificationContent"));
    }
}
