package com.sandrox.avvessa;

import android.app.Activity;
import android.os.Looper;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class PushLifecycleTest {
    @Test public void navigationAndNotificationTrampolinesDoNotRepeatForegroundCleanup() {
        AtomicInteger entries = new AtomicInteger();
        PushLifecycle lifecycle = new PushLifecycle(entries::incrementAndGet);
        ReflectionHelpers.setField(lifecycle,"foreground",true);
        Activity first = Robolectric.buildActivity(Activity.class).get();
        Activity second = Robolectric.buildActivity(Activity.class).get();
        lifecycle.onActivityStarted(first);
        lifecycle.onActivityStopped(first);
        lifecycle.onActivityStarted(second);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(0,entries.get());
        lifecycle.onActivityStopped(second);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        lifecycle.onActivityStarted(Robolectric.buildActivity(AvvessaNotificationActivity.class).get());
        assertEquals(0,entries.get());
        lifecycle.onActivityStarted(first);
        lifecycle.onActivityStarted(first);
        assertEquals(1,entries.get());
    }
}
