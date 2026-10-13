package com.sandrox.avvessa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import cn.jpush.android.api.NotificationMessage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps page-dependent decisions in the application process, including SDK service callbacks. */
public final class AvvessaPresentationProvider extends ContentProvider {
    private static final Map<String, Decision> decisions = new LinkedHashMap<>();
    private static final long LIFETIME_MS = 60000;
    static final String BLOCKED = "blocked", SILENT_REMOVAL = "silentRemoval";

    static final class Decision {
        final String reason, method, token;
        final long time = SystemClock.elapsedRealtime();
        Decision(String reason, String method, String token) { this.reason = reason; this.method = method; this.token = token; }
        Bundle bundle() {
            Bundle result = new Bundle(); result.putString("reason", reason);
            result.putString("method", method); result.putString("token", token); return result;
        }
        static Decision from(Bundle value) {
            return value == null || value.isEmpty() ? null : new Decision(value.getString("reason"), value.getString("method"), value.getString("token"));
        }
    }
    private static String identity(NotificationMessage message) {
        // JPush assigns notificationId after building; msgId is stable across those callbacks.
        return message.platform + ":" + (message.msgId == null || message.msgId.isEmpty()
                ? "notification:" + message.notificationId : "message:" + message.msgId);
    }
    static Decision select(Context context, NotificationMessage message, String method) {
        Bundle input = new Bundle(); input.putString("extras", message.notificationExtras); input.putString("method", method);
        return Decision.from(invoke(context, "select", identity(message), input));
    }
    static Decision take(Context context, NotificationMessage message) {
        return Decision.from(invoke(context, "take", identity(message), null));
    }
    static void clear() { synchronized (decisions) { decisions.clear(); } }
    private static Bundle invoke(Context context, String operation, String identity, Bundle input) {
        if (Avvessa.isMainProcess(context)) return operate(context, operation, identity, input);
        Bundle result = context.getContentResolver().call(Uri.parse("content://" + context.getPackageName() + ".avvessa.presentation"), operation, identity, input);
        if (result == null) throw new PushException("presentation_unavailable", "Cannot reach the application's notification policy");
        return result;
    }
    private static void prune() {
        long now = SystemClock.elapsedRealtime();
        java.util.Iterator<Decision> iterator = decisions.values().iterator();
        while (iterator.hasNext()) if (now - iterator.next().time > LIFETIME_MS) iterator.remove();
    }
    private static Bundle operate(Context context, String operation, String identity, Bundle input) {
        if (identity == null) throw new IllegalArgumentException("Notification identity is required");
        if ("take".equals(operation)) {
            synchronized (decisions) {
                prune(); Decision decision = decisions.remove(identity);
                return decision == null ? Bundle.EMPTY : decision.bundle();
            }
        }
        if (!"select".equals(operation) || input == null) throw new IllegalArgumentException("Unknown presentation operation");
        String method = input.getString("method");
        if (!BLOCKED.equals(method) && !SILENT_REMOVAL.equals(method)) throw new IllegalArgumentException("Unknown presentation method");
        synchronized (decisions) {
            prune(); Decision saved = decisions.get(identity);
            if (saved != null) return saved.bundle();
        }
        // Never hold the cache lock while entering the client; consent withdrawal also clears it.
        Avvessa client = Avvessa.getInstance(context);
        String reason = client.suppressionReason(PushPayloads.extras(client, input.getString("extras")), PushNotifications.foreground());
        Decision selected = new Decision(reason, reason == null ? null : method, UUID.randomUUID().toString());
        synchronized (decisions) {
            Decision saved = decisions.get(identity);
            if (saved != null) return saved.bundle();
            if (decisions.size() >= 256) decisions.remove(decisions.keySet().iterator().next());
            decisions.put(identity, selected); return selected.bundle();
        }
    }
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException("Application-only notification policy");
        return operate(getContext(), method, argument, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
