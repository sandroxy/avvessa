package com.sandrox.avvessa.bridge;

import android.app.Activity;
import android.app.NotificationChannel;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.sandrox.avvessa.Avvessa;
import com.sandrox.avvessa.PushConfiguration;
import com.sandrox.avvessa.PushEvent;
import com.sandrox.avvessa.PushException;
import com.sandrox.avvessa.PushState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.sandrox.avvessa.bridge.BridgeArguments.*;

/** One framework owner. Closing it removes its observers, never the process-wide SDK. */
public final class AvvessaBridge {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Observation> observations = new LinkedHashMap<>();
    private boolean closed;

    public AvvessaBridge(Context context) {
        if (context == null) throw invalid("Application context is required");
        this.context = context.getApplicationContext();
    }

    public void invoke(Activity activity, String method, String argumentsJSON, AvvessaCallback callback) {
        if (callback == null) throw invalid("Callback is required");
        onMain(() -> {
            try {
                if (closed) throw new PushException("bridge_closed", "This bridge owner has been disposed");
                dispatch(activity, method, parse(argumentsJSON), callback);
            } catch (RuntimeException error) { failure(callback, error); }
        });
    }

    private void dispatch(Activity activity, String method, JSONObject args, AvvessaCallback callback) {
        Avvessa client = Avvessa.getInstance(context);
        switch (method) {
            case "getState": fields(args); success(callback, client.getState().toJson()); return;
            case "getCapabilities": fields(args); success(callback, client.getCapabilities()); return;
            case "setConsentGranted":
                fields(args, "granted"); if (!args.has("granted")) throw invalid("granted is required");
                client.setConsentGranted(bool(args, "granted", false)); break;
            case "initialize": client.initialize(configuration(args)); break;
            case "refreshState": fields(args); client.refreshState(); success(callback, client.getState().toJson()); return;
            case "requestNotificationPermission":
                fields(args); client.requestNotificationPermission(activity, 42837);
                success(callback, json("requested", Build.VERSION.SDK_INT >= 33, "granted", Build.VERSION.SDK_INT < 33 ? client.getState().notificationPermission.equals("authorized") : null)); return;
            case "openNotificationSettings": fields(args); client.openNotificationSettings(); break;
            case "getPendingEvents":
                fields(args); JSONArray events = new JSONArray();
                for (PushEvent event : client.getPendingEvents()) events.put(event.toJson());
                success(callback, events); return;
            case "acknowledge":
                fields(args, "eventId"); success(callback, client.acknowledge(string(args, "eventId"))); return;
            case "clearPendingEvents": fields(args); client.clearPendingEvents(); break;
            case "subscribe": subscribe(client, args, callback); return;
            case "unsubscribe": fields(args, "subscriptionId"); remove(string(args, "subscriptionId")); break;
            case "stopPush": fields(args); client.stopPush(); break;
            case "resumePush": fields(args); client.resumePush(); break;
            case "setBadge":
                fields(args, "badge"); if (!args.has("badge")) throw invalid("badge is required");
                client.setBadge((int) integer(args, "badge", 0, 0, Integer.MAX_VALUE)); break;
            case "clearNotifications":
                fields(args, "androidIds", "iosIdentifiers");
                if (args.has("iosIdentifiers")) strings(args, "iosIdentifiers", true);
                if (args.has("iosIdentifiers") && !args.has("androidIds")) throw new PushException("unsupported", "iosIdentifiers cannot select Android notifications");
                if (!args.has("androidIds")) client.clearAllNotifications();
                else {
                    JSONArray ids = array(args, "androidIds");
                    ArrayList<Integer> valid = new ArrayList<>();
                    for (int i = 0; i < ids.length(); i++) valid.add((int) integer(json("id", ids.opt(i)), "id", 0, 0, Integer.MAX_VALUE));
                    for (int id : valid) client.clearNotification(id);
                }
                break;
            case "createNotificationChannel": createChannel(client, args); break;
            case "setNotificationCategories": throw new PushException("unsupported", "Notification categories require iOS");
            case "setAlias": fields(args, "alias"); client.setAlias(string(args, "alias"), completion(callback)); return;
            case "getAlias": fields(args); client.getAlias(completion(callback)); return;
            case "deleteAlias": fields(args); client.deleteAlias(completion(callback)); return;
            case "setTags": fields(args, "tags"); client.setTags(strings(args, "tags", false), completion(callback)); return;
            case "addTags": fields(args, "tags"); client.addTags(strings(args, "tags", false), completion(callback)); return;
            case "deleteTags": fields(args, "tags"); client.deleteTags(strings(args, "tags", false), completion(callback)); return;
            case "getTags": fields(args); client.getTags(completion(callback)); return;
            case "clearTags": fields(args); client.clearTags(completion(callback)); return;
            case "checkTag": fields(args, "tag"); client.checkTag(string(args, "tag"), completion(callback)); return;
            default: throw new PushException("unsupported", "Unknown bridge method: " + method);
        }
        success(callback, null);
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private PushConfiguration configuration(JSONObject args) {
        fields(args, "appKey", "channel", "debug", "maxPendingEvents", "eventRetentionMilliseconds", "android", "ios");
        JSONObject android = object(args, "android"), ios = object(args, "ios");
        fields(android, "smallIcon", "color", "notificationChannelId", "notificationChannelName", "presentInForeground", "openApplicationOnClick");
        fields(ios, "apnsEnvironment", "foregroundPresentation");
        // Validate the opposite platform's fields as well; misspellings must fail everywhere.
        if (ios.has("apnsEnvironment") && !java.util.Arrays.asList("development", "production").contains(string(ios, "apnsEnvironment"))) throw invalid("Invalid apnsEnvironment");
        if (ios.has("foregroundPresentation")) for (String option : strings(ios, "foregroundPresentation", true))
            if (!java.util.Arrays.asList("banner", "list", "sound", "badge").contains(option)) throw invalid("Invalid foreground presentation option");
        PushConfiguration.Builder builder = new PushConfiguration.Builder(string(args, "appKey"))
            .channel(string(args, "channel", "default")).debug(bool(args, "debug", false))
            .notificationChannel(string(android, "notificationChannelId", "avvessa"), string(android, "notificationChannelName", "Notifications"))
            .presentInForeground(bool(android, "presentInForeground", true)).openApplicationOnClick(bool(android, "openApplicationOnClick", true))
            .eventRetention((int) integer(args, "maxPendingEvents", 256, 1, 1000), integer(args, "eventRetentionMilliseconds", 259200000L, 60000, 2592000000L));
        if (android.has("smallIcon")) {
            String name = string(android, "smallIcon");
            if (!name.matches("(?:drawable|mipmap)/[a-z][a-z0-9_]*")) throw invalid("smallIcon must be drawable/name or mipmap/name");
            int id = context.getResources().getIdentifier(name, null, context.getPackageName());
            if (id == 0) throw new PushException("invalid_configuration", "smallIcon resource does not exist: " + name);
            builder.smallIcon(id);
        }
        if (android.has("color")) {
            String color = string(android, "color");
            if (!color.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) throw invalid("color must be #RRGGBB or #AARRGGBB");
            builder.color(Color.parseColor(color));
        }
        return builder.build();
    }

    private void createChannel(Avvessa client, JSONObject args) {
        fields(args, "id", "name", "importance", "description", "sound", "vibration", "showBadge");
        if (Build.VERSION.SDK_INT < 26) throw new PushException("unsupported", "Notification channels require Android 8 or later");
        NotificationChannel channel = new NotificationChannel(string(args, "id"), string(args, "name"), (int) integer(args, "importance", 3, 0, 5));
        if (args.has("description")) channel.setDescription(string(args, "description"));
        if (!bool(args, "sound", true)) channel.setSound(null, null);
        channel.enableVibration(bool(args, "vibration", true)); channel.setShowBadge(bool(args, "showBadge", true));
        client.createNotificationChannel(channel);
    }

    private void subscribe(Avvessa client, JSONObject args, AvvessaCallback callback) {
        fields(args, "subscriptionId", "replayPending");
        String id = string(args, "subscriptionId");
        if (id.length() > 128 || observations.containsKey(id)) throw invalid("Duplicate or oversized subscriptionId");
        if (observations.size() >= 128) throw invalid("Too many subscriptions for this owner");
        Observation observation = new Observation(callback);
        observation.subscription = client.subscribe(new Avvessa.Listener() {
            public void onEvent(PushEvent event) { if (observation.active) success(callback, json("kind", "event", "event", event.toJson())); }
            public void onStateChanged(PushState state) { if (observation.active) success(callback, json("kind", "state", "state", state.toJson())); }
            public void onError(PushException error) { if (observation.active) success(callback, json("kind", "error", "error", errorPayload(error))); }
        }, bool(args, "replayPending", true));
        observations.put(id, observation);
        success(callback, json("kind", "subscribed", "subscriptionId", id));
    }
    private void remove(String id) {
        Observation observation = observations.remove(id);
        if (observation == null) return;
        observation.active = false; observation.subscription.remove();
        success(observation.callback, json("kind", "closed"));
    }
    public void refreshState() { onMain(() -> { if (!closed) Avvessa.getInstance(context).refreshState(); }); }
    public void close() { onMain(() -> { closed = true; for (String id : new ArrayList<>(observations.keySet())) remove(id); }); }
    private void onMain(Runnable work) { if (Looper.myLooper() == Looper.getMainLooper()) work.run(); else main.post(work); }
    private Avvessa.Completion completion(AvvessaCallback callback) { return (result, error) -> { if (error == null) success(callback, result); else failure(callback, error); }; }
    private static final class Observation {
        final AvvessaCallback callback; Avvessa.Subscription subscription; boolean active = true;
        Observation(AvvessaCallback callback) { this.callback = callback; }
    }
    static JSONObject errorPayload(Throwable error) {
        PushException push = error instanceof PushException ? (PushException) error : null;
        return json("code", push == null ? "native_error" : push.code, "message", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), "nativeCode", push == null ? null : push.nativeCode);
    }
    static void success(AvvessaCallback callback, Object value) { callback.onResult(json("ok", true, "data", value).toString()); }
    static void failure(AvvessaCallback callback, Throwable error) { callback.onResult(json("ok", false, "error", errorPayload(error)).toString()); }
}
