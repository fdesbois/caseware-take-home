package com.caseware.updates.model;

import java.time.Instant;
import java.util.Objects;

/** One {@code pending_update} row: the offer an engagement currently shows, from its applied version to the latest. */
public record PendingUpdate(
        String engagementId,
        String firmId,
        String productId,
        long fromVersion,
        long toVersion,
        String summaryKey,
        Instant offeredAt) {

    public PendingUpdate {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(summaryKey, "summaryKey");
        Objects.requireNonNull(offeredAt, "offeredAt");
    }
}
