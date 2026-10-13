package com.sandrox.avvessa;

import cn.jiguang.api.utils.JCollectionAuth;

/** Restores a previously initialized SDK before components run in a new push process. */
public final class AvvessaPushPrivacyProvider extends AvvessaPrivacyProvider {
    private boolean restored;

    @Override protected void applyConsent() {
        String saved;
        try (EventStore store = new EventStore(getContext())) { saved = store.restorableConfiguration(); }
        if (saved == null) {
            restored = false;
            JCollectionAuth.setAuth(getContext(), false);
            return;
        }
        PushConfiguration config = PushConfiguration.fromJson(getContext(), Json.parse(saved));
        PushSdk.validate(getContext(), config);
        if (!restored) {
            PushSdk.initialize(getContext(), config);
            restored = true;
        } else JCollectionAuth.setAuth(getContext(), true);
    }
}
