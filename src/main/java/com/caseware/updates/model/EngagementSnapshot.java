package com.caseware.updates.model;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The template state read by loading an engagement.
 *
 * @param declinedVersion the highest version the engagement declined, if any
 * @param readAt when engagement-service began reading the engagement, by engagement-service's own clock: the same
 *     clock as the events' {@code sourceUpdatedAt}, so a decision made during the load orders after this snapshot
 *     whatever this pod's clock says
 */
public record EngagementSnapshot(
        String firmId, String productId, long appliedVersion, @Nullable Long declinedVersion, Instant readAt) {

    public EngagementSnapshot {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(readAt, "readAt");
    }
}
