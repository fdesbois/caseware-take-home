package com.caseware.updates.model;

import java.util.Objects;

/** Queued nightly on {@code engagement-sync} for an engagement that has no history row yet. */
public record SyncRequest(String engagementId, Region region) implements EngagementMessage {

    public SyncRequest {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(region, "region");
    }
}
