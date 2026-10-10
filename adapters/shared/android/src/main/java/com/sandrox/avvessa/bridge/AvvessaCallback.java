package com.sandrox.avvessa.bridge;

/** JSON envelopes; subscription callbacks remain active until explicitly removed. */
public interface AvvessaCallback { void onResult(String json); }
