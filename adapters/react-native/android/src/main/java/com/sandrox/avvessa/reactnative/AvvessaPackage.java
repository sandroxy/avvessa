package com.sandrox.avvessa.reactnative;

import com.facebook.react.BaseReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.module.model.ReactModuleInfo;
import com.facebook.react.module.model.ReactModuleInfoProvider;
import java.util.Collections;

public final class AvvessaPackage extends BaseReactPackage {
    @Override public NativeModule getModule(String name, ReactApplicationContext context) {
        return AvvessaModule.NAME.equals(name) ? new AvvessaModule(context) : null;
    }
    @Override public ReactModuleInfoProvider getReactModuleInfoProvider() {
        return () -> Collections.singletonMap(AvvessaModule.NAME, new ReactModuleInfo(
            AvvessaModule.NAME, AvvessaModule.class.getName(), false, false, false,
            BuildConfig.IS_NEW_ARCHITECTURE_ENABLED));
    }
}
