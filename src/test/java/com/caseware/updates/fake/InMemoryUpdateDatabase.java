package com.caseware.updates.fake;

import com.caseware.updates.model.DecisionBasis;
import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.PendingDecision;
import com.caseware.updates.model.PendingUpdate;
import com.caseware.updates.port.EngagementHistoryRepository;
import com.caseware.updates.port.PendingUpdateRepository;
import com.caseware.updates.port.ProductVersionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The region's update-db in memory, and the reference implementation of the three repository contracts. One
 * object serves all three ports because the tables share one database: {@link #applyIfUnchanged} reads the
 * history and the product version as part of its compare-and-set.
 *
 * <p>Thread-safe. {@code applyIfUnchanged} is atomic per engagement, like a transaction that locks the
 * engagement's {@code pending_update} key; appends do not wait for it, because each append is followed by its own
 * evaluation.
 */
public final class InMemoryUpdateDatabase
        implements EngagementHistoryRepository, ProductVersionRepository, PendingUpdateRepository {

    /** Latest first by sourceUpdatedAt; ties broken by event type, as the port contract states. */
    private static final Comparator<HistoryEntry> RECENCY =
            Comparator.comparing(HistoryEntry::sourceUpdatedAt).thenComparing(HistoryEntry::eventType);

    private final ConcurrentMap<String, ConcurrentMap<RowKey, HistoryEntry>> history = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> latestVersions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingUpdate> pendingUpdates = new ConcurrentHashMap<>();

    @Override
    public boolean append(HistoryEntry entry) {
        return history.computeIfAbsent(entry.engagementId(), id -> new ConcurrentHashMap<>())
                        .putIfAbsent(new RowKey(entry.sourceUpdatedAt(), entry.eventType()), entry)
                == null;
    }

    @Override
    public boolean hasAnyEntry(String engagementId) {
        return !rows(engagementId).isEmpty();
    }

    @Override
    public Optional<EngagementState> currentState(String engagementId) {
        List<HistoryEntry> rows = rows(engagementId);
        Long maxDeclined = rows.stream()
                .map(HistoryEntry::declinedVersion)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return rows.stream().max(RECENCY).map(latest -> new EngagementState(latest, maxDeclined));
    }

    @Override
    public OptionalLong latestVersion(String productId) {
        Long version = latestVersions.get(productId);
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }

    @Override
    public boolean applyIfUnchanged(String engagementId, PendingDecision decision, DecisionBasis basis) {
        AtomicBoolean applied = new AtomicBoolean();
        pendingUpdates.compute(engagementId, (id, current) -> {
            if (!basis.equals(basisNow(id))) {
                return current;
            }
            applied.set(true);
            return switch (decision) {
                case PendingDecision.NoOffer() -> null;
                case PendingDecision.Offer(PendingUpdate offer) -> sameOffer(current, offer) ? current : offer;
            };
        });
        return applied.get();
    }

    /** Publishes a product version. Versions only increase, so a late, older one changes nothing. */
    public void publishVersion(String productId, long version) {
        latestVersions.merge(productId, version, Math::max);
    }

    public Optional<PendingUpdate> pendingUpdate(String engagementId) {
        return Optional.ofNullable(pendingUpdates.get(engagementId));
    }

    private DecisionBasis basisNow(String engagementId) {
        Optional<EngagementState> state = currentState(engagementId);
        OptionalLong latest = state.isPresent()
                ? latestVersion(state.get().latest().productId())
                : OptionalLong.empty();
        return new DecisionBasis(state, latest);
    }

    /** A consistent snapshot of the engagement's rows. */
    private List<HistoryEntry> rows(String engagementId) {
        ConcurrentMap<RowKey, HistoryEntry> rows = history.get(engagementId);
        return rows == null ? List.of() : List.copyOf(rows.values());
    }

    /** The same offer apart from when it was made, so re-evaluating an unchanged offer keeps its offeredAt. */
    private static boolean sameOffer(PendingUpdate current, PendingUpdate offer) {
        return current != null
                && current.equals(new PendingUpdate(
                        offer.engagementId(),
                        offer.firmId(),
                        offer.productId(),
                        offer.fromVersion(),
                        offer.toVersion(),
                        offer.summaryKey(),
                        current.offeredAt()));
    }

    private record RowKey(Instant sourceUpdatedAt, EngagementEventType eventType) {}
}
