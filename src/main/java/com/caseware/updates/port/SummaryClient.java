package com.caseware.updates.port;

import com.caseware.updates.model.SummaryResult;

/** summary-service (global, template data only). A call can take 30–60 s. */
public interface SummaryClient {

    /**
     * The human-readable summary of {@code fromVersion → toVersion}, or {@code EmptyDiff} when both versions have
     * the same content. Throws when the summary cannot be produced right now; the message is then redelivered.
     */
    SummaryResult summarize(String productId, long fromVersion, long toVersion);
}
