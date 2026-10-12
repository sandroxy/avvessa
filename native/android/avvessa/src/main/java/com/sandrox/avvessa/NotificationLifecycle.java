package com.sandrox.avvessa;

import org.json.JSONObject;

/** Optional application lifecycle cleanup. No automatic permission prompt or SDK initialization. */
public final class NotificationLifecycle {
    public final boolean clearBadgeOnStart, clearBadgeOnForeground, clearBadgeOnOpen;
    public final boolean clearDeliveredOnStart, clearDeliveredOnForeground;

    public NotificationLifecycle(JSONObject options) {
        NotificationPolicy.fields(options, "clearBadgeOnStart", "clearBadgeOnForeground", "clearBadgeOnOpen", "clearDeliveredOnStart", "clearDeliveredOnForeground");
        clearBadgeOnStart = flag(options, "clearBadgeOnStart");
        clearBadgeOnForeground = flag(options, "clearBadgeOnForeground");
        clearBadgeOnOpen = flag(options, "clearBadgeOnOpen");
        clearDeliveredOnStart = flag(options, "clearDeliveredOnStart");
        clearDeliveredOnForeground = flag(options, "clearDeliveredOnForeground");
    }
    private static boolean flag(JSONObject options, String name) {
        if (!options.has(name)) return false;
        if (!(options.opt(name) instanceof Boolean)) throw NotificationPolicy.invalid(name + " must be a boolean");
        return options.optBoolean(name);
    }
    public JSONObject toJson() {
        JSONObject result = new JSONObject();
        Json.put(result, "clearBadgeOnStart", clearBadgeOnStart); Json.put(result, "clearBadgeOnForeground", clearBadgeOnForeground);
        Json.put(result, "clearBadgeOnOpen", clearBadgeOnOpen); Json.put(result, "clearDeliveredOnStart", clearDeliveredOnStart);
        Json.put(result, "clearDeliveredOnForeground", clearDeliveredOnForeground); return result;
    }
}
