package com.sandrox.avvessa;

import android.app.Application;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Storage checks never initialize JPush or install the library's startup providers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class EventStoreTest {
    private Application context;
    private EventStore store;
    private PushConfiguration config;
    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(new File(context.getNoBackupFilesDir(), "avvessa.db").getPath());
        store = new EventStore(context);
        config = new PushConfiguration.Builder("000000000000000000000000")
                .smallIcon(android.R.drawable.ic_dialog_info).build();
    }
    @After public void tearDown() { store.close(); }
    private PushEvent append(String type, String message, long now) {
        return store.append(type, message, "jpush:0", new JSONObject(), new JSONObject(),
                "Title", "Body", config, now);
    }
    @Test public void replaySurvivesReopenAndAckDoesNotReinsertDuplicate() {
        PushEvent first = append("notificationReceived", "9007199254740993", 100000);
        store.close(); store = new EventStore(context);
        List<PushEvent> pending = store.pending(config, 100001);
        assertEquals(first.id, pending.get(0).id);
        assertEquals("9007199254740993", pending.get(0).messageId);
        assertTrue(pending.get(0).replayed);
        assertTrue(store.acknowledge(first.id));
        assertNull(append("notificationReceived", "9007199254740993", 100002));
        assertNotNull(append("notificationOpened", "9007199254740993", 100003));
        assertEquals(1, store.pending(config, 100004).size());
    }
    @Test public void absentMessageIdentityDoesNotDeduplicateMatchingContent() {
        assertNotEquals(append("customMessage", null, 100000).id,
                append("customMessage", null, 100000).id);
        assertEquals(2, store.pending(config, 100001).size());
    }
    @Test public void capacityAndExpirationAreReported() {
        config = new PushConfiguration.Builder("000000000000000000000000").eventRetention(2, 60000).build();
        append("customMessage", "a", 100000); append("customMessage", "b", 100001);
        PushEvent last = append("customMessage", "c", 100002);
        List<PushEvent> pending = store.pending(config, 100003);
        assertEquals(2, pending.size()); assertEquals(last.id, pending.get(1).id);
        assertEquals("1", store.state("droppedEvents"));
        assertTrue(store.pending(config, 160003).isEmpty());
        assertEquals("3", store.state("droppedEvents"));
    }
    @Test public void revokeAtomicallyClearsPersonalStateAndEvents() {
        store.state("consent", "true"); store.state("registrationId", "device");
        append("customMessage", "a", 100000); store.revokeConsent();
        try (EventStore reopened = new EventStore(context)) {
            assertFalse(reopened.consent()); assertNull(reopened.state("registrationId"));
            assertTrue(reopened.pending(config, 100001).isEmpty());
        }
    }
    @Test public void concurrentStoresPersistOneCopyOfAnIdentifiedEvent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable append = () -> {
            try (EventStore other = new EventStore(context)) {
                start.await();
                other.append("notificationOpened", "a", "jpush:0", new JSONObject(), new JSONObject(),
                        null, null, config, 100000);
            } catch (Throwable error) { failure.set(error); }
        };
        Thread first = new Thread(append); Thread second = new Thread(append);
        first.start(); second.start(); start.countDown(); first.join(); second.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        assertEquals(1, store.pending(config, 100001).size());
    }
    @Test public void sizeLimitLeavesThePreviousQueueIntact() {
        PushEvent previous = append("customMessage", "a", 100000);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < EventStore.MAX_EVENT_BYTES; i++) huge.append('x');
        try {
            store.append("customMessage", "b", "jpush:0", new JSONObject(),
                    Json.put(new JSONObject(), "body", huge.toString()), null, null, config, 100001);
            fail("Expected event_too_large");
        } catch (PushException error) { assertEquals("event_too_large", error.code); }
        assertEquals(previous.id, store.pending(config, 100002).get(0).id);
    }
    @Test public void iconIdentityUsesAResourceNameAcrossProcesses() {
        JSONObject encoded = config.toJson(context);
        assertEquals("android:drawable/ic_dialog_info", encoded.optString("smallIcon"));
        assertEquals(config.smallIcon, PushConfiguration.fromJson(context, encoded).smallIcon);
    }
    @Test public void malformedExtrasFailWithoutInventingBusinessData() {
        try { Json.extras("not-json"); fail("Expected invalid_data"); }
        catch (PushException error) { assertEquals("invalid_data", error.code); }
    }
}
