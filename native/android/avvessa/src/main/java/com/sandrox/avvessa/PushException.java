package com.sandrox.avvessa;

/** A stable plugin error category with an optional original SDK code. */
public final class PushException extends RuntimeException {
    public final String code;
    public final Integer nativeCode;

    public PushException(String code, String message) { this(code, message, null, null); }
    public PushException(String code, String message, Throwable cause) { this(code, message, null, cause); }
    public PushException(String code, String message, Integer nativeCode, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.nativeCode = nativeCode;
    }
}
