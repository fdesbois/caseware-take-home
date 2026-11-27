package com.caseware.updates.handler;

import static com.caseware.updates.fake.FakeEngagementClient.FIRM;
import static com.caseware.updates.fake.FakeEngagementClient.PRODUCT;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.updates.fake.FakeEngagementClient;
import com.caseware.updates.fake.InMemoryUpdateDatabase;
import com.caseware.updates.fake.InMemoryUpdateDatabase.Offer;
import com.caseware.updates.fake.MutableClock;
import com.caseware.updates.fake.RecordingDeadLetterPublisher;
import com.caseware.updates.fake.RecordingDeadLetterPublisher.DeadLetter;
import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementMessage;
import com.caseware.updates.model.EngagementStateUpdateEvent;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The handler against in-memory fakes: no Docker, no AWS, no sleeps. Concurrency is driven with latches and
 * barriers, engagement-service's time with a mutable clock.
 */
@Timeout(30)
class EngagementMessageHandlerTest {

    private static final Region REGION = Region.EU;
    private static final Instant T0 = Instant.parse("2026-03-02T02:00:00Z");

    /** engagement-service's clock: stamps events and loaded snapshots. */
    private final MutableClock engagementServiceClock = new MutableClock(T0);
    private final InMemoryUpdateDatabase db = new InMemoryUpdateDatabase();
    private final FakeEngagementClient engagements = new FakeEngagementClient(engagementServiceClock);
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
    @DisplayName("2. a duplicate SyncRequest after success does not load again")
    void duplicateSyncRequestDoesNotLoadAgain() throws Exception {
        EngagementMessageHandler handler = handler(20);

        handler.handle(sync("eng-1"));
        handler.handle(sync("eng-1"));

        assertThat(engagements.calls()).isEqualTo(1);
        assertThat(outcome("loaded")).isEqualTo(1);
        assertThat(outcome("skipped")).isEqualTo(1);
    }

    @Test
    @DisplayName("3. transient and permanent failures stay isolated and leak no permit")
    void failuresAreIsolated() {
        EngagementMessageHandler handler = handler(4);
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
        healthy.forEach(id -> assertThat(db.hasAnyEntry(id)).isTrue());
        // Nothing recorded for the failures, so a redelivered transient one loads again.
        assertThat(db.hasAnyEntry("eng-transient")).isFalse();
        assertThat(db.hasAnyEntry("eng-permanent")).isFalse();
        assertThat(db.hasAnyEntry("eng-crash")).isFalse();
        assertThat(outcome("transient")).isEqualTo(1);
        assertThat(outcome("permanent")).isEqualTo(1);
        assertThat(outcome("error")).isEqualTo(1); // the adapter crash
        assertThat(outcome("loaded")).isEqualTo(10);
        assertThat(loadsInFlight()).isZero();
    }

