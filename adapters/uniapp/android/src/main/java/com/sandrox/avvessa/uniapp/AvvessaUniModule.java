package com.sandrox.avvessa.uniapp;

import android.app.Activity;
import android.content.Context;
import com.sandrox.avvessa.bridge.AvvessaBridge;
import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;
import org.json.JSONObject;

public final class AvvessaUniModule extends UniModule {
    private AvvessaBridge bridge;
    @UniJSMethod(uiThread = true)
    public void invoke(String method, String argumentsJSON, UniJSCallback callback) {
        if (callback == null) return;
        Context context = mUniSDKInstance == null ? null : mUniSDKInstance.getContext();
        if (context == null) {
            callback.invoke("{\"ok\":false,\"error\":{\"code\":\"host_unavailable\",\"message\":\"UNI host is unavailable\",\"nativeCode\":null}}"); return;
        }
        if (bridge == null) bridge = new AvvessaBridge(context);
        bridge.invoke(context instanceof Activity ? (Activity) context : null, method, argumentsJSON, json -> {
            boolean keep = false;
            try {
                JSONObject response = new JSONObject(json);
                keep = "subscribe".equals(method) && response.optBoolean("ok") && !"closed".equals(response.getJSONObject("data").optString("kind"));
            } catch (org.json.JSONException ignored) { /* Deliver malformed responses as terminal errors. */ }
            if (keep) callback.invokeAndKeepAlive(json); else callback.invoke(json);
        });
    }
    @Override public void onActivityResume() { super.onActivityResume(); if (bridge != null) bridge.refreshState(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (bridge != null) bridge.refreshState();
    }
    @Override public void onActivityDestroy() { if (bridge != null) { bridge.close(); bridge = null; } super.onActivityDestroy(); }
}
