package com.caseware.updates.model;

/** Outcome of summarizing a version range. */
public sealed interface SummaryResult {

    /** A validated summary, stored under this key. */
    record Ready(String summaryKey) implements SummaryResult {}

    /** Both versions have the same content (for example, a withdrawal restored it): nothing to offer. */
    record EmptyDiff() implements SummaryResult {}
}
