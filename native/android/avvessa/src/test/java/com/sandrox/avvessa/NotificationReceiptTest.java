package com.sandrox.avvessa;

import android.app.ActivityManager;
import android.app.Application;
import cn.jpush.android.api.NotificationMessage;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = NotificationReceiptTest.ProcessShadow.class)
public class NotificationReceiptTest {
    private Application context;
    private EventStore store;
    private Avvessa client;
    private PushConfiguration config;
    private final AvvessaReceiver receiver = new AvvessaReceiver();
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication(); ProcessShadow.foreground = true;
        context.getApplicationInfo().processName = Application.getProcessName();
        config = new PushConfiguration.Builder("000000000000000000000000").smallIcon(android.R.drawable.ic_dialog_info)
                .presentationPolicy(new NotificationPolicy(Json.parse("{\"activeContext\":{\"match\":{\"kind\":\"chat\"},\"idField\":\"conversationId\"}}"))).build();
        store = new EventStore(context); store.state("configuration", config.toJson(context).toString()); store.state("consent", "true");
        client = Avvessa.getInstance(context);
        // Exercise native receipt/selection only, without invoking JPush initialization or services.
        ReflectionHelpers.setField(client, "initialized", true);
        client.setActiveNotificationContext(Json.parse("{\"id\":\"a\"}"));
    }
    @After public void tearDown() {
        AvvessaPresentationProvider.clear();
        ((EventStore) ReflectionHelpers.getField(client, "store")).close(); store.close();
        ReflectionHelpers.setStaticField(Avvessa.class,"shared",null);
    }
    private NotificationMessage message(String id) {
        NotificationMessage message = new NotificationMessage(); message.msgId = id; message.notificationId = -123;
        message.notificationExtras = "{\"kind\":\"chat\",\"conversationId\":\"a\"}"; return message;
    }
    private List<PushEvent> pending() { return store.pending(config,System.currentTimeMillis()); }
    @Test public void suppressionKeepsOneReceiptAndFreezesTheDecisionBeforeContextChanges() throws Exception {
        NotificationMessage message = message("9007199254740993");
        assertFalse(receiver.isNeedShowNotification(context,message,"jpush"));
        client.clearActiveNotificationContext();
        receiver.onNotifyMessageUnShow(context,message);
        receiver.onNotifyMessageArrived(context,message);
        assertEquals(1,pending().size());
        JSONObject event = pending().get(0).toJson();
        assertEquals("notificationReceived",event.getString("type"));
        assertEquals("9007199254740993",event.getString("messageId"));
        assertEquals("activeContext",event.getJSONObject("presentation").getString("reason"));
        assertEquals(-123,event.getInt("notificationId"));
        assertEquals("a",event.getJSONObject("data").getString("conversationId"));
        assertTrue(receiver.isNeedShowNotification(context,message,"jpush"));
    }
    @Test public void foregroundRulesNeverSuppressBackgroundOrAnUnrelatedMessage() {
        NotificationMessage message = message("one"); ProcessShadow.foreground = false;
        assertTrue(receiver.isNeedShowNotification(context,message,"jpush"));
        ProcessShadow.foreground = true; message.notificationExtras = "{\"kind\":\"status\",\"conversationId\":\"a\"}";
        assertTrue(receiver.isNeedShowNotification(context,message,"jpush"));
    }
    @Test public void receiptAliasesLinkMissingMessageIdsButDoNotSwallowReusedNotificationIds() {
        NotificationMessage message = message(null);
        assertFalse(receiver.isNeedShowNotification(context,message,"jpush")); receiver.onNotifyMessageUnShow(context,message);
        message.msgId = "one"; receiver.onNotifyMessageArrived(context,message);
        assertEquals(1,pending().size());
        message.platform = 3; receiver.onNotifyMessageArrived(context,message);
        assertEquals(1,pending().size());
        message.msgId = "two"; receiver.onNotifyMessageArrived(context,message);
        assertEquals(2,pending().size());
        client.clearPendingEvents(); receiver.onNotifyMessageArrived(context,message);
        assertTrue(pending().isEmpty());
    }
    @Implements(ActivityManager.class)
    public static class ProcessShadow {
        static boolean foreground;
        @Implementation protected static void getMyMemoryState(ActivityManager.RunningAppProcessInfo info) {
            info.importance = foreground ? ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND : ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;
        }
    }
}
