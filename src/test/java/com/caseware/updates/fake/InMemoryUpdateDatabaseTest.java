package com.caseware.updates.fake;

import static com.caseware.updates.model.EngagementEventType.ACCEPTED;
import static com.caseware.updates.model.EngagementEventType.CREATED;
import static com.caseware.updates.model.EngagementEventType.DECLINED;
import static com.caseware.updates.model.EngagementEventType.SYNCED;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.HistoryEntry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The repository contracts, pinned on the in-memory reference implementation. A SQL Server adapter has to pass the
 * same cases.
 */
class InMemoryUpdateDatabaseTest {

    private static final Instant T0 = Instant.parse("2026-03-02T02:00:00Z");
    private static final String ENGAGEMENT = "eng-1";
    private static final String PRODUCT = "product-1";

    private final InMemoryUpdateDatabase db = new InMemoryUpdateDatabase();

    @Test
    void appendIsIdempotentOnTheRowKey() {
        assertThat(db.append(row(T0, CREATED, 4, null))).isTrue();
        assertThat(db.append(row(T0, CREATED, 5, null))).isFalse();

        assertThat(db.currentState(ENGAGEMENT)).hasValueSatisfying(
                state -> assertThat(state.latest().appliedVersion()).isEqualTo(4));
    }

    @Test
    void hasAnyEntryReportsWhetherAnyRowExists() {
        assertThat(db.hasAnyEntry(ENGAGEMENT)).isFalse();
        db.append(row(T0, SYNCED, 4, null));
        assertThat(db.hasAnyEntry(ENGAGEMENT)).isTrue();
    }

    @Test
    void currentStateIsTheLatestRowPlusTheMaxDeclinedVersion() {
        db.append(row(T0.plusSeconds(20), ACCEPTED, 7, null));
        db.append(row(T0.plusSeconds(10), DECLINED, 4, 6L)); // older, arrives late
        db.append(row(T0, CREATED, 4, null));

        assertThat(db.currentState(ENGAGEMENT)).hasValueSatisfying(state -> {
            assertThat(state.latest().eventType()).isEqualTo(ACCEPTED);
            assertThat(state.maxDeclinedVersion()).isEqualTo(6L);
        });
    }

    @Test
    void anEventOutranksASyncAtTheSameInstant() {
        db.append(row(T0, ACCEPTED, 8, null));
        db.append(row(T0, SYNCED, 4, null));

        assertThat(db.currentState(ENGAGEMENT)).hasValueSatisfying(
                state -> assertThat(state.latest().eventType()).isEqualTo(ACCEPTED));
    }

    @Test
    void removeOfferDeletesAnyOffer() {
        db.putOffer(ENGAGEMENT, 4, 8);

        db.removeOffer(ENGAGEMENT);
        db.removeOffer(ENGAGEMENT); // idempotent

        assertThat(db.offer(ENGAGEMENT)).isEmpty();
    }

    @Test
    void removeOfferUpToDeletesOnlyAnOfferUpToThatVersion() {
        db.putOffer(ENGAGEMENT, 4, 8);

        db.removeOfferUpTo(ENGAGEMENT, 7); // a decline of v7: the newer v8 offer stays
        assertThat(db.offer(ENGAGEMENT)).hasValue(new InMemoryUpdateDatabase.Offer(4, 8));

        db.removeOfferUpTo(ENGAGEMENT, 8);
        assertThat(db.offer(ENGAGEMENT)).isEmpty();
    }

    @Test
    void removeOfferStartingBeforeKeepsAnOfferBuiltFromThatVersion() {
        db.putOffer(ENGAGEMENT, 6, 8);

        db.removeOfferStartingBefore(ENGAGEMENT, 6); // a replayed acceptance of v6
        assertThat(db.offer(ENGAGEMENT)).hasValue(new InMemoryUpdateDatabase.Offer(6, 8));

        db.removeOfferStartingBefore(ENGAGEMENT, 7);
        assertThat(db.offer(ENGAGEMENT)).isEmpty();
    }

    private static HistoryEntry row(Instant at, EngagementEventType type, long applied, Long declined) {
        return new HistoryEntry(ENGAGEMENT, at, type, "firm-1", PRODUCT, applied, declined, null, null);
    }
}
