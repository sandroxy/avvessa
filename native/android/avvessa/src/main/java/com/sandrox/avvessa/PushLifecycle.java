package com.sandrox.avvessa;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Observes foreground transitions without treating Activity navigation or rotation as app entry. */
final class PushLifecycle implements Application.ActivityLifecycleCallbacks {
    private final Runnable foregrounded;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Activity> started = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean foreground = PushNotifications.foreground();
    private final Runnable background = () -> foreground = false;
    PushLifecycle(Avvessa client) { this(client::foregrounded); }
    PushLifecycle(Runnable foregrounded) { this.foregrounded = foregrounded; }
    @Override public void onActivityStarted(Activity activity) {
        if (activity instanceof AvvessaNotificationActivity) return;
        main.removeCallbacks(background); started.add(activity);
        if (!foreground) { foreground = true; foregrounded.run(); }
    }
    @Override public void onActivityStopped(Activity activity) {
        if (activity instanceof AvvessaNotificationActivity) return;
        started.remove(activity);
        if (started.isEmpty() && !activity.isChangingConfigurations()) main.postDelayed(background, 700);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) { started.remove(activity); }
}
