package com.sandrox.avvessa;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import cn.jiguang.api.JCoreManager;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Exercises the pinned JPush dispatcher, without initializing the SDK or connecting to a server. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 35}, instrumentedPackages = {"cn.jpush.android"}, shadows = {
        NotificationReceiptTest.ProcessShadow.class, JPushNotificationDispatchTest.Notifications.class,
        JPushNotificationDispatchTest.NoJCoreNetwork.class})
public class JPushNotificationDispatchTest {
    private Application context;
    private Avvessa client;
    private EventStore store;
    private PushConfiguration config;
    private Context dispatchContext;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        dispatchContext = context;
        context.getApplicationInfo().icon = android.R.drawable.ic_dialog_info;
        context.getApplicationInfo().processName = Application.getProcessName();
        NotificationReceiptTest.ProcessShadow.foreground = true;
        Notifications.posted = null; Notifications.afterPost = null;
        config = new PushConfiguration.Builder("000000000000000000000000").smallIcon(android.R.drawable.ic_dialog_info)
                .presentationPolicy(new NotificationPolicy(Json.parse("{\"activeContext\":{\"match\":{\"kind\":\"chat\"},\"idField\":\"conversationId\"},\"suppressForeground\":[{\"match\":{\"kind\":\"quiet\"}}]}"))).build();
        store = new EventStore(context); store.state("configuration", config.toJson(context).toString()); store.state("consent", "true");
        client = Avvessa.getInstance(context);
        ReflectionHelpers.setField(client, "initialized", true);
        client.setActiveNotificationContext(Json.parse("{\"id\":\"a\"}"));
        // Only this fixture knows the pinned SDK's internals. An SDK upgrade must revalidate dispatch.
        Class<?> constants = Class.forName("cn.jpush.android.local.JPushConstants");
        ReflectionHelpers.setStaticField(constants, "mApplicationContext", context);
        ReflectionHelpers.setStaticField(constants, "messageReceiverClass", AvvessaReceiver.class.getName());
        ReflectionHelpers.setStaticField(constants, "jPushMessageReceiver", new AvvessaReceiver());
    }
    @After public void tearDown() {
        AvvessaPresentationProvider.clear();
        ((EventStore) ReflectionHelpers.getField(client, "store")).close(); store.close();
        ReflectionHelpers.setStaticField(Avvessa.class, "shared", null);
    }
    private void dispatch(String displayForeground, String extras) throws Exception {
        Class<?> entityClass = Class.forName("cn.jpush.android.d.d");
        Object entity = entityClass.getConstructor().newInstance();
        ReflectionHelpers.setField(entity, "d", "9007199254740993");
        ReflectionHelpers.setField(entity, "w", "Test notification");
        ReflectionHelpers.setField(entity, "z", "Test body");
        ReflectionHelpers.setField(entity, "H", extras);
        ReflectionHelpers.setField(entity, "K", displayForeground);
        Method show = Class.forName("cn.jpush.android.x.b").getMethod("b", Context.class, entityClass);
        show.invoke(null, dispatchContext, entity);
    }
    private JSONObject receipt() throws Exception {
        assertEquals(1, store.pending(config, System.currentTimeMillis()).size());
        return store.pending(config, System.currentTimeMillis()).get(0).toJson();
    }
    @Test public void defaultPayloadIsSilentBeforePostingAndRemovedWithoutLosingItsReceipt() throws Exception {
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertNotNull("The default SDK path posts a notification", Notifications.posted);
        assertEquals(Notification.GROUP_ALERT_SUMMARY, Notifications.posted.getGroupAlertBehavior());
        assertNotNull(Notifications.posted.getGroup());
        assertTrue(context.getSystemService(NotificationManager.class).getActiveNotifications().length == 0);
        JSONObject event = receipt();
        assertTrue(event.getJSONObject("presentation").getBoolean("suppressed"));
        assertEquals("activeContext", event.getJSONObject("presentation").getString("reason"));
        assertEquals("silentRemoval", event.getJSONObject("presentation").getString("method"));
        assertEquals("", event.getJSONObject("raw").getString("displayForeground"));
        assertEquals("a", event.getJSONObject("data").getString("conversationId"));
    }
    @Test public void explicitForegroundDisplayUsesTheSdkGateWithoutPosting() throws Exception {
        dispatch("1", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertNull(Notifications.posted);
        assertTrue(receipt().getJSONObject("presentation").getBoolean("suppressed"));
        assertEquals("blocked", receipt().getJSONObject("presentation").getString("method"));
    }
    @Test public void unrelatedMessageRetainsNormalPresentation() throws Exception {
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"b\"}");
        assertNotNull(Notifications.posted);
        assertEquals(Notification.GROUP_ALERT_ALL, Notifications.posted.getGroupAlertBehavior());
        assertEquals(1, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertFalse(receipt().getJSONObject("presentation").getBoolean("suppressed"));
    }
    @Test public void defaultPayloadHonorsStaticSuppression() throws Exception {
        dispatch("", "{\"kind\":\"quiet\"}");
        assertEquals(Notification.GROUP_ALERT_SUMMARY, Notifications.posted.getGroupAlertBehavior());
        assertEquals("rule", receipt().getJSONObject("presentation").getString("reason"));
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
    @Test public void globalForegroundDisableAlsoHandlesDefaultPayloads() throws Exception {
        ReflectionHelpers.setField(client, "configuration", new PushConfiguration.Builder("000000000000000000000000")
                .smallIcon(android.R.drawable.ic_dialog_info).presentInForeground(false).build());
        dispatch("", "{\"kind\":\"status\"}");
        assertEquals("foregroundDisabled", receipt().getJSONObject("presentation").getString("reason"));
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
    @Test public void backgroundNotificationsStillPostNormally() throws Exception {
        NotificationReceiptTest.ProcessShadow.foreground = false;
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertEquals(Notification.GROUP_ALERT_ALL, Notifications.posted.getGroupAlertBehavior());
        assertEquals(1, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
        assertFalse(receipt().getJSONObject("presentation").getBoolean("suppressed"));
    }
    @Test public void leavingContextRestoresPresentation() throws Exception {
        client.clearActiveNotificationContext();
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertEquals(Notification.GROUP_ALERT_ALL, Notifications.posted.getGroupAlertBehavior());
        assertFalse(receipt().getJSONObject("presentation").getBoolean("suppressed"));
    }
    @Test public void decisionSurvivesContextChangesBetweenBuildAndReceipt() throws Exception {
        Notifications.afterPost = () -> client.clearActiveNotificationContext();
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertEquals("activeContext", receipt().getJSONObject("presentation").getString("reason"));
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
    @Test public void suppressionLeavesOtherDeliveredNotificationsIntact() throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        PushNotifications.ensureDefaultChannel(context, config);
        Notification previous = new Notification.Builder(context, config.notificationChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Earlier notification").build();
        manager.notify(47, previous);
        dispatch("", "{\"kind\":\"quiet\"}");
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals(47, manager.getActiveNotifications()[0].getId());
        assertEquals("rule", receipt().getJSONObject("presentation").getString("reason"));
    }
    @Test public void delayedReceiptDoesNotRemoveAReplacementWithTheSameNotificationId() throws Exception {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notifications.afterPost = () -> {
            Notifications.afterPost = null;
            int id = manager.getActiveNotifications()[0].getId();
            Notification replacement = new Notification.Builder(context, config.notificationChannelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Replacement").build();
            manager.notify(id, replacement);
        };
        dispatch("", "{\"kind\":\"quiet\"}");
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals("Replacement", manager.getActiveNotifications()[0].getNotification().extras.getString(Notification.EXTRA_TITLE));
        assertEquals("rule", receipt().getJSONObject("presentation").getString("reason"));
    }
    @Test public void serviceDispatchQueriesTheApplicationProviderAndPreservesItsDecision() throws Exception {
        String authority = context.getPackageName() + ".avvessa.presentation";
        ProviderInfo info = new ProviderInfo(); info.authority = authority;
        AvvessaPresentationProvider provider = new AvvessaPresentationProvider();
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
        // Force the service-side branch while the provider retains the application's context.
        dispatchContext = new ContextWrapper(context) {
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo app = new ApplicationInfo(super.getApplicationInfo());
                app.processName = "application-process"; return app;
            }
        };
        dispatch("", "{\"kind\":\"chat\",\"conversationId\":\"a\"}");
        assertEquals(Notification.GROUP_ALERT_SUMMARY, Notifications.posted.getGroupAlertBehavior());
        assertEquals("activeContext", receipt().getJSONObject("presentation").getString("reason"));
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
    @Implements(NotificationManager.class)
    public static class Notifications extends ShadowNotificationManager {
        static Notification posted;
        static Runnable afterPost;
        @Implementation protected void notify(int id, Notification notification) {
            posted = notification;
            super.notify(id, notification);
            if (afterPost != null) afterPost.run();
        }
    }
    @Implements(value = JCoreManager.class, isInAndroidSdk = false)
    public static class NoJCoreNetwork {
        @Implementation protected static Object onEvent(Context context, String sdk, int action, String name, Bundle extras, Object... args) {
            return null;
        }
    }
}
