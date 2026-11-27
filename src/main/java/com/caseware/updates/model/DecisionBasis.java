package com.caseware.updates.model;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * What a pending decision was computed from: the engagement's state and its product's latest version. The
 * decision is written only if both are still current.
 *
 * <p>The whole state is compared, not only the latest {@code sourceUpdatedAt}: a late, older DECLINED can raise
 * the max declined version, and two rows can share the same instant.
 */
public record DecisionBasis(Optional<EngagementState> state, OptionalLong latestVersion) {

    public DecisionBasis {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(latestVersion, "latestVersion");
    }
}
