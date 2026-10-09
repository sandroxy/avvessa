package com.sandrox.avvessa;

import org.json.JSONObject;

public final class PushState {
    public final boolean consentGranted;
    public final boolean initialized;
    public final String registrationId;
    public final Boolean connected;
    public final Boolean stopped;
    public final String notificationPermission;
    public final long droppedEvents;
    public final PushException lastError;

    PushState(boolean consent, boolean initialized, String registrationId, Boolean connected,
            Boolean stopped, String permission, long droppedEvents, PushException lastError) {
        consentGranted = consent; this.initialized = initialized; this.registrationId = registrationId;
        this.connected = connected; this.stopped = stopped;
        notificationPermission = permission; this.droppedEvents = droppedEvents;
        this.lastError = lastError;
    }
    public JSONObject toJson() {
        JSONObject data = new JSONObject();
        Json.put(data, "consentGranted", consentGranted); Json.put(data, "initialized", initialized);
        Json.put(data, "registrationId", registrationId); Json.put(data, "connected", connected);
        Json.put(data, "stopped", stopped); Json.put(data, "notificationPermission", notificationPermission);
        Json.put(data, "droppedEvents", droppedEvents);
        Json.put(data, "lastError", lastError == null ? null
                : Json.put(Json.put(new JSONObject(), "code", lastError.code), "nativeCode", lastError.nativeCode));
        return data;
    }
}
