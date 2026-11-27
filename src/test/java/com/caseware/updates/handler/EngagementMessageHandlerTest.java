package com.caseware.updates.handler;

import static com.caseware.updates.fake.FakeEngagementClient.FIRM;
import static com.caseware.updates.fake.FakeEngagementClient.PRODUCT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caseware.updates.fake.FakeEngagementClient;
import com.caseware.updates.fake.FakeSummaryClient;
import com.caseware.updates.fake.InMemoryUpdateDatabase;
import com.caseware.updates.fake.MutableClock;
import com.caseware.updates.fake.RecordingDeadLetterPublisher;
import com.caseware.updates.fake.RecordingDeadLetterPublisher.DeadLetter;
import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementMessage;
import com.caseware.updates.model.EngagementStateUpdateEvent;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;
import com.caseware.updates.model.SummaryResult;
import com.caseware.updates.model.SyncRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The handler against in-memory fakes: no Docker, no AWS, no sleeps. Concurrency is driven with latches and
 * barriers, time with a mutable clock. Test numbers follow the Part 2 test list.
 */
@Timeout(30)
class EngagementMessageHandlerTest {

    private static final Region REGION = Region.EU;
    private static final Instant T0 = Instant.parse("2026-03-02T02:00:00Z");

    /** This pod's clock: the handler's. */
    private final MutableClock clock = new MutableClock(T0);
    /** engagement-service's clock: stamps events and loaded snapshots. */
    private final MutableClock engagementServiceClock = new MutableClock(T0);
    private final InMemoryUpdateDatabase db = new InMemoryUpdateDatabase();
    private final FakeEngagementClient engagements = new FakeEngagementClient(engagementServiceClock);
    private final FakeSummaryClient summaries = new FakeSummaryClient();
    private final RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    @DisplayName("1. peak concurrent loads never exceed the permits (500 requests, 20 permits)")
    void peakConcurrentLoadsNeverExceedPermits() {
        int permits = 20;
        EngagementMessageHandler handler = handler(permits);
        // Loads pass only in full waves of 20: a 21st concurrent load would raise the peak, a leaked permit would
        // leave a wave short and time it out.
        CyclicBarrier fullWave = new CyclicBarrier(permits);
        engagements.duringLoad(id -> fullWave.await(10, SECONDS));

        List<Future<Void>> results = handleConcurrently(
                handler, IntStream.range(0, 500).mapToObj(i -> sync("eng-" + i)).toList());

        results.forEach(EngagementMessageHandlerTest::assertSucceeded);
        assertThat(engagements.peakInFlight()).isEqualTo(permits);
        assertThat(engagements.calls()).isEqualTo(500);
        assertThat(outcome("loaded")).isEqualTo(500);
        assertThat(loadsInFlight()).isZero();
    }

    @Test
    @DisplayName("2. a duplicate SyncRequest after success does not load again, and the offer is still there")
    void duplicateSyncRequestDoesNotLoadAgain() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.publishVersion(PRODUCT, 8);

        handler.handle(sync("eng-1"));
        clock.advance(Duration.ofMinutes(5));
        handler.handle(sync("eng-1"));

