package com.sandrox.avvessa;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationManagerCompat;
import cn.jiguang.api.utils.JCollectionAuth;
import cn.jpush.android.api.JPushInterface;
import cn.jpush.android.api.JPushMessage;
import cn.jpush.android.data.JPushConfig;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Process-wide native client. Public callbacks are delivered on the main thread. */
public final class Avvessa {
    public interface Listener {
        void onEvent(PushEvent event);
        default void onStateChanged(PushState state) {}
        default void onError(PushException error) {}
    }
    public interface Subscription { void remove(); }
    public interface Completion { void complete(JSONObject result, PushException error); }

    @android.annotation.SuppressLint("StaticFieldLeak") // Only the process Application context is retained.
    private static Avvessa shared;
    public static synchronized Avvessa getInstance(Context context) {
        if (context == null) throw new PushException("invalid_argument", "Context is required");
        if (shared == null) shared = new Avvessa(context.getApplicationContext());
        return shared;
    }

    /** Application.onCreate also runs in :pushcore; initialize only the application process. */
    public static boolean isMainProcess(Context context) {
        String current = null;
        if (Build.VERSION.SDK_INT >= 28) current = Application.getProcessName();
        else {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes)
                if (process.pid == android.os.Process.myPid()) current = process.processName;
        }
        if (current == null) throw new PushException("host_unavailable", "Cannot determine the current application process");
        return current.equals(context.getApplicationInfo().processName);
    }

    final Context context;
    private final EventStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Observer> observers = new ArrayList<>();
    private final Map<Integer, PendingOperation> operations = new HashMap<>();
    private PushConfiguration configuration;
    private boolean initialized;
    private int sequence;
    private volatile long consentGeneration;
    private PushException lastError;
    private String savedAppKey;
    private JSONObject activeNotificationContext;
    private boolean lifecycleInstalled;
    private boolean pendingOpenCleanup;

    private Avvessa(Context context) {
        this.context = context;
        store = new EventStore(context);
        String saved = store.state("configuration");
        if (saved != null) {
            JSONObject identity = Json.parse(saved);
            savedAppKey = identity.optString("appKey");
            try { configuration = PushConfiguration.fromJson(context, identity); }
            catch (PushException error) { lastError = error; }
        }
    }

    /** Consent is persisted, but granting consent alone never initializes the SDK. */
    public synchronized void setConsentGranted(boolean granted) {
        requireMainThread();
        if (granted) store.state("consent", "true");
        else {
            store.revokeConsent();
            consentGeneration++;
            activeNotificationContext = null; pendingOpenCleanup = false;
            AvvessaPresentationProvider.clear();
            if (initialized) {
                try { JPushInterface.stopPush(context); }
                catch (RuntimeException error) { report(new PushException("sdk_error", "Cannot confirm SDK shutdown", error)); }
            }
            for (PendingOperation operation : new ArrayList<>(operations.values()))
                finish(operation.sequence, null, new PushException("consent_required", "Consent was revoked"));
        }
        JCollectionAuth.setAuth(context, granted);
        if (!granted || initialized) synchronizePrivacy();
        emitState();
    }

    private void synchronizePrivacy() {
        // Apply persisted consent even if the SDK's separate process is already alive.
        try {
            context.getContentResolver().call(android.net.Uri.parse("content://" + context.getPackageName()
                    + ".avvessa.pushprivacy"), "syncConsent", null, null);
        } catch (RuntimeException error) {
            report(new PushException("privacy_sync", "Could not update the push process privacy state", error));
        }
    }

    public synchronized void initialize(PushConfiguration config) {
        requireMainThread();
        if (!isMainProcess(context)) throw new PushException("invalid_process", "Initialize Avvessa in the application process only");
        if (config == null) throw new PushException("invalid_configuration", "Configuration is required");
        if (!store.consent()) throw new PushException("consent_required", "Explicit consent is required before initialization");
        config.validateResources(context);
        Object manifestKey = PushVendors.metadata(context).get("JPUSH_APPKEY");
        if (manifestKey != null && !manifestKey.toString().isEmpty() && !manifestKey.toString().equals(config.appKey))
            throw new PushException("configuration_conflict", "Manifest AppKey differs from the runtime AppKey");
        if (initialized) {
            if (!configuration.toJson(context).toString().equals(config.toJson(context).toString()))
                throw new PushException("configuration_conflict", "The process already uses a different configuration");
            return;
        }
        if (savedAppKey != null && !savedAppKey.equals(config.appKey))
            throw new PushException("configuration_conflict", "Changing AppKey requires clearing this application's data");
        PushVendors.validate(context);
        store.state("configuration", config.toJson(context).toString());
        configuration = config;
        savedAppKey = config.appKey;
        lastError = null;
        JCollectionAuth.setAuth(context, true);
        synchronizePrivacy();
        JCollectionAuth.enableAutoWakeup(context, false);
        JCollectionAuth.enableDynamicLoad(context, false);
        JPushInterface.setDebugMode(config.debug);
        JPushInterface.setChannel(context, config.channel);
        JPushInterface.setGeofenceEnable(context, false);
        JPushInterface.setSmartPushEnable(context, false);
        JPushInterface.setDataInsightsEnable(context, false);
        PushNotifications.ensureDefaultChannel(context, config);
        JPushConfig sdkConfig = new JPushConfig(); sdkConfig.setjAppKey(config.appKey);
        PushVendors.apply(context, sdkConfig);
        JPushInterface.init(context, sdkConfig);
        JPushInterface.setNotificationCallBackEnable(context, true);
        initialized = true;
        if (!lifecycleInstalled && context instanceof Application) {
            ((Application) context).registerActivityLifecycleCallbacks(new PushLifecycle(this)); lifecycleInstalled = true;
        }
        cleanup(pendingOpenCleanup || config.notificationLifecycle.clearBadgeOnStart, config.notificationLifecycle.clearDeliveredOnStart);
        pendingOpenCleanup = false;
        String registration = JPushInterface.getRegistrationID(context);
        if (registration != null && !registration.isEmpty()) store.state("registrationId", registration);
        emitState();
    }

    public synchronized PushState getState() {
        boolean consent = store.consent();
        String dropped = store.state("droppedEvents");
        return new PushState(consent, initialized,
                consent ? store.state("registrationId") : null,
                initialized && consent ? JPushInterface.getConnectionState(context) : null,
                initialized && consent ? JPushInterface.isPushStopped(context) : null,
                NotificationManagerCompat.from(context).areNotificationsEnabled() ? "authorized" : "denied",
                dropped == null ? 0 : Long.parseLong(dropped), lastError);
    }

    /** Installed modules describe build capability, not vendor registration or delivery success. */
    public JSONObject getCapabilities() {
        JSONObject result = new JSONObject();
        Json.put(result, "platform", "android");
        Json.put(result, "vendorModules", new JSONArray(PushVendors.installed(context)));
        Json.put(result, "notificationChannels", Build.VERSION.SDK_INT >= 26);
        Json.put(result, "runtimeNotificationPermission", Build.VERSION.SDK_INT >= 33);
        Json.put(result, "presentationPolicy", true); Json.put(result, "notificationLifecycle", true);
        Json.put(result, "durableEvents", true); Json.put(result, "incomingCalls", false);
        return result;
    }

    /** Registers before enqueueing replay, under the same lock used by incoming events. */
    public synchronized Subscription subscribe(Listener listener, boolean replayPending) {
        if (listener == null) throw new PushException("invalid_argument", "Listener is required");
        Observer observer = new Observer(listener); observers.add(observer);
        if (replayPending && configuration != null && store.consent()) {
            for (PushEvent event : store.pending(configuration, System.currentTimeMillis())) deliver(observer, event);
        }
        PushState state = getState();
        main.post(() -> { if (observer.active) invoke(() -> listener.onStateChanged(state)); });
        if (lastError != null) {
            PushException error = lastError;
            main.post(() -> { if (observer.active) invoke(() -> listener.onError(error)); });
        }
        return () -> { synchronized (Avvessa.this) { observer.active = false; observers.remove(observer); } };
    }

    public synchronized List<PushEvent> getPendingEvents() {
        if (!store.consent() || configuration == null) return new ArrayList<>();
        return store.pending(configuration, System.currentTimeMillis());
    }
    public synchronized boolean acknowledge(String eventId) { return store.acknowledge(eventId); }
    public synchronized void clearPendingEvents() { store.clearEvents(); }

    public void requestNotificationPermission(Activity activity, int requestCode) {
        requireMainThread();
        if (activity == null || activity.isFinishing()) throw new PushException("host_unavailable", "A visible Activity is required");
        if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
    }
    /** Call after a permission result or returning from system settings. */
    public synchronized void refreshState() { emitState(); }
    public void openNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        else intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    public synchronized void stopPush() { requireInitialized(); JPushInterface.stopPush(context); emitState(); }
    public synchronized void resumePush() {
        requireInitialized(); JPushInterface.resumePush(context);
        String registration = JPushInterface.getRegistrationID(context);
        if (registration != null && !registration.isEmpty()) store.state("registrationId", registration);
        emitState();
    }
    /** Context is memory-only, never restored after process death or consent withdrawal. */
    public synchronized void setActiveNotificationContext(JSONObject value) {
        requireMainThread(); requireInitialized(); activeNotificationContext = NotificationPolicy.context(value);
    }
    public synchronized void clearActiveNotificationContext() { requireMainThread(); activeNotificationContext = null; }
    synchronized String suppressionReason(JSONObject data, boolean foreground) {
        if (!mayReceive() || !foreground) return null;
        if (!configuration.presentInForeground) return "foregroundDisabled";
        return configuration.presentationPolicy.suppressionReason(data, activeNotificationContext, "android");
    }
    synchronized void foregrounded() {
        if (!initialized || !store.consent()) return;
        cleanup(configuration.notificationLifecycle.clearBadgeOnForeground, configuration.notificationLifecycle.clearDeliveredOnForeground);
        emitState();
    }
    private void cleanup(boolean badge, boolean delivered) {
        try {
            if (delivered) clearAllNotifications();
            if (badge) setBadge(0);
        } catch (RuntimeException error) { report(new PushException("notification_cleanup", "Could not complete notification cleanup", error)); }
    }
    public synchronized void setBadge(int number) {
        requireInitialized();
        if (number < 0) throw new PushException("invalid_argument", "Badge must be non-negative");
        JPushInterface.setBadgeNumber(context, number);
    }
    public void clearNotification(int notificationId) {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(notificationId);
    }
    public void clearAllNotifications() {
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();
    }
    public void createNotificationChannel(NotificationChannel channel) {
        if (Build.VERSION.SDK_INT < 26) throw new PushException("unsupported", "Notification channels require Android 8 or later");
        if (channel == null) throw new PushException("invalid_argument", "Channel is required");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    public synchronized int setAlias(String alias, Completion completion) {
        validateLabel(alias, "alias"); return execute("alias", completion, id -> JPushInterface.setAlias(context, id, alias));
    }
    public synchronized int getAlias(Completion completion) { return execute("alias", completion, id -> JPushInterface.getAlias(context, id)); }
    public synchronized int deleteAlias(Completion completion) { return execute("alias", completion, id -> JPushInterface.deleteAlias(context, id)); }
    public synchronized int setTags(Set<String> tags, Completion completion) { return tags("set", tags, completion); }
    public synchronized int addTags(Set<String> tags, Completion completion) { return tags("add", tags, completion); }
    public synchronized int deleteTags(Set<String> tags, Completion completion) { return tags("delete", tags, completion); }
    public synchronized int getTags(Completion completion) { return execute("tags", completion, id -> JPushInterface.getAllTags(context, id)); }
    public synchronized int clearTags(Completion completion) { return execute("tags", completion, id -> JPushInterface.cleanTags(context, id)); }
    public synchronized int checkTag(String tag, Completion completion) {
        validateLabel(tag, "tag"); return execute("checkTag", completion, id -> JPushInterface.checkTagBindState(context, id, tag));
    }
    private int tags(String operation, Set<String> tags, Completion completion) {
        if (tags == null || tags.isEmpty() || tags.size() > 1000)
            throw new PushException("invalid_argument", "Provide between 1 and 1000 tags");
        Set<String> copy = new LinkedHashSet<>(tags); for (String tag : copy) validateLabel(tag, "tag");
        return execute("tags", completion, id -> {
            if (operation.equals("set")) JPushInterface.setTags(context, id, copy);
            else if (operation.equals("add")) JPushInterface.addTags(context, id, copy);
            else JPushInterface.deleteTags(context, id, copy);
        });
    }
    private static void validateLabel(String value, String field) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 40)
            throw new PushException("invalid_argument", field + " must contain 1 to 40 UTF-8 bytes");
    }
    private interface SdkOperation { void run(int sequence); }
    private int execute(String kind, Completion completion, SdkOperation action) {
        int id = begin(kind, completion);
        try { action.run(id); }
        catch (RuntimeException error) { finish(id, null, new PushException("sdk_error", "JPush could not start the operation", error)); }
        return id;
    }
    private int begin(String kind, Completion completion) {
        requireInitialized();
        if (completion == null) throw new PushException("invalid_argument", "Completion is required");
        do { sequence = sequence == Integer.MAX_VALUE ? 1 : sequence + 1; } while (operations.containsKey(sequence));
        PendingOperation operation = new PendingOperation(sequence, kind, completion);
        operations.put(sequence, operation);
        operation.timeout = () -> { synchronized (Avvessa.this) {
            finish(operation.sequence, null, new PushException("timeout", "SDK operation did not complete within 30 seconds"));
        } };
        main.postDelayed(operation.timeout, 30000);
        return sequence;
    }

    synchronized void operationResult(String kind, JPushMessage message) {
        PendingOperation operation = operations.get(message.getSequence());
        if (operation == null || !operation.kind.equals(kind)) return;
        if (message.getErrorCode() != 0) {
            finish(message.getSequence(), null, new PushException("sdk_error", "JPush operation failed", message.getErrorCode(), null));
            return;
        }
        JSONObject result = new JSONObject(); Json.put(result, "sequence", message.getSequence());
        if (kind.equals("alias")) Json.put(result, "alias", message.getAlias());
        if (kind.equals("tags")) Json.put(result, "tags", new JSONArray(message.getTags() == null ? new ArrayList<>() : message.getTags()));
        if (kind.equals("checkTag")) { Json.put(result, "tag", message.getCheckTag()); Json.put(result, "bound", message.getTagCheckStateResult()); }
        finish(message.getSequence(), result, null);
    }
    private void finish(int id, JSONObject result, PushException error) {
        PendingOperation operation = operations.remove(id);
        if (operation == null) return;
        if (error != null) lastError = error;
        main.removeCallbacks(operation.timeout);
        main.post(() -> invoke(() -> operation.completion.complete(result, error)));
    }

    synchronized void registrationChanged(String registration) {
        if (!store.consent()) return;
        if (registration != null && !registration.isEmpty()) store.state("registrationId", registration);
        emitState();
    }
    synchronized void stateChanged() { if (store.consent()) emitState(); }
    synchronized PushConfiguration configuration() { return configuration; }
    synchronized boolean mayReceive() { return store.consent() && configuration != null; }

    synchronized void receive(String type, String messageId, String source, JSONObject data,
            JSONObject raw, String title, String body) {
        receive(type, messageId, source, data, raw, title, body, null);
    }
    synchronized void receive(String type, String messageId, String source, JSONObject data,
            JSONObject raw, String title, String body, JSONObject presentation) {
        if (!mayReceive()) return;
        try {
            PushEvent event = store.append(type, messageId, source, data, raw, title, body, presentation, configuration, System.currentTimeMillis());
            if (event != null && type.equals("notificationOpened") && configuration.notificationLifecycle.clearBadgeOnOpen) {
                if (initialized) main.post(() -> { synchronized (Avvessa.this) { if (store.consent()) cleanup(true, false); } });
                else pendingOpenCleanup = true;
            }
            if (event != null) for (Observer observer : new ArrayList<>(observers)) deliver(observer, event);
        } catch (RuntimeException error) {
            report(error instanceof PushException ? (PushException) error : new PushException("storage_write", "Could not persist push event", error));
        }
    }
    private void deliver(Observer observer, PushEvent event) {
        long generation = consentGeneration;
        main.post(() -> { if (observer.active && store.consent() && generation == consentGeneration)
            invoke(() -> observer.listener.onEvent(event)); });
    }
    private void emitState() {
        PushState state = getState();
        for (Observer observer : new ArrayList<>(observers))
            main.post(() -> { if (observer.active) invoke(() -> observer.listener.onStateChanged(state)); });
    }
    synchronized void report(PushException error) {
        lastError = error;
        Log.e("Avvessa", error.code + ": " + error.getMessage());
        for (Observer observer : new ArrayList<>(observers))
            main.post(() -> { if (observer.active) invoke(() -> observer.listener.onError(error)); });
    }
    private static void invoke(Runnable operation) {
        try { operation.run(); } catch (RuntimeException error) { Log.e("Avvessa", "Consumer callback failed", error); }
    }
    private void requireInitialized() {
        if (!store.consent()) throw new PushException("consent_required", "Consent is required");
        if (!initialized) throw new PushException("not_initialized", "Initialize the client first");
    }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new PushException("invalid_thread", "Call this operation on the main thread");
    }
    private static final class Observer {
        final Listener listener; volatile boolean active = true;
        Observer(Listener listener) { this.listener = listener; }
    }
    private static final class PendingOperation {
        final int sequence; final String kind; final Completion completion; Runnable timeout;
        PendingOperation(int sequence, String kind, Completion completion) {
            this.sequence = sequence; this.kind = kind; this.completion = completion;
        }
    }
}
