package com.sandrox.avvessa.bridge;

import android.os.Looper;
import com.sandrox.avvessa.PushException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class AvvessaBridgeTest {
    private final AvvessaBridge bridge = new AvvessaBridge(RuntimeEnvironment.getApplication());
    private static JSONObject object(String json) { return BridgeArguments.parse(json); }
    private JSONObject call(String method, String args) {
        List<String> responses = new ArrayList<>();
        bridge.invoke(null, method, args, responses::add);
        assertEquals(1, responses.size());
        return object(responses.get(0));
    }
    private void error(String method, String args, String expected) throws Exception {
        JSONObject result = call(method, args);
        assertFalse(result.getBoolean("ok"));
        assertEquals(expected, result.getJSONObject("error").getString("code"));
    }
    @Test public void invalidInputNeverInitializesOrRequestsPermission() throws Exception {
        error("initialize", "{\"appKey\":true}", "invalid_argument");
        error("requestNotificationPermission", "{\"automatic\":true}", "invalid_argument");
        error("setBadge", "{\"badge\":1.5}", "invalid_argument");
        error("setConsentGranted", "{\"granted\":\"true\"}", "invalid_argument");
        JSONObject state = call("getState", "{}").getJSONObject("data");
        assertFalse(state.getBoolean("initialized"));
        assertFalse(state.getBoolean("consentGranted"));
    }
    @Test public void foreignIdentifiersCannotAccidentallyClearEveryNotification() throws Exception {
        error("clearNotifications", "{\"iosIdentifiers\":[\"one\"]}", "unsupported");
        error("clearNotifications", "{\"androidIds\":[1,-2]}", "invalid_argument");
    }
    @Test public void nativeObserverAcknowledgementPrecedesReplayAndCloseOnlyDisposesOwner() throws Exception {
        List<String> messages = new ArrayList<>();
        bridge.invoke(null, "subscribe", "{\"subscriptionId\":\"owner\",\"replayPending\":true}", messages::add);
        assertEquals("subscribed", object(messages.get(0)).getJSONObject("data").getString("kind"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("state", object(messages.get(1)).getJSONObject("data").getString("kind"));
        bridge.close();
        assertEquals("closed", object(messages.get(messages.size()-1)).getJSONObject("data").getString("kind"));
        int size = messages.size();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(size, messages.size());
        error("getState", "{}", "bridge_closed");
        AvvessaBridge other = new AvvessaBridge(RuntimeEnvironment.getApplication());
        List<String> results = new ArrayList<>();
        other.invoke(null, "getState", "{}", results::add);
        assertTrue(object(results.get(0)).getBoolean("ok"));
        other.close();
    }
    @Test public void argumentValidationPreservesStringsAndRejectsCoercion() throws Exception {
        assertEquals(" padded ", BridgeArguments.string(object("{\"alias\":\" padded \"}"), "alias"));
        assertThrows(PushException.class, () -> BridgeArguments.strings(object("{\"tags\":[\"a\",\"a\"]}"), "tags", false));
        assertThrows(PushException.class, () -> BridgeArguments.integer(object("{\"badge\":true}"), "badge", 0, 0, 10));
        assertThrows(PushException.class, () -> BridgeArguments.object(object("{\"ios\":null}"), "ios"));
        assertThrows(PushException.class, () -> BridgeArguments.parse("[]"));
    }
}
