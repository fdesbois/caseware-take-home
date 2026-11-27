package com.caseware.updates.model;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An engagement's current state: its latest history row by {@code sourceUpdatedAt}, plus the highest version it
 * ever declined (a decline pins every version up to it, even if a later row omits it).
 */
public record EngagementState(HistoryEntry latest, @Nullable Long maxDeclinedVersion) {

    public EngagementState {
        Objects.requireNonNull(latest, "latest");
    }
}
