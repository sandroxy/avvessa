package com.sandrox.avvessa;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable, field-agnostic foreground rules. Context is transient and owned by the application. */
public final class NotificationPolicy {
    private final String encoded;

    public NotificationPolicy(JSONObject rules) {
        fields(rules, "suppressForeground", "activeContext");
        if (rules.has("suppressForeground")) {
            JSONArray list = rules.optJSONArray("suppressForeground");
            if (list == null || list.length() > 32) throw invalid("suppressForeground must contain at most 32 rules");
            for (int i = 0; i < list.length(); i++) validateRule(list.optJSONObject(i), false);
        }
        if (rules.has("activeContext")) validateRule(rules.optJSONObject("activeContext"), true);
        encoded = rules.toString();
    }

    public JSONObject toJson() { return Json.parse(encoded); }

    static JSONObject context(JSONObject value) {
        fields(value, "id", "category");
        if (scalar(value.opt("id")) == null || scalar(value.opt("id")).isEmpty())
            throw new PushException("invalid_argument", "Context id must be a nonempty scalar");
        if (value.has("category") && scalar(value.opt("category")) == null)
            throw new PushException("invalid_argument", "Context category must be a scalar");
        return Json.parse(value.toString());
    }

    /** null means allow; this method never interprets a business message name. */
    String suppressionReason(JSONObject data, JSONObject context, String platform) {
        if (data == null) return null;
        JSONObject rules = toJson(); JSONArray list = rules.optJSONArray("suppressForeground");
        if (list != null) for (int i = 0; i < list.length(); i++) {
            JSONObject rule = list.optJSONObject(i);
            if (applies(rule, platform) && matches(data, rule.optJSONObject("match")))
                return rule.optString("reason", "rule");
        }
        JSONObject active = rules.optJSONObject("activeContext");
        if (active == null || context == null || !applies(active, platform) || !matches(data, active.optJSONObject("match"))) return null;
        String actual = scalar(data.opt(active.optString("idField")));
        if (actual == null || !actual.equals(scalar(context.opt("id")))) return null;
        if (active.has("categoryField")) {
            actual = scalar(data.opt(active.optString("categoryField")));
            if (actual == null || !actual.equals(scalar(context.opt("category")))) return null;
        }
        return active.optString("reason", "activeContext");
    }

    private static boolean applies(JSONObject rule, String platform) {
        JSONArray values = rule.optJSONArray("platforms");
        if (values == null) return true;
        for (int i = 0; i < values.length(); i++) if (platform.equals(values.opt(i))) return true;
        return false;
    }
    private static boolean matches(JSONObject data, JSONObject match) {
        for (Iterator<String> keys = match.keys(); keys.hasNext();) {
            String key = keys.next(), value = scalar(data.opt(key));
            if (value == null || !value.equals(scalar(match.opt(key)))) return false;
        }
        return true;
    }
    private static void validateRule(JSONObject rule, boolean active) {
        if (active) fields(rule, "match", "platforms", "reason", "idField", "categoryField");
        else fields(rule, "match", "platforms", "reason");
        JSONObject match = rule.optJSONObject("match");
        if (match == null || match.length() == 0 || match.length() > 32) throw invalid("match must contain 1 to 32 scalar fields");
        for (Iterator<String> keys = match.keys(); keys.hasNext();) {
            String key = keys.next();
            if (key.trim().isEmpty() || key.length() > 128 || scalar(match.opt(key)) == null) throw invalid("Invalid match field or scalar value");
        }
        if (active) text(rule, "idField");
        if (rule.has("categoryField")) text(rule, "categoryField");
        if (rule.has("reason")) text(rule, "reason");
        if (rule.has("platforms")) {
            JSONArray platforms = rule.optJSONArray("platforms"); Set<String> seen = new HashSet<>();
            if (platforms == null || platforms.length() == 0) throw invalid("platforms must not be empty");
            for (int i = 0; i < platforms.length(); i++) {
                Object value = platforms.opt(i);
                if (!(value instanceof String) || !Arrays.asList("android", "ios").contains(value) || !seen.add((String) value))
                    throw invalid("platforms must contain unique android/ios values");
            }
        }
    }
    static void fields(JSONObject value, String... allowed) {
        if (value == null) throw invalid("Policy values must be objects");
        for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String key = keys.next(); if (!Arrays.asList(allowed).contains(key)) throw invalid("Unknown policy field: " + key);
        }
    }
    private static void text(JSONObject value, String key) {
        Object text = value.opt(key);
        if (!(text instanceof String) || ((String) text).trim().isEmpty() || ((String) text).length() > 128) throw invalid(key + " must contain 1 to 128 characters");
    }
    static String scalar(Object value) {
        if (value instanceof String) return ((String) value).length() <= 1024 ? (String) value : null;
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number) || Math.abs(number) > 9007199254740991d) return null;
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        return null;
    }
    static PushException invalid(String message) { return new PushException("invalid_configuration", message); }
}
