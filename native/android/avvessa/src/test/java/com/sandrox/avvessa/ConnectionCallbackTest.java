package com.sandrox.avvessa;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import cn.jpush.android.api.CmdMessage;
import cn.jpush.android.api.JPushInterface;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** SDK query responses must not become errors or recursively query the SDK. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, shadows = ConnectionCallbackTest.SdkState.class,
        instrumentedPackages = "cn.jpush.android.api")
public class ConnectionCallbackTest {
    private Application context;
    private Avvessa client;
    private final AvvessaReceiver receiver = new AvvessaReceiver();
    private final List<PushState> states = new ArrayList<>();
    private final List<PushException> errors = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SdkState.connected = false;
        SdkState.queries = 0;
        client = Avvessa.getInstance(context);
        ((EventStore) ReflectionHelpers.getField(client, "store")).state("consent", "true");
        // Exercise callbacks without initializing the SDK or opening a network connection.
        ReflectionHelpers.setField(client, "initialized", true);
        client.subscribe(new Avvessa.Listener() {
            @Override public void onEvent(PushEvent event) {}
            @Override public void onStateChanged(PushState state) { states.add(state); }
            @Override public void onError(PushException error) { errors.add(error); }
        }, false);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void tearDown() {
        ((EventStore) ReflectionHelpers.getField(client, "store")).close();
        ReflectionHelpers.setStaticField(Avvessa.class, "shared", null);
    }

    @Test public void disconnectedAndConnectedQueryResponsesDoNotReportErrorsOrQueryAgain() {
        int queries = SdkState.queries;
        int notifications = states.size();

        receiver.onCommandResult(context, new CmdMessage(2004, 1));
        receiver.onCommandResult(context, new CmdMessage(2004, 0));
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(errors.isEmpty());
        assertEquals(queries, SdkState.queries);
        assertEquals(notifications, states.size());
        assertNull(client.getState().lastError);
    }

    @Test public void connectionTransitionsStillReachObserversWithoutAnError() {
        SdkState.connected = true;
        receiver.onConnected(context, true);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Boolean.TRUE, states.get(states.size() - 1).connected);

        SdkState.connected = false;
        receiver.onConnected(context, false);
        receiver.onCommandResult(context, new CmdMessage(2004, 1));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Boolean.FALSE, states.get(states.size() - 1).connected);
        assertNull(client.getState().lastError);
        assertTrue(errors.isEmpty());
    }

    @Test public void stateQueryResponsesDoNotClearAnUnrelatedCommandFailure() {
        receiver.onCommandResult(context, new CmdMessage(2000, 10001));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, errors.size());
        PushException failure = errors.get(0);

        receiver.onCommandResult(context, new CmdMessage(2004, 0));
        receiver.onCommandResult(context, new CmdMessage(2004, 1));
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, errors.size());
        assertEquals("sdk_error", failure.code);
        assertEquals(Integer.valueOf(10001), failure.nativeCode);
        assertSame(failure, client.getState().lastError);
    }

    @Test public void unexpectedConnectionResultIsStillReported() {
        receiver.onCommandResult(context, new CmdMessage(2004, 10001));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, errors.size());
        assertEquals(Integer.valueOf(10001), errors.get(0).nativeCode);
    }

    @Implements(JPushInterface.class)
    public static class SdkState {
        static boolean connected;
        static int queries;
        @Implementation protected static void __staticInitializer__() {}
        @Implementation protected static boolean getConnectionState(Context context) {
            queries++;
            return connected;
        }
        @Implementation protected static boolean isPushStopped(Context context) { return false; }
    }
}
