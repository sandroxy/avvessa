package com.sandrox.avvessa.bridge;

import com.sandrox.avvessa.PushException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BridgeArguments {
    static JSONObject parse(String json) {
        try { return new JSONObject(json); }
        catch (JSONException | NullPointerException e) { throw invalid("Arguments must be a JSON object"); }
    }
    static PushException invalid(String message) { return new PushException("invalid_argument", message); }
    static void fields(JSONObject object, String... fields) {
        Set<String> allowed = new HashSet<>(Arrays.asList(fields));
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); if (!allowed.contains(key)) throw invalid("Unknown argument: " + key); }
    }
    static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw invalid(key + " must be a nonempty string");
        return (String) value;
    }
    static String string(JSONObject object, String key, String fallback) { return object.has(key) ? string(object, key) : fallback; }
    static boolean bool(JSONObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw invalid(key + " must be a boolean");
        return (Boolean) value;
    }
    static long integer(JSONObject object, String key, long fallback, long min, long max) {
        if (!object.has(key)) return fallback;
        Object value = object.opt(key);
        if (!(value instanceof Number)) throw invalid(key + " must be an integer");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < min || number > max) throw invalid(key + " is outside its integer range");
        return ((Number) value).longValue();
    }
    static JSONObject object(JSONObject object, String key) {
        if (!object.has(key)) return new JSONObject();
        JSONObject value = object.optJSONObject(key);
        if (value == null) throw invalid(key + " must be an object");
        return value;
    }
    static JSONArray array(JSONObject object, String key) {
        JSONArray value = object.optJSONArray(key);
        if (value == null) throw invalid(key + " must be an array");
        return value;
    }
    static Set<String> strings(JSONObject object, String key, boolean empty) {
        JSONArray values = array(object, key);
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (!(value instanceof String) || ((String) value).isEmpty() || !result.add((String) value)) throw invalid(key + " must contain unique nonempty strings");
        }
        if (!empty && result.isEmpty()) throw invalid(key + " must not be empty");
        return result;
    }
    static JSONObject json(Object... values) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) object.put((String) values[i], values[i + 1] == null ? JSONObject.NULL : values[i + 1]); }
        catch (JSONException e) { throw new PushException("invalid_payload", "Cannot encode bridge response", e); }
        return object;
    }
}
