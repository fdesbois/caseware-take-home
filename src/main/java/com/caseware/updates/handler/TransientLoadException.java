package com.caseware.updates.handler;

import java.io.Serial;

/** A load failed in a way worth retrying (timeout, 429, 5xx). Thrown so SQS redelivers the message. */
public final class TransientLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    public TransientLoadException(String engagementId, String code) {
        super("Transient load failure, engagementId=" + engagementId + " code=" + code);
        this.code = code;
    }

    /** The opaque failure code, such as {@code "503"} or {@code "timeout"}. */
    public String code() {
        return code;
    }
}
