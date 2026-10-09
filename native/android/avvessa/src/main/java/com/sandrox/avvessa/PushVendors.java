package com.sandrox.avvessa;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import cn.jpush.android.data.JPushConfig;
import java.util.LinkedHashSet;
import java.util.Set;

final class PushVendors {
    static final String[] NAMES = {"xiaomi", "huawei", "oppo", "vivo", "honor", "meizu", "fcm"};
    private PushVendors() {}
    static Bundle metadata(Context context) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            return app.metaData == null ? Bundle.EMPTY : app.metaData;
        } catch (PackageManager.NameNotFoundException error) {
            throw new PushException("invalid_configuration", "Cannot read application metadata", error);
        }
    }
    static Set<String> installed(Context context) {
        Bundle data = metadata(context);
        Set<String> result = new LinkedHashSet<>();
        for (String name : NAMES) if (data.getBoolean("com.sandrox.avvessa.vendor." + name)) result.add(name);
        return result;
    }
    @android.annotation.SuppressLint("DiscouragedApi") // FCM is optional; the base artifact has no Firebase resource dependency.
    static void validate(Context context) {
        Bundle data = metadata(context);
        Set<String> vendors = installed(context);
        if (vendors.contains("xiaomi")) require(data, "XIAOMI_APPID", "XIAOMI_APPKEY");
        if (vendors.contains("oppo")) require(data, "OPPO_APPID", "OPPO_APPKEY", "OPPO_APPSECRET");
        if (vendors.contains("meizu")) require(data, "MEIZU_APPID", "MEIZU_APPKEY");
        if (vendors.contains("vivo")) require(data, "com.vivo.push.app_id", "com.vivo.push.api_key");
        if (vendors.contains("honor")) require(data, "com.hihonor.push.app_id");
        if (vendors.contains("fcm")) {
            int resource = context.getResources().getIdentifier("google_app_id", "string", context.getPackageName());
            if (resource == 0 || context.getString(resource).trim().isEmpty())
                throw new PushException("invalid_configuration", "FCM requires google-services.json and the Google Services Gradle plugin");
        }
    }
    private static void require(Bundle data, String... names) {
        for (String name : names) {
            Object value = data.get(name);
            String text = value == null ? "" : value.toString().replace("\\", "").trim();
            if (text.isEmpty() || text.equals("0") || text.contains("${"))
                throw new PushException("invalid_configuration", "Selected vendor requires Android metadata " + name);
        }
    }
    static void apply(Context context, JPushConfig config) {
        Bundle data = metadata(context);
        Set<String> vendors = installed(context);
        // Android metadata can encode numeric application IDs as numbers. The SDK's
        // dynamic configuration accepts their exact string values on every channel.
        if (vendors.contains("xiaomi")) {
            config.setXmAppId(data.get("XIAOMI_APPID").toString());
            config.setXmAppKey(data.get("XIAOMI_APPKEY").toString());
        }
        if (vendors.contains("oppo")) {
            config.setOppoAppId(data.get("OPPO_APPID").toString());
            config.setOppoAppKey(data.get("OPPO_APPKEY").toString());
            config.setOppoAppSecret(data.get("OPPO_APPSECRET").toString());
        }
        if (vendors.contains("meizu")) {
            config.setMzAppId(data.get("MEIZU_APPID").toString());
            config.setMzAppKey(data.get("MEIZU_APPKEY").toString());
        }
    }
}