    @Test
    @DisplayName("4. out of order: a decision made during the load beats the SYNCED snapshot")
    void decisionDuringTheLoadBeatsTheSnapshot() throws Exception {
        EngagementMessageHandler handler = handler(20);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch finishLoad = new CountDownLatch(1);
        engagements.duringLoad(id -> {
            loading.countDown();
            await(finishLoad);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // engagement-service reads the engagement on v4 at T0, by its own clock...
            Future<Void> sync = executor.submit(() -> handle(handler, sync("eng-1")));
            await(loading);
            // ...while the user accepts v8 at T0 + 30 s, and the load takes its minute.
            handler.handle(event("eng-1", EngagementEventType.ACCEPTED, 8, null, T0.plusSeconds(30)));
            engagementServiceClock.advance(Duration.ofMinutes(1));
            finishLoad.countDown();
            sync.get(10, SECONDS); // appends SYNCED stamped T0 by engagement-service, older than the decision
        }

        assertThat(db.currentState("eng-1")).hasValueSatisfying(state -> {
            assertThat(state.latest().eventType()).isEqualTo(EngagementEventType.ACCEPTED);
            assertThat(state.latest().appliedVersion()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("5. a decision or an archive removes the offer it made obsolete, and only that one")
    void decisionsRemoveObsoleteOffers() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.putOffer("eng-created", 4, 8);
        db.putOffer("eng-accepted", 4, 8);
        db.putOffer("eng-declined-older", 4, 8);
        db.putOffer("eng-declined", 4, 8);
        db.putOffer("eng-archived", 4, 8);

        handler.handle(event("eng-created", EngagementEventType.CREATED, 4, null, T0));
        handler.handle(event("eng-accepted", EngagementEventType.ACCEPTED, 6, null, T0));
        handler.handle(event("eng-declined-older", EngagementEventType.DECLINED, 4, 7L, T0));
        handler.handle(event("eng-declined", EngagementEventType.DECLINED, 4, 8L, T0));
        handler.handle(event("eng-archived", EngagementEventType.ARCHIVED, 4, null, T0));

        assertThat(db.offer("eng-created")).hasValue(new Offer(4, 8));
        assertThat(db.offer("eng-accepted")).isEmpty(); // the next publish offers from v6
        // The user declined v7 while v8 was being published: the v8 offer they have not seen stays.
        assertThat(db.offer("eng-declined-older")).hasValue(new Offer(4, 8));
        assertThat(db.offer("eng-declined")).isEmpty();
        assertThat(db.offer("eng-archived")).isEmpty();
    }

    @Test
    @DisplayName("6. a redelivered acceptance does not erase the offer a later publish built from it")
    void redeliveredAcceptanceKeepsTheNewerOffer() throws Exception {
        EngagementMessageHandler handler = handler(20);
        EngagementStateUpdateEvent acceptedV6 = event("eng-1", EngagementEventType.ACCEPTED, 6, null, T0);
        db.putOffer("eng-1", 4, 6);

        handler.handle(acceptedV6);
        assertThat(db.offer("eng-1")).isEmpty();

        db.putOffer("eng-1", 6, 8); // the next publish offers v6 → v8
        handler.handle(acceptedV6); // SQS delivers the acceptance again

        assertThat(db.offer("eng-1")).hasValue(new Offer(6, 8));
    }

    @Test
    @DisplayName("7. a message for another region goes to the DLQ: no load, no write")
    void regionMismatchGoesToTheDeadLetterQueue() throws Exception {
        EngagementMessageHandler handler = handler(20);
        db.putOffer("eng-2", 4, 8);
        SyncRequest canadianSync = new SyncRequest("eng-1", Region.CA);
        EngagementStateUpdateEvent usEvent = new EngagementStateUpdateEvent(
                "eng-2", FIRM, PRODUCT, Region.US, EngagementEventType.ACCEPTED, 8, null, null, null, T0);

        handler.handle(canadianSync);
        handler.handle(usEvent);

        assertThat(deadLetters.published())
                .containsExactly(
                        new DeadLetter(canadianSync, "region_mismatch"),
                        new DeadLetter(usEvent, "region_mismatch"));
        assertThat(engagements.calls()).isZero();
        assertThat(db.hasAnyEntry("eng-1")).isFalse();
        assertThat(db.hasAnyEntry("eng-2")).isFalse();
        assertThat(db.offer("eng-2")).hasValue(new Offer(4, 8));
        assertThat(outcome("permanent")).isEqualTo(2);
    }

    private EngagementMessageHandler handler(int permits) {
        return new EngagementMessageHandler(REGION, permits, db, db, engagements, deadLetters, meters);
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

    private static EngagementStateUpdateEvent event(
            String engagementId,
            EngagementEventType type,
            long appliedVersion,
            @Nullable Long declinedVersion,
            Instant at) {
        boolean decision = type == EngagementEventType.ACCEPTED || type == EngagementEventType.DECLINED;
        return new EngagementStateUpdateEvent(
                engagementId,
                FIRM,
                PRODUCT,
                REGION,
                type,
                appliedVersion,
                declinedVersion,
                decision ? "summaries/" + PRODUCT + "/4-8" : null,
                decision ? "user-1" : null,
                at);
    }
}
