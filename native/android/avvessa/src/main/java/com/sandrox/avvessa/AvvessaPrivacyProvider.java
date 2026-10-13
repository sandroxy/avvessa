package com.sandrox.avvessa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import cn.jiguang.api.utils.JCollectionAuth;

/** Restores primary-process consent; explicit initialization remains the host's responsibility. */
public class AvvessaPrivacyProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        try { synchronize(); }
        catch (RuntimeException error) { Log.e("Avvessa", "Cannot restore push state; collection stays disabled", error); }
        return true;
    }
    protected void applyConsent() {
        boolean consent;
        try (EventStore store = new EventStore(getContext())) { consent = store.consent(); }
        JCollectionAuth.setAuth(getContext(), consent);
    }
    private synchronized void synchronize() {
        try { applyConsent(); }
        catch (RuntimeException error) {
            JCollectionAuth.setAuth(getContext(), false);
            throw error;
        }
    }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (!"syncConsent".equals(method)) throw new IllegalArgumentException("Unknown privacy operation");
        synchronize();
        return Bundle.EMPTY;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
