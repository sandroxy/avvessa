package com.sandrox.avvessa;

import org.json.JSONObject;

/** Immutable event. Acknowledgement uses id, never the provider's messageId. */
public final class PushEvent {
    public final String id;
    public final String type;
    public final String messageId;
    public final String source;
    public final long receivedAt;
    public final boolean replayed;
    private final String encoded;

    PushEvent(String encoded, boolean replayed) {
        JSONObject data = Json.parse(encoded);
        this.encoded = encoded;
        id = data.optString("id");
        type = data.optString("type");
        messageId = data.isNull("messageId") ? null : data.optString("messageId");
        source = data.optString("source");
        receivedAt = data.optLong("receivedAt");
        this.replayed = replayed;
    }

    public JSONObject toJson() { return Json.put(Json.parse(encoded), "replayed", replayed); }
    /** Null only when malformed SDK extras were reported through onError; raw remains intact. */
    public JSONObject getData() { return toJson().optJSONObject("data"); }
    public JSONObject getRaw() { return toJson().optJSONObject("raw"); }
}
