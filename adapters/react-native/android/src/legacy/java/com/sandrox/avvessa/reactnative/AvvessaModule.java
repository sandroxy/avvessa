package com.sandrox.avvessa.reactnative;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

@ReactModule(name = AvvessaModule.NAME)
public final class AvvessaModule extends ReactContextBaseJavaModule {
    static final String NAME = "Avvessa";
    private final AvvessaModuleDelegate delegate;
    public AvvessaModule(ReactApplicationContext context) { super(context); delegate = new AvvessaModuleDelegate(context); }
    @Override public String getName() { return NAME; }
    @ReactMethod public void invoke(String method, String argumentsJSON, Promise promise) { delegate.invoke(method, argumentsJSON, promise); }
    @ReactMethod public void addListener(String eventName) {}
    @ReactMethod public void removeListeners(double count) {}
    @Override public void invalidate() { delegate.invalidate(); super.invalidate(); }
}
