package com.sandrox.avvessa;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class NotificationPolicyTest {
    private JSONObject fixtures() throws Exception {
        try (java.io.InputStream stream = getClass().getResourceAsStream("/notification-policy.json")) {
            assertNotNull(stream); return new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test public void nativePlatformsShareTheSamePolicyExamples() throws Exception {
        JSONArray cases = fixtures().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject entry = cases.getJSONObject(i);
            String actual = new NotificationPolicy(entry.getJSONObject("policy")).suppressionReason(
                    entry.getJSONObject("data"), entry.optJSONObject("context"), entry.getString("platform"));
            assertEquals(entry.getString("name"), entry.isNull("reason") ? null : entry.getString("reason"), actual);
        }
    }
    @Test public void malformedRulesNeverBecomeAnUnconditionalSuppression() throws Exception {
        JSONArray invalid = fixtures().getJSONArray("invalidPolicies");
        for (int i = 0; i < invalid.length(); i++) {
            JSONObject input = invalid.getJSONObject(i);
            assertThrows(input.toString(), PushException.class, () -> new NotificationPolicy(input));
        }
    }
    @Test public void policiesAreImmutableAndPersistWithTheNativeConfiguration() throws Exception {
        JSONObject input = new JSONObject("{\"suppressForeground\":[{\"match\":{\"kind\":\"quiet\"}}]}");
        NotificationPolicy policy = new NotificationPolicy(input);
        input.getJSONArray("suppressForeground").getJSONObject(0).getJSONObject("match").put("kind", "changed");
        PushConfiguration config = new PushConfiguration.Builder("000000000000000000000000")
                .smallIcon(android.R.drawable.ic_dialog_info).presentationPolicy(policy)
                .notificationLifecycle(new NotificationLifecycle(new JSONObject("{\"clearBadgeOnOpen\":true}"))).build();
        PushConfiguration restored = PushConfiguration.fromJson(RuntimeEnvironment.getApplication(), config.toJson(RuntimeEnvironment.getApplication()));
        assertEquals("rule", restored.presentationPolicy.suppressionReason(new JSONObject("{\"kind\":\"quiet\"}"), null, "android"));
        assertTrue(restored.notificationLifecycle.clearBadgeOnOpen);
        assertFalse(restored.notificationLifecycle.clearDeliveredOnStart);
    }
    @Test public void contextAndLifecycleRejectInvalidValues() {
        assertThrows(PushException.class, () -> NotificationPolicy.context(new JSONObject()));
        assertThrows(PushException.class, () -> new NotificationLifecycle(Json.parse("{\"clearBadgeOnStart\":\"true\"}")));
        assertThrows(PushException.class, () -> new NotificationLifecycle(Json.parse("{\"unknown\":true}")));
    }
}
