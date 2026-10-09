package com.sandrox.avvessa;

import android.content.Context;
import org.json.JSONObject;

/** Process-wide identity and presentation defaults. Build with only the target app's key. */
public final class PushConfiguration {
    public final String appKey;
    public final String channel;
    public final String notificationChannelId;
    public final String notificationChannelName;
    public final int smallIcon;
    public final int color;
    public final boolean debug;
    public final boolean presentInForeground;
    public final boolean openApplicationOnClick;
    public final int maxPendingEvents;
    public final long eventTtlMillis;

    private PushConfiguration(Builder builder) {
        if (builder.appKey == null || !builder.appKey.matches("[0-9a-fA-F]{24}"))
            throw new PushException("invalid_configuration", "appKey must contain 24 hexadecimal characters");
        if (builder.channel == null || builder.channel.trim().isEmpty()
                || builder.channelId == null || builder.channelId.trim().isEmpty()
                || builder.channelName == null || builder.channelName.trim().isEmpty())
            throw new PushException("invalid_configuration", "Channel values must not be empty");
        if (builder.maxEvents < 1 || builder.maxEvents > 1000 || builder.ttl < 60000
                || builder.ttl > 30L * 24 * 3600 * 1000)
            throw new PushException("invalid_configuration", "Invalid event retention bounds");
        appKey = builder.appKey;
        channel = builder.channel;
        notificationChannelId = builder.channelId;
        notificationChannelName = builder.channelName;
        smallIcon = builder.icon;
        color = builder.color;
        debug = builder.debug;
        presentInForeground = builder.foreground;
        openApplicationOnClick = builder.open;
        maxPendingEvents = builder.maxEvents;
        eventTtlMillis = builder.ttl;
    }

    void validateResources(Context context) {
        try {
            String type = context.getResources().getResourceTypeName(smallIcon);
            if (!type.equals("drawable") && !type.equals("mipmap"))
                throw new PushException("invalid_configuration", "smallIcon must reference a drawable or mipmap");
        } catch (android.content.res.Resources.NotFoundException error) {
            throw new PushException("invalid_configuration", "smallIcon resource does not exist", error);
        }
    }

    JSONObject toJson(Context context) {
        JSONObject data = new JSONObject();
        Json.put(data, "appKey", appKey); Json.put(data, "channel", channel);
        Json.put(data, "channelId", notificationChannelId); Json.put(data, "channelName", notificationChannelName);
        Json.put(data, "smallIcon", context.getResources().getResourceName(smallIcon));
        Json.put(data, "color", color); Json.put(data, "debug", debug);
        Json.put(data, "foreground", presentInForeground); Json.put(data, "open", openApplicationOnClick);
        Json.put(data, "maxEvents", maxPendingEvents); Json.put(data, "ttl", eventTtlMillis);
        return data;
    }

    @android.annotation.SuppressLint("DiscouragedApi") // Stored IDs are unstable after an application update.
    static PushConfiguration fromJson(Context context, JSONObject data) {
        int icon = context.getResources().getIdentifier(data.optString("smallIcon"), null, null);
        if (icon == 0) throw new PushException("invalid_configuration", "The saved smallIcon resource no longer exists; restore it before delivering notifications");
        return new Builder(data.optString("appKey")).channel(data.optString("channel"))
                .notificationChannel(data.optString("channelId"), data.optString("channelName"))
                .smallIcon(icon).color(data.optInt("color"))
                .debug(data.optBoolean("debug")).presentInForeground(data.optBoolean("foreground"))
                .openApplicationOnClick(data.optBoolean("open"))
                .eventRetention(data.optInt("maxEvents"), data.optLong("ttl")).build();
    }

    public static final class Builder {
        private final String appKey;
        private String channel = "default", channelId = "avvessa", channelName = "Notifications";
        private int icon = R.drawable.avvessa_notification, color, maxEvents = 256;
        private long ttl = 72L * 3600 * 1000;
        private boolean debug, foreground = true, open = true;
        public Builder(String appKey) { this.appKey = appKey; }
        public Builder channel(String value) { channel = value; return this; }
        public Builder notificationChannel(String id, String name) { channelId = id; channelName = name; return this; }
        public Builder smallIcon(int value) { icon = value; return this; }
        public Builder color(int value) { color = value; return this; }
        public Builder debug(boolean value) { debug = value; return this; }
        public Builder presentInForeground(boolean value) { foreground = value; return this; }
        public Builder openApplicationOnClick(boolean value) { open = value; return this; }
        public Builder eventRetention(int count, long ttlMillis) { maxEvents = count; ttl = ttlMillis; return this; }
        public PushConfiguration build() { return new PushConfiguration(this); }
    }
}
