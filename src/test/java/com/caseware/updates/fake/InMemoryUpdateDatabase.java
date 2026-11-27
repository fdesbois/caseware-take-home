package com.caseware.updates.fake;

import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.port.EngagementHistoryRepository;
import com.caseware.updates.port.PendingUpdateRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The region's update-db in memory, and the reference implementation of the two repository contracts this
 * component uses. Thread-safe.
 */
public final class InMemoryUpdateDatabase implements EngagementHistoryRepository, PendingUpdateRepository {

    /** An offer as the publish fan-out (Flow B) would have written it. */
    public record Offer(long fromVersion, long toVersion) {}

    /** Latest first by sourceUpdatedAt; ties broken by event type, as the port contract states. */
    private static final Comparator<HistoryEntry> RECENCY =
            Comparator.comparing(HistoryEntry::sourceUpdatedAt).thenComparing(HistoryEntry::eventType);

    private final ConcurrentMap<String, ConcurrentMap<RowKey, HistoryEntry>> history = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Offer> offers = new ConcurrentHashMap<>();

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
    public void removeOffer(String engagementId) {
        offers.remove(engagementId);
    }

    @Override
    public void removeOfferUpTo(String engagementId, long version) {
        offers.computeIfPresent(engagementId, (id, offer) -> offer.toVersion() <= version ? null : offer);
    }

    @Override
    public void removeOfferStartingBefore(String engagementId, long appliedVersion) {
        offers.computeIfPresent(engagementId, (id, offer) -> offer.fromVersion() < appliedVersion ? null : offer);
    }

    /** Writes an offer the way the publish fan-out would; tests use it to set up the table. */
    public void putOffer(String engagementId, long fromVersion, long toVersion) {
        offers.put(engagementId, new Offer(fromVersion, toVersion));
    }

    public Optional<Offer> offer(String engagementId) {
        return Optional.ofNullable(offers.get(engagementId));
    }

    /** A consistent snapshot of the engagement's rows. */
    private List<HistoryEntry> rows(String engagementId) {
        ConcurrentMap<RowKey, HistoryEntry> rows = history.get(engagementId);
        return rows == null ? List.of() : List.copyOf(rows.values());
    }

    private record RowKey(Instant sourceUpdatedAt, EngagementEventType eventType) {}
}
