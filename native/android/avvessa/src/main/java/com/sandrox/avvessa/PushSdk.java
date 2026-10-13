package com.sandrox.avvessa;

import android.content.Context;
import cn.jiguang.api.utils.JCollectionAuth;
import cn.jpush.android.api.JPushInterface;
import cn.jpush.android.data.JPushConfig;

/** SDK configuration shared by explicit initialization and service-process restoration. */
final class PushSdk {
    private PushSdk() {}

    static void validate(Context context, PushConfiguration config) {
        config.validateResources(context);
        Object manifestKey = PushVendors.metadata(context).get("JPUSH_APPKEY");
        if (manifestKey != null && !manifestKey.toString().isEmpty() && !manifestKey.toString().equals(config.appKey))
            throw new PushException("configuration_conflict", "Manifest AppKey differs from the runtime AppKey");
        PushVendors.validate(context);
    }

    static void initialize(Context context, PushConfiguration config) {
        JPushInterface.setDebugMode(config.debug);
        PushNotifications.ensureDefaultChannel(context, config);
        JPushConfig sdkConfig = new JPushConfig();
        sdkConfig.setjAppKey(config.appKey);
        PushVendors.apply(context, sdkConfig);
        // Callers have already checked durable consent. With this SDK, publishing auth
        // before init can enqueue service work before the process-local AppKey exists.
        JPushInterface.init(context, sdkConfig);
        JCollectionAuth.setAuth(context, true);
        // These calls can dispatch work to JCore. Supply this process's identity first.
        JCollectionAuth.enableAutoWakeup(context, false);
        JCollectionAuth.enableDynamicLoad(context, false);
        JPushInterface.setGeofenceEnable(context, false);
        JPushInterface.setSmartPushEnable(context, false);
        JPushInterface.setDataInsightsEnable(context, false);
        JPushInterface.setChannel(context, config.channel);
        JPushInterface.setNotificationCallBackEnable(context, true);
    }
}
