package com.caseware.updates.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Published by engagement-service on {@code engagement-events} after every change, carrying the engagement's full
 * state, so the latest event alone describes the engagement.
 *
 * @param summaryKey the summary the user saw, on ACCEPTED and DECLINED
 * @param actorId who made the decision, on ACCEPTED and DECLINED
 * @param sourceUpdatedAt when engagement-service made the change; orders the history
 */
public record EngagementStateUpdateEvent(
        String engagementId,
        String firmId,
        String productId,
        Region region,
        EngagementEventType type,
        long appliedVersion,
        @Nullable Long declinedVersion,
        @Nullable String summaryKey,
        @Nullable String actorId,
        Instant sourceUpdatedAt)
        implements EngagementMessage {

    public EngagementStateUpdateEvent {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sourceUpdatedAt, "sourceUpdatedAt");
    }
}
