package com.caseware.updates.fake;

import static com.caseware.updates.model.EngagementEventType.ACCEPTED;
import static com.caseware.updates.model.EngagementEventType.CREATED;
import static com.caseware.updates.model.EngagementEventType.DECLINED;
import static com.caseware.updates.model.EngagementEventType.SYNCED;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.updates.model.DecisionBasis;
import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.PendingDecision;
import com.caseware.updates.model.PendingUpdate;
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
    void applyIfUnchangedWritesWhenNothingMoved() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0, CREATED, 4, null));

        assertThat(db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basisNow())).isTrue();
        assertThat(db.pendingUpdate(ENGAGEMENT)).hasValueSatisfying(o -> assertThat(o.toVersion()).isEqualTo(8));
    }

    @Test
    void applyIfUnchangedSkipsTheWriteOnceHistoryMoved() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0, CREATED, 4, null));
        DecisionBasis basis = basisNow();

        db.append(row(T0.plusSeconds(1), ACCEPTED, 8, null));

        assertThat(db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basis)).isFalse();
        assertThat(db.pendingUpdate(ENGAGEMENT)).isEmpty();
    }

    @Test
    void applyIfUnchangedSkipsTheWriteOnceAnOlderRowRaisesTheMaxDeclinedVersion() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0.plusSeconds(10), SYNCED, 4, null));
        DecisionBasis basis = basisNow();

        db.append(row(T0, DECLINED, 4, 8L)); // older than the latest row, so only the max declined version moves

        assertThat(db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basis)).isFalse();
    }

    @Test
    void applyIfUnchangedSkipsTheWriteOnceTheProductVersionMoved() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0, CREATED, 4, null));
        DecisionBasis basis = basisNow();

        db.publishVersion(PRODUCT, 9);

        assertThat(db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basis)).isFalse();
    }

    @Test
    void noOfferDeletesTheRow() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0, CREATED, 4, null));
        db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basisNow());

        assertThat(db.applyIfUnchanged(ENGAGEMENT, new PendingDecision.NoOffer(), basisNow())).isTrue();
        assertThat(db.pendingUpdate(ENGAGEMENT)).isEmpty();
    }

    @Test
    void anUnchangedOfferKeepsItsOfferedAtAndAChangedOneReplacesIt() {
        db.publishVersion(PRODUCT, 8);
        db.append(row(T0, CREATED, 4, null));
        db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0), basisNow());

        db.applyIfUnchanged(ENGAGEMENT, offer(4, 8, T0.plusSeconds(60)), basisNow());
        assertThat(db.pendingUpdate(ENGAGEMENT)).hasValueSatisfying(o -> assertThat(o.offeredAt()).isEqualTo(T0));

        db.publishVersion(PRODUCT, 9);
        db.applyIfUnchanged(ENGAGEMENT, offer(4, 9, T0.plusSeconds(120)), basisNow());
        assertThat(db.pendingUpdate(ENGAGEMENT)).hasValueSatisfying(o -> {
            assertThat(o.toVersion()).isEqualTo(9);
            assertThat(o.offeredAt()).isEqualTo(T0.plusSeconds(120));
        });
    }

    private DecisionBasis basisNow() {
        return new DecisionBasis(db.currentState(ENGAGEMENT), db.latestVersion(PRODUCT));
    }

    private static HistoryEntry row(Instant at, EngagementEventType type, long applied, Long declined) {
        return new HistoryEntry(ENGAGEMENT, at, type, "firm-1", PRODUCT, applied, declined, null, null);
    }

    private static PendingDecision offer(long from, long to, Instant offeredAt) {
        return new PendingDecision.Offer(new PendingUpdate(
                ENGAGEMENT, "firm-1", PRODUCT, from, to, "summaries/" + PRODUCT + "/" + from + "-" + to, offeredAt));
    }
}
