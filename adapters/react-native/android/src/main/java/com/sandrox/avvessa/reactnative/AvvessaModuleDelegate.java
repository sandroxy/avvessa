package com.sandrox.avvessa.reactnative;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.sandrox.avvessa.bridge.AvvessaBridge;
import org.json.JSONObject;

/** A React runtime owns observers, while the native client owns push lifetime. */
final class AvvessaModuleDelegate implements LifecycleEventListener {
    private final ReactApplicationContext context;
    private final AvvessaBridge bridge;
    private boolean invalidated;

    AvvessaModuleDelegate(ReactApplicationContext context) {
        this.context = context;
        bridge = new AvvessaBridge(context);
        context.addLifecycleEventListener(this);
    }

    void invoke(String method, String argumentsJSON, Promise promise) {
        UiThreadUtil.runOnUiThread(() -> {
            if (invalidated) {
                promise.reject("bridge_closed", "The React Native bridge has been invalidated");
                return;
            }
            String id = null;
            if ("subscribe".equals(method)) {
                try { id = new JSONObject(argumentsJSON).optString("subscriptionId", null); }
                catch (org.json.JSONException ignored) { /* Shared bridge validates and reports the argument. */ }
            }
            final String subscriptionId = id;
            final boolean[] replied = {false};
            bridge.invoke(context.getCurrentActivity(), method, argumentsJSON, response -> {
                if (invalidated) return;
                if (!replied[0]) {
                    replied[0] = true;
                    promise.resolve(response);
                } else if (subscriptionId != null && context.hasActiveReactInstance()) {
                    WritableMap packet = Arguments.createMap();
                    packet.putString("subscriptionId", subscriptionId);
                    packet.putString("response", response);
                    context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit("AvvessaObservation", packet);
                }
            });
        });
    }

    void invalidate() {
        UiThreadUtil.runOnUiThread(() -> {
            if (invalidated) return;
            invalidated = true;
            context.removeLifecycleEventListener(this);
            bridge.close();
        });
    }

    @Override public void onHostResume() { bridge.refreshState(); }
    @Override public void onHostPause() {}
    @Override public void onHostDestroy() {}
}