        assertThat(engagements.calls()).isEqualTo(1);
        assertThat(outcome("skipped")).isEqualTo(1);
        assertThat(db.pendingUpdate("eng-1")).hasValueSatisfying(offer -> {
            assertThat(offer.fromVersion()).isEqualTo(4);
            assertThat(offer.toVersion()).isEqualTo(8);
            assertThat(offer.offeredAt()).isEqualTo(T0); // re-evaluating an unchanged offer keeps when it was made
        });
    }

    @Test
    @DisplayName("3. transient and permanent failures stay isolated and leak no permit")
    void failuresAreIsolated() {
        EngagementMessageHandler handler = handler(4);
        db.publishVersion(PRODUCT, 8);
        engagements.respond("eng-transient", new LoadResult.Transient("503"));
        engagements.respond("eng-permanent", new LoadResult.Permanent("404"));
        engagements.duringLoad(id -> {
            if (id.equals("eng-crash")) {
                throw new IllegalStateException("connection reset"); // an adapter bug, not a LoadResult
            }
        });
        List<String> healthy = IntStream.range(0, 10).mapToObj(i -> "eng-" + i).toList();

        List<Future<Void>> results = handleConcurrently(handler, Stream.concat(
                        Stream.of("eng-transient", "eng-permanent", "eng-crash"), healthy.stream())
                .map(EngagementMessageHandlerTest::sync)
                .toList());

        assertThat(results.get(0).exceptionNow())
                .isInstanceOfSatisfying(TransientLoadException.class, e -> assertThat(e.code()).isEqualTo("503"));
        assertSucceeded(results.get(1));
        assertThat(results.get(2).exceptionNow()).hasRootCauseMessage("connection reset");
        results.subList(3, results.size()).forEach(EngagementMessageHandlerTest::assertSucceeded);
        assertThat(deadLetters.published()).containsExactly(new DeadLetter(sync("eng-permanent"), "404"));
        healthy.forEach(id -> assertThat(db.pendingUpdate(id)).isPresent());
        // Nothing recorded for the failures, so a redelivered transient one loads again.
        assertThat(db.hasAnyEntry("eng-transient")).isFalse();
        assertThat(db.hasAnyEntry("eng-permanent")).isFalse();
        assertThat(db.hasAnyEntry("eng-crash")).isFalse();
        assertThat(outcome("transient")).isEqualTo(1);
        assertThat(outcome("permanent")).isEqualTo(1);
        assertThat(outcome("loaded")).isEqualTo(10);
        assertThat(outcome("error")).isEqualTo(1); // the adapter crash
        assertThat(loadsInFlight()).isZero();
    }

    @Test
    @DisplayName("4. out of order: a newer ACCEPTED beats an older SYNCED, even with the pod clock 10 min ahead")
    void newerEventWinsOverOlderSync() throws Exception {
        // The SYNCED row must be ordered on engagement-service's clock: with the pod's, this pod would win.
        clock.advance(Duration.ofMinutes(10));
        EngagementMessageHandler handler = handler(20);
        db.publishVersion(PRODUCT, 8);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finishLoad = new CountDownLatch(1);
        engagements.duringLoad(id -> {
            loading.countDown();
            await(finishLoad);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // engagement-service reads the engagement on v4 at T0 (its clock)...
            Future<Void> sync = executor.submit(() -> handle(handler, sync("eng-1")));
            await(loading);
            // ...while the user accepts v8 at T0 + 30 s, and the load takes its minute.
            handler.handle(accepted("eng-1", 8, T0.plusSeconds(30)));
            engagementServiceClock.advance(Duration.ofMinutes(1));
            finishLoad.countDown();
            sync.get(10, SECONDS); // appends SYNCED stamped T0 by engagement-service, older than the decision
        }

        assertThat(db.currentState("eng-1")).hasValueSatisfying(state -> {
            assertThat(state.latest().eventType()).isEqualTo(EngagementEventType.ACCEPTED);
            assertThat(state.latest().appliedVersion()).isEqualTo(8);
        });
        assertThat(db.pendingUpdate("eng-1")).isEmpty(); // the stale v4 snapshot would have offered v4 → v8
        assertThat(summaries.calls()).isZero();
    }

    @Test
    @DisplayName("5. an empty diff deletes the existing offer")
    void emptyDiffDeletesExistingOffer() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.publishVersion(PRODUCT, 7);
        handler.handle(created("eng-1", 6, T0));
        assertThat(db.pendingUpdate("eng-1")).hasValueSatisfying(offer -> assertThat(offer.toVersion()).isEqualTo(7));

        // v7 is withdrawn: v8 restores v6's content, so v6 → v8 has nothing to show.
        db.publishVersion(PRODUCT, 8);
        summaries.answer((product, from, to) -> new SummaryResult.EmptyDiff());
        handler.handle(created("eng-1", 6, T0)); // a redelivery re-evaluates

        assertThat(db.pendingUpdate("eng-1")).isEmpty();
    }

    @Test
    @DisplayName("6. a stale evaluation does not overwrite the newer result")
    void staleEvaluationDoesNotOverwriteNewerResult() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.publishVersion(PRODUCT, 8);
        CountDownLatch summarizing = new CountDownLatch(1);
        CountDownLatch finishSummary = new CountDownLatch(1);
        summaries.answer((product, from, to) -> {
            summarizing.countDown();
            await(finishSummary);
            return FakeSummaryClient.ready(product, from, to);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // CREATED on v4: its evaluation waits in summarize for v4 → v8...
            Future<Void> created = executor.submit(() -> handle(handler, created("eng-1", 4, T0)));
            await(summarizing);
            // ...while ACCEPTED v8 completes: nothing to offer.
            handler.handle(accepted("eng-1", 8, T0.plusSeconds(60)));
            finishSummary.countDown();
            created.get(10, SECONDS);
        }

        assertThat(db.pendingUpdate("eng-1")).isEmpty();
        assertThat(outcome("stale_write_skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("7. summarize fails after the SYNCED row: the redelivery skips the load and creates the offer")
    void redeliveryAfterFailedSummarySkipsTheLoad() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.publishVersion(PRODUCT, 8);
        summaries.answer((product, from, to) -> {
            throw new IllegalStateException("summary-service unavailable");
        });

        assertThatThrownBy(() -> handler.handle(sync("eng-1"))).hasMessage("summary-service unavailable");
        assertThat(outcome("error")).isEqualTo(1);
        assertThat(db.hasAnyEntry("eng-1")).isTrue();
        assertThat(db.pendingUpdate("eng-1")).isEmpty();

        summaries.answer(FakeSummaryClient::ready);
        clock.advance(Duration.ofMinutes(5));
        handler.handle(sync("eng-1")); // SQS redelivers

        assertThat(engagements.calls()).isEqualTo(1);
        assertThat(db.pendingUpdate("eng-1")).hasValueSatisfying(offer -> {
            assertThat(offer.fromVersion()).isEqualTo(4);
            assertThat(offer.toVersion()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("8. a message for another region goes to the DLQ: no load, no write")
    void regionMismatchGoesToTheDeadLetterQueue() throws Exception {
        EngagementMessageHandler handler = handler(20);
        SyncRequest canadianSync = new SyncRequest("eng-1", Region.CA);
        EngagementStateUpdateEvent usEvent = new EngagementStateUpdateEvent(
                "eng-2", FIRM, PRODUCT, Region.US, EngagementEventType.CREATED, 4, null, null, null, T0);

        handler.handle(canadianSync);
        handler.handle(usEvent);

        assertThat(deadLetters.published())
                .containsExactly(
                        new DeadLetter(canadianSync, "region_mismatch"),
                        new DeadLetter(usEvent, "region_mismatch"));
        assertThat(engagements.calls()).isZero();
        assertThat(db.hasAnyEntry("eng-1")).isFalse();
        assertThat(db.hasAnyEntry("eng-2")).isFalse();
        assertThat(outcome("permanent")).isEqualTo(2);
    }

    @Test
    @DisplayName("9. while one evaluation is blocked in summarize, other loads can use every permit")
    void summarizeHoldsNoPermit() throws Exception {
        int permits = 4;
        EngagementMessageHandler handler = handler(permits);
        db.publishVersion(PRODUCT, 8);
        CountDownLatch summarizing = new CountDownLatch(1);
        CountDownLatch finishSummary = new CountDownLatch(1);
        summaries.answer((product, from, to) -> {
            summarizing.countDown();
            await(finishSummary);
            return FakeSummaryClient.ready(product, from, to);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> slow = executor.submit(() -> handle(handler, sync("eng-slow")));
            await(summarizing); // eng-slow is loaded and now waits in summarize

            // The others are already on v8, so they need no summary. The barrier opens only if all of their loads
            // run at the same time, which needs every permit.
            CyclicBarrier allPermits = new CyclicBarrier(permits);
            engagements.duringLoad(id -> allPermits.await(10, SECONDS));
            List<Future<Void>> others = IntStream.range(0, permits)
                    .mapToObj(i -> {
                        engagements.respondOnVersion("eng-" + i, 8);
                        return executor.submit(() -> handle(handler, sync("eng-" + i)));
                    })
                    .toList();
            for (Future<Void> other : others) {
                other.get(10, SECONDS);
            }

            assertThat(slow).isNotDone();
            finishSummary.countDown();
            slow.get(10, SECONDS);
        }

        assertThat(engagements.peakInFlight()).isEqualTo(permits);
        assertThat(db.pendingUpdate("eng-slow")).isPresent();
        assertThat(loadsInFlight()).isZero();
    }

    private EngagementMessageHandler handler(int permits) {
        return new EngagementMessageHandler(
                REGION, permits, db, db, db, engagements, summaries, deadLetters, clock, meters);
    }

    /** Handles each message on its own virtual thread, all at once, and waits until every one has finished. */
    private static List<Future<Void>> handleConcurrently(
            EngagementMessageHandler handler, List<? extends EngagementMessage> messages) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return messages.stream()
                    .map(message -> executor.submit(() -> handle(handler, message)))
                    .toList();
        }
    }

    private static Void handle(EngagementMessageHandler handler, EngagementMessage message)
            throws InterruptedException {
        handler.handle(message);
        return null;
    }

    private static void assertSucceeded(Future<?> result) {
        if (result.state() == Future.State.FAILED) {
            throw new AssertionError("handle failed", result.exceptionNow());
        }
        assertThat(result.state()).isEqualTo(Future.State.SUCCESS);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, SECONDS)) {
            throw new AssertionError("timed out waiting on a latch");
        }
    }

    private double outcome(String outcome) {
        return meters.get(EngagementMessageHandler.OUTCOMES)
                .tag("outcome", outcome)
                .counter()
                .count();
    }

    private double loadsInFlight() {
        return meters.get(EngagementMessageHandler.LOADS_IN_FLIGHT).gauge().value();
    }

    private static SyncRequest sync(String engagementId) {
        return new SyncRequest(engagementId, REGION);
    }

    private static EngagementStateUpdateEvent created(String engagementId, long appliedVersion, Instant at) {
        return new EngagementStateUpdateEvent(
                engagementId, FIRM, PRODUCT, REGION, EngagementEventType.CREATED, appliedVersion, null, null, null, at);
    }

    private static EngagementStateUpdateEvent accepted(String engagementId, long appliedVersion, Instant at) {
        return new EngagementStateUpdateEvent(
                engagementId,
                FIRM,
                PRODUCT,
                REGION,
                EngagementEventType.ACCEPTED,
                appliedVersion,
                null,
                "summaries/" + PRODUCT + "/4-" + appliedVersion,
                "user-1",
                at);
    }
}
