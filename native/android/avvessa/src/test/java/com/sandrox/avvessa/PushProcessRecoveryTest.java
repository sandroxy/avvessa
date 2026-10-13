package com.sandrox.avvessa;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import cn.jiguang.internal.JCoreInternalHelper;
import cn.jiguang.internal.JConstants;
import cn.jpush.android.data.JPushConfig;
import cn.jpush.android.service.JLocalNotifyActivity;
import cn.jpush.android.service.JNotifyActivity;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Runs the pinned SDK's public init and AppKey check; service/network work stays inside a shadow. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 35}, instrumentedPackages = {"cn.jpush.android", "cn.jiguang"},
        shadows = PushProcessRecoveryTest.NoServiceWork.class)
public class PushProcessRecoveryTest {
    private static final String APP_KEY = "000000000000000000000000";
    private Application context;
    private EventStore store;
    private PushConfiguration config;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        context.getApplicationInfo().icon = android.R.drawable.ic_dialog_info;
        context.getApplicationInfo().metaData = new Bundle();
        context.getApplicationInfo().metaData.putString("JPUSH_APPKEY", "");
        context.getApplicationInfo().processName = Application.getProcessName();
        config = new PushConfiguration.Builder(APP_KEY).channel("test-channel")
                .smallIcon(android.R.drawable.ic_dialog_info).presentInForeground(false).build();
        store = new EventStore(context);
        NoServiceWork.actions.clear();
        NoServiceWork.unconfiguredOperations.clear(); NoServiceWork.initialization = null;
        JConstants.APP_KEY = "";
        // Model the SDK's cached debug-certificate check without bundling a signing certificate.
        ReflectionHelpers.setStaticField(Class.forName("cn.jiguang.aw.a"), "d", true);
        // The real SDK init validates host components; no startup providers are installed by the fixture.
        for (Class<?> type : new Class<?>[]{JNotifyActivity.class, JLocalNotifyActivity.class}) {
            ResolveInfo info = new ResolveInfo(); info.activityInfo = new ActivityInfo();
            info.activityInfo.name = type.getName(); info.activityInfo.packageName = context.getPackageName();
            info.activityInfo.applicationInfo = context.getApplicationInfo();
            shadowOf(context.getPackageManager()).addResolveInfoForIntent(new Intent(context, type), info);
            if (type == JNotifyActivity.class)
                shadowOf(context.getPackageManager()).addResolveInfoForIntent(new Intent("cn.jpush.android.intent.JNotifyActivity")
                        .addCategory(Intent.CATEGORY_DEFAULT).addCategory(context.getPackageName()), info);
        }
        ReflectionHelpers.setStaticField(Class.forName("cn.jpush.android.local.JPushConstants"),
                "messageReceiverClass", AvvessaReceiver.class.getName());
    }
    @After public void tearDown() {
        Avvessa client = ReflectionHelpers.getStaticField(Avvessa.class, "shared");
        if (client != null) ((EventStore) ReflectionHelpers.getField(client, "store")).close();
        ReflectionHelpers.setStaticField(Avvessa.class, "shared", null);
        store.close();
    }
    private void saveInitialization() {
        store.state("consent", "true"); store.saveConfiguration(config.toJson(context).toString());
    }
    private <T extends AvvessaPrivacyProvider> T start(T provider) {
        ProviderInfo info = new ProviderInfo(); info.authority = context.getPackageName() + ".avvessa.pushprivacy";
        provider.attachInfo(context, info); return provider;
    }
    private int checkSdkAppKey() throws Exception {
        // This exact SDK check emitted error 10001 and notification 10000 on the physical device.
        return (Integer) Class.forName("cn.jiguang.w.a").getMethod("e", Context.class).invoke(null, context);
    }
    private boolean sdkConsent() throws Exception {
        return (Boolean) Class.forName("cn.jiguang.aw.b").getMethod("a").invoke(null);
    }
    private long registrations() { return NoServiceWork.actions.stream().filter("a5"::equals).count(); }

    @Test public void sdkMissingKeyCheckReproducesTheDiagnosticNotification() throws Exception {
        assertEquals(10001, checkSdkAppKey());
        assertNotNull(shadowOf(context.getSystemService(NotificationManager.class)).getNotification(10000));
    }
    @Test public void serviceStartupRestoresDynamicKeyBeforeItsFirstSdkCheck() throws Exception {
        saveInitialization();
        start(new AvvessaPushPrivacyProvider());
        assertEquals(APP_KEY, JConstants.APP_KEY);
        assertEquals(0, checkSdkAppKey());
        assertTrue(sdkConsent());
        assertEquals(1, registrations());
        assertTrue("Configuration calls must not start JCore before its dynamic identity exists",
                NoServiceWork.unconfiguredOperations.isEmpty());
        assertNull(shadowOf(context.getSystemService(NotificationManager.class)).getNotification(10000));
    }
    @Test public void serviceRestorationIncludesSelectedVendorCredentials() {
        Bundle data = context.getApplicationInfo().metaData;
        data.putBoolean("com.sandrox.avvessa.vendor.xiaomi", true);
        data.putString("XIAOMI_APPID", "test-id"); data.putString("XIAOMI_APPKEY", "test-key");
        saveInitialization(); start(new AvvessaPushPrivacyProvider());
        assertNotNull(NoServiceWork.initialization);
        assertEquals(APP_KEY, NoServiceWork.initialization.getjAppKey());
        assertEquals("test-id", NoServiceWork.initialization.getXmAppId());
        assertEquals("test-key", NoServiceWork.initialization.getXmAppKey());
    }
    @Test public void newServiceProcessRestoresEvenWhileTheApplicationConfigurationIsUnchanged() throws Exception {
        saveInitialization(); start(new AvvessaPushPrivacyProvider());
        JConstants.APP_KEY = ""; // Model loss of process-local SDK state, without losing durable app data.
        start(new AvvessaPushPrivacyProvider());
        assertEquals(0, checkSdkAppKey());
        assertEquals(APP_KEY, JConstants.APP_KEY);
        assertEquals(2, registrations());
    }
    @Test public void consentSynchronizationDoesNotRegisterTheSameServiceRepeatedly() {
        saveInitialization();
        AvvessaPushPrivacyProvider provider = start(new AvvessaPushPrivacyProvider());
        provider.call("syncConsent", null, null); provider.call("syncConsent", null, null);
        assertEquals(1, registrations());
    }
    @Test public void absentConsentDoesNotRestorePersistedIdentity() throws Exception {
        store.saveConfiguration(config.toJson(context).toString());
        start(new AvvessaPushPrivacyProvider());
        assertFalse(sdkConsent()); assertEquals("", JConstants.APP_KEY); assertEquals(0, registrations());
    }
    @Test public void consentAloneDoesNotInitializeTheService() throws Exception {
        store.state("consent", "true"); start(new AvvessaPushPrivacyProvider());
        assertFalse(sdkConsent()); assertEquals("", JConstants.APP_KEY); assertEquals(0, registrations());
    }
    @Test public void primaryProviderLeavesExplicitInitializationToTheHost() throws Exception {
        saveInitialization(); start(new AvvessaPrivacyProvider());
        assertTrue(sdkConsent()); assertEquals("", JConstants.APP_KEY); assertEquals(0, registrations());
    }
    @Test public void revocationBlocksRestorationUntilExplicitInitializationIsSavedAgain() throws Exception {
        saveInitialization(); AvvessaPushPrivacyProvider provider = start(new AvvessaPushPrivacyProvider());
        store.revokeConsent(); provider.call("syncConsent", null, null);
        assertFalse(sdkConsent());
        store.state("consent", "true"); provider.call("syncConsent", null, null);
        assertFalse(sdkConsent()); assertEquals(1, registrations());
        store.saveConfiguration(config.toJson(context).toString()); provider.call("syncConsent", null, null);
        assertTrue(sdkConsent()); assertEquals(2, registrations());
    }
    @Test public void initializedClientCanExplicitlyResumeAfterConsentIsGrantedAgain() throws Exception {
        saveInitialization();
        AvvessaPushPrivacyProvider provider = start(new AvvessaPushPrivacyProvider());
        ShadowContentResolver.registerProviderInternal(context.getPackageName() + ".avvessa.pushprivacy", provider);
        Avvessa client = Avvessa.getInstance(context);
        ReflectionHelpers.setField(client, "initialized", true);
        client.setConsentGranted(false); client.setConsentGranted(true);
        assertNull(store.restorableConfiguration()); assertFalse(sdkConsent());
        client.resumePush();
        assertNotNull(store.restorableConfiguration()); assertTrue(sdkConsent());
        assertEquals(2, registrations());
    }
    @Test public void invalidSavedConfigurationKeepsCollectionDisabledAndReportsSyncFailure() throws Exception {
        store.state("consent", "true"); store.saveConfiguration("{\"appKey\":\"invalid\"}");
        AvvessaPushPrivacyProvider provider = start(new AvvessaPushPrivacyProvider());
        assertFalse(sdkConsent()); assertEquals(0, registrations());
        try { provider.call("syncConsent", null, null); fail("Expected invalid_configuration"); }
        catch (PushException error) { assertEquals("invalid_configuration", error.code); }
        assertFalse(sdkConsent());
    }
    @Test public void manifestConflictIsRejectedBeforeRestoringTheSdk() throws Exception {
        saveInitialization();
        context.getApplicationInfo().metaData.putString("JPUSH_APPKEY", "111111111111111111111111");
        AvvessaPushPrivacyProvider provider = start(new AvvessaPushPrivacyProvider());
        assertFalse(sdkConsent()); assertEquals(0, registrations());
        try { provider.call("syncConsent", null, null); fail("Expected configuration_conflict"); }
        catch (PushException error) { assertEquals("configuration_conflict", error.code); }
    }

    @Implements(value = JCoreInternalHelper.class, isInAndroidSdk = false)
    public static class NoServiceWork {
        static final List<String> actions = new ArrayList<>();
        static final List<Integer> unconfiguredOperations = new ArrayList<>();
        static JPushConfig initialization;
        @Implementation protected void initLoad(Context context) {}
        @Implementation protected Object onEvent(Context context, String sdk, int action, boolean async,
                String name, Bundle extras, Object... args) {
            actions.add(name);
            // Public collection switches dispatch these actions in the pinned JCore SDK.
            boolean grantsConsent = action == 96 && args.length > 0 && Boolean.TRUE.equals(args[0]);
            if ((grantsConsent || action == 109 || action == 115 || action == 129) && JConstants.APP_KEY.isEmpty())
                unconfiguredOperations.add(action);
            if ("init".equals(name) && extras != null)
                initialization = (JPushConfig) extras.getSerializable("config");
            return null;
        }
    }
}
