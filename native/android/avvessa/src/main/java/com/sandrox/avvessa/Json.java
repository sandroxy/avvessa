package com.sandrox.avvessa;

import org.json.JSONException;
import org.json.JSONObject;

final class Json {
    private Json() {}
    static JSONObject parse(String value) {
        try { return new JSONObject(value); }
        catch (JSONException error) { throw new PushException("invalid_data", "Expected a JSON object", error); }
    }
    static JSONObject copy(JSONObject value) { return parse(value.toString()); }
    static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value == null ? JSONObject.NULL : value); return object; }
        catch (JSONException error) { throw new PushException("invalid_data", "Cannot encode " + key, error); }
    }
    static JSONObject extras(String value) {
        if (value == null || value.isEmpty()) return new JSONObject();
        return parse(value);
    }
}
