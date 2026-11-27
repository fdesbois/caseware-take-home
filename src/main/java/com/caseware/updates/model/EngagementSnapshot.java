package com.caseware.updates.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** The template state read by loading an engagement. */
public record EngagementSnapshot(
        String firmId, String productId, long appliedVersion, @Nullable Long declinedVersion) {

    public EngagementSnapshot {
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(productId, "productId");
    }
}
