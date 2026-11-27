package com.caseware.updates.handler;

import static com.caseware.updates.model.EngagementEventType.ACCEPTED;
import static com.caseware.updates.model.EngagementEventType.ARCHIVED;
import static com.caseware.updates.model.EngagementEventType.CREATED;
import static com.caseware.updates.model.EngagementEventType.DECLINED;
import static com.caseware.updates.model.EngagementEventType.SYNCED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.PendingDecision;
import com.caseware.updates.model.PendingUpdate;
import com.caseware.updates.model.SummaryResult;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The pure offer rules, without any fake: state and latest version in, decision out. */
class OfferRulesTest {

    private static final Instant NOW = Instant.parse("2026-03-02T02:00:00Z");
    private static final SummaryResult READY = new SummaryResult.Ready("summary-key");
    private static final SummaryResult EMPTY_DIFF = new SummaryResult.EmptyDiff();
    private static final PendingDecision NO_OFFER = new PendingDecision.NoOffer();

    static Stream<Arguments> rules() {
        return Stream.of(
                arguments("declined v5, latest v8 → offer v4 → v8", state(DECLINED, 4, 5L), 8, READY, offer(4, 8)),
                arguments("declined v8, latest v8 → no offer", state(DECLINED, 4, 8L), 8, READY, NO_OFFER),
                arguments("empty diff → no offer, deleting any existing one", state(CREATED, 4, null), 8,
                        EMPTY_DIFF, NO_OFFER),
                arguments("archived → no offer", state(ARCHIVED, 4, null), 8, READY, NO_OFFER),
                arguments("accepted an older offer (v7) while v8 exists → offer v7 → v8", state(ACCEPTED, 7, 5L), 8,
                        READY, offer(7, 8)),
                arguments("already on the latest version → no offer", state(SYNCED, 8, null), 8, READY, NO_OFFER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    @DisplayName("5. decision rules")
    void decides(String rule, EngagementState state, long latest, SummaryResult summary, PendingDecision expected) {
        PendingDecision decision = OfferRules.candidate(Optional.of(state), OptionalLong.of(latest))
                .map(candidate -> OfferRules.decide(candidate, summary, NOW))
                .orElseGet(PendingDecision.NoOffer::new);

        assertThat(decision).isEqualTo(expected);
    }

    @Test
    @DisplayName("no candidate, so no summary call, without a state or a published version")
    void noCandidateWithoutStateOrPublishedVersion() {
        assertThat(OfferRules.candidate(Optional.empty(), OptionalLong.of(8))).isEmpty();
        assertThat(OfferRules.candidate(Optional.of(state(CREATED, 4, null)), OptionalLong.empty())).isEmpty();
    }

    private static EngagementState state(EngagementEventType type, long applied, Long maxDeclined) {
        HistoryEntry latest = new HistoryEntry(
                "eng-1", NOW.minusSeconds(60), type, "firm-1", "product-1", applied, maxDeclined, null, null);
        return new EngagementState(latest, maxDeclined);
    }

    private static PendingDecision offer(long from, long to) {
        return new PendingDecision.Offer(
                new PendingUpdate("eng-1", "firm-1", "product-1", from, to, "summary-key", NOW));
    }
}
