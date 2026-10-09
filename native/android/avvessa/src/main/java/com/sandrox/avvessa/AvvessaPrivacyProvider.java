package com.sandrox.avvessa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import cn.jiguang.api.utils.JCollectionAuth;

/** Applies persisted consent before SDK components run, without initializing push. */
public class AvvessaPrivacyProvider extends ContentProvider {
    @Override public boolean onCreate() {
        if (getContext() == null) return false;
        applyConsent();
        return true;
    }
    private void applyConsent() {
        boolean consent = false;
        try (EventStore store = new EventStore(getContext())) { consent = store.consent(); }
        catch (RuntimeException error) { Log.e("Avvessa", "Cannot restore consent; collection stays disabled", error); }
        JCollectionAuth.setAuth(getContext(), consent);
        JCollectionAuth.enableAutoWakeup(getContext(), false);
        JCollectionAuth.enableDynamicLoad(getContext(), false);
    }
    @Override public Bundle call(String method, String argument, Bundle extras) {
        if (!"syncConsent".equals(method)) throw new IllegalArgumentException("Unknown privacy operation");
        applyConsent();
        return Bundle.EMPTY;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
