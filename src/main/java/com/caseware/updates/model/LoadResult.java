package com.caseware.updates.model;

/** Outcome of loading an engagement. Codes are opaque (an HTTP status, "timeout"), never engagement content. */
public sealed interface LoadResult {

    /** The engagement was loaded. */
    record Loaded(EngagementSnapshot snapshot) implements LoadResult {}

    /** Worth retrying later: timeout, 429, 5xx. */
    record Transient(String code) implements LoadResult {}

    /** Retrying cannot help: 404, malformed engagement. */
    record Permanent(String code) implements LoadResult {}
}
