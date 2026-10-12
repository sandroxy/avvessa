package com.sandrox.avvessa;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;

/** Transactions keep event insertion, deduplication and eviction consistent across processes. */
final class EventStore extends SQLiteOpenHelper {
    static final int MAX_EVENT_BYTES = 256 * 1024;
    static final int MAX_QUEUE_BYTES = 4 * 1024 * 1024;
    static final long DEDUP_MILLIS = 60000;

    EventStore(Context context) {
        super(context, new File(context.getNoBackupFilesDir(), "avvessa.db").getPath(), null, 2,
                database -> { throw new PushException("storage_corrupt", "Avvessa storage is corrupt"); });
    }

    @Override public void onCreate(SQLiteDatabase database) {
        // SQLiteOpenHelper can observe version zero concurrently in separate processes.
        database.execSQL("CREATE TABLE IF NOT EXISTS state (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        database.execSQL("CREATE TABLE IF NOT EXISTS events (position INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, received INTEGER NOT NULL, bytes INTEGER NOT NULL, payload TEXT NOT NULL)");
        database.execSQL("CREATE TABLE IF NOT EXISTS dedup (identity TEXT PRIMARY KEY, expires INTEGER NOT NULL, messageId TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase database, int from, int to) {
        if (from == 1 && to == 2) { database.execSQL("ALTER TABLE dedup ADD COLUMN messageId TEXT"); return; }
        throw new PushException("storage_version", "Unsupported storage migration");
    }

    synchronized String state(String name) {
        try (Cursor cursor = getReadableDatabase().query("state", new String[]{"value"}, "name=?",
                new String[]{name}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
    synchronized void state(String name, String value) {
        SQLiteDatabase database = getWritableDatabase();
        if (value == null) { database.delete("state", "name=?", new String[]{name}); return; }
        ContentValues values = new ContentValues(); values.put("name", name); values.put("value", value);
        if (database.insertWithOnConflict("state", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new PushException("storage_write", "Unable to save push state");
    }
    boolean consent() { return "true".equals(state("consent")); }

    synchronized void revokeConsent() {
        SQLiteDatabase database = getWritableDatabase(); database.beginTransaction();
        try {
            state("consent", "false"); state("registrationId", null);
            database.delete("events", null, null); database.delete("dedup", null, null);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }

    synchronized PushEvent append(String type, String messageId, String source, JSONObject data,
            JSONObject raw, String title, String body, PushConfiguration config, long now) {
        return append(type, messageId, source, data, raw, title, body, null, config, now);
    }
    synchronized PushEvent append(String type, String messageId, String source, JSONObject data,
            JSONObject raw, String title, String body, JSONObject presentation, PushConfiguration config, long now) {
        JSONObject payload = new JSONObject();
        Json.put(payload, "id", UUID.randomUUID().toString()); Json.put(payload, "type", type);
        Json.put(payload, "messageId", messageId); Json.put(payload, "source", source);
        Json.put(payload, "platform", "android"); Json.put(payload, "receivedAt", now);
        Json.put(payload, "data", data); Json.put(payload, "raw", raw);
        Json.put(payload, "title", title); Json.put(payload, "body", body);
        Json.put(payload, "presentation", presentation);
        if (type.startsWith("notification") && raw.has("notificationId")) Json.put(payload, "notificationId", raw.opt("notificationId"));
        String encoded = payload.toString();
        int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_EVENT_BYTES) throw new PushException("event_too_large", "Push event exceeds storage limit");
        String message = messageId == null || messageId.isEmpty() ? null : messageId;
        List<String> identities = new ArrayList<>();
        if (message != null) identities.add(new JSONArray().put(type).put("message").put(message).toString());
        if (type.startsWith("notification") && raw.opt("notificationId") instanceof Number)
            identities.add(new JSONArray().put(type).put("notification").put(raw.optInt("notificationId")).toString());
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            prune(database, config, now);
            boolean duplicate = false; long expiry = now + DEDUP_MILLIS;
            for (String identity : identities) {
                try (Cursor cursor = database.rawQuery("SELECT expires, messageId FROM dedup WHERE identity=?", new String[]{identity})) {
                    if (cursor.moveToFirst()) {
                        String known = cursor.getString(1);
                        // Reusing a system notification ID for a different message must not lose that message.
                        if (message == null || known == null || message.equals(known)) { duplicate = true; expiry = Math.min(expiry, cursor.getLong(0)); }
                    }
                }
            }
            for (String identity : identities) {
                ContentValues seen = new ContentValues(); seen.put("identity", identity); seen.put("expires", expiry);
                // An unidentified callback must not erase a previously known strong identity.
                String known = message;
                if (known == null) try (Cursor cursor = database.rawQuery("SELECT messageId FROM dedup WHERE identity=?", new String[]{identity})) {
                    if (cursor.moveToFirst()) known = cursor.getString(0);
                }
                seen.put("messageId", known);
                if (database.insertWithOnConflict("dedup", null, seen, SQLiteDatabase.CONFLICT_REPLACE) == -1)
                    throw new PushException("storage_write", "Cannot save notification identity");
            }
            if (duplicate) { prune(database, config, now); database.setTransactionSuccessful(); return null; }
            ContentValues values = new ContentValues(); values.put("id", payload.optString("id"));
            values.put("received", now); values.put("bytes", bytes); values.put("payload", encoded);
            database.insertOrThrow("events", null, values);
            prune(database, config, now);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return new PushEvent(encoded, false);
    }

    private void prune(SQLiteDatabase database, PushConfiguration config, long now) {
        database.delete("dedup", "expires<=?", new String[]{Long.toString(now)});
        int removed = database.delete("events", "received<=?", new String[]{Long.toString(now - config.eventTtlMillis)});
        while (true) {
            long count, bytes;
            try (Cursor cursor = database.rawQuery("SELECT COUNT(*), COALESCE(SUM(bytes),0) FROM events", null)) {
                cursor.moveToFirst(); count = cursor.getLong(0); bytes = cursor.getLong(1);
            }
            if (count <= config.maxPendingEvents && bytes <= MAX_QUEUE_BYTES) break;
            removed += database.delete("events", "position=(SELECT MIN(position) FROM events)", null);
        }
        database.execSQL("DELETE FROM dedup WHERE identity IN (SELECT identity FROM dedup ORDER BY expires DESC LIMIT -1 OFFSET 5000)");
        if (removed > 0) {
            String existing = state("droppedEvents");
            state("droppedEvents", Long.toString((existing == null ? 0 : Long.parseLong(existing)) + removed));
        }
    }

    synchronized List<PushEvent> pending(PushConfiguration config, long now) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            prune(database, config, now);
            List<PushEvent> result = new ArrayList<>();
            try (Cursor cursor = database.query("events", new String[]{"payload"}, null, null, null, null, "position ASC")) {
                while (cursor.moveToNext()) result.add(new PushEvent(cursor.getString(0), true));
            }
            database.setTransactionSuccessful();
            return result;
        } finally { database.endTransaction(); }
    }

    synchronized boolean acknowledge(String id) {
        if (id == null || id.isEmpty()) throw new PushException("invalid_argument", "event id is required");
        return getWritableDatabase().delete("events", "id=?", new String[]{id}) > 0;
    }
    synchronized void clearEvents() {
        SQLiteDatabase database = getWritableDatabase(); database.beginTransaction();
        try {
            database.delete("events", null, null);
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
    }
}
