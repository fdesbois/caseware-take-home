package com.caseware.updates.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One {@code engagement_history} row. Rows are only ever added; the key is
 * {@code (engagementId, sourceUpdatedAt, eventType)}.
 */
public record HistoryEntry(
        String engagementId,
        Instant sourceUpdatedAt,
        EngagementEventType eventType,
        String firmId,
        String productId,
        long appliedVersion,
        @Nullable Long declinedVersion,
        @Nullable String summaryKey,
        @Nullable String actorId) {

    public HistoryEntry {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
    }

    /** The row recording an engagement-service event. */
    public static HistoryEntry of(EngagementStateUpdateEvent event) {
        return new HistoryEntry(
                event.engagementId(),
                event.sourceUpdatedAt(),
                event.type(),
                event.firmId(),
                event.productId(),
                event.appliedVersion(),
                event.declinedVersion(),
                event.summaryKey(),
                event.actorId());
    }

    /**
     * The row recording a loaded snapshot. It is stamped with engagement-service's read time, on the same clock as
     * the events, so any decision made during the load wins.
     */
    public static HistoryEntry synced(String engagementId, EngagementSnapshot snapshot) {
        return new HistoryEntry(
                engagementId,
                snapshot.readAt(),
                EngagementEventType.SYNCED,
                snapshot.firmId(),
                snapshot.productId(),
                snapshot.appliedVersion(),
                snapshot.declinedVersion(),
                null,
                null);
    }
}
