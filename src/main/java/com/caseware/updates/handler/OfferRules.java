package com.caseware.updates.handler;

import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.PendingDecision;
import com.caseware.updates.model.PendingUpdate;
import com.caseware.updates.model.SummaryResult;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The offer rules as pure functions: no I/O, no clock. The caller takes the {@link #candidate}, summarizes its
 * version range, then turns the summary into a {@link #decide decision}.
 */
public final class OfferRules {

    private OfferRules() {}

    /** An offer the engagement is due, provided the two versions differ in content. */
    public record Candidate(String engagementId, String firmId, String productId, long fromVersion, long toVersion) {}

    /**
     * The offer from the engagement's applied version to the latest one. Empty when no offer applies: no state,
     * an unpublished product, ARCHIVED, {@code latest ≤ applied}, or {@code latest ≤ max(declined)}.
     */
    public static Optional<Candidate> candidate(Optional<EngagementState> state, OptionalLong latestVersion) {
        return state.flatMap(current -> latestVersion.stream()
                .boxed()
                .findFirst()
                .filter(latest -> isDue(current, latest))
                .map(latest -> new Candidate(
                        current.latest().engagementId(),
                        current.latest().firmId(),
                        current.latest().productId(),
                        current.latest().appliedVersion(),
                        latest)));
    }

    /** Not archived, behind the latest version, and the latest version is newer than anything declined. */
    private static boolean isDue(EngagementState state, long latest) {
        HistoryEntry current = state.latest();
        Long maxDeclined = state.maxDeclinedVersion();
        return current.eventType() != EngagementEventType.ARCHIVED
                && latest > current.appliedVersion()
                && (maxDeclined == null || latest > maxDeclined);
    }

    /** {@code EmptyDiff} → no offer (any existing one is deleted); {@code Ready} → offer the candidate's range. */
    public static PendingDecision decide(Candidate candidate, SummaryResult summary, Instant offeredAt) {
        return switch (summary) {
            case SummaryResult.EmptyDiff() -> new PendingDecision.NoOffer();
            case SummaryResult.Ready(String summaryKey) -> new PendingDecision.Offer(new PendingUpdate(
                    candidate.engagementId(),
                    candidate.firmId(),
                    candidate.productId(),
                    candidate.fromVersion(),
                    candidate.toVersion(),
                    summaryKey,
                    offeredAt));
        };
    }
}
