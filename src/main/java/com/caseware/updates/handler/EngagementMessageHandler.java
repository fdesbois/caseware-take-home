package com.caseware.updates.handler;

import com.caseware.updates.model.DecisionBasis;
import com.caseware.updates.model.EngagementMessage;
import com.caseware.updates.model.EngagementSnapshot;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.EngagementStateUpdateEvent;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.PendingDecision;
import com.caseware.updates.model.Region;
import com.caseware.updates.model.SummaryResult;
import com.caseware.updates.model.SyncRequest;
import com.caseware.updates.port.DeadLetterPublisher;
import com.caseware.updates.port.EngagementClient;
import com.caseware.updates.port.EngagementHistoryRepository;
import com.caseware.updates.port.PendingUpdateRepository;
import com.caseware.updates.port.ProductVersionRepository;
import com.caseware.updates.port.SummaryClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles both queues' messages for one region. An event appends its history row; a sync request loads an
 * engagement that has no history yet. Either way the engagement's offer is then re-evaluated, and written only if
 * nothing it was computed from has moved.
 *
 * <p>Thread-safe: listeners call {@link #handle} concurrently, including for the same engagement.
 */
public final class EngagementMessageHandler {

    /** Counter, tagged {@code outcome}: loaded, skipped, transient, permanent, stale_write_skipped. */
    public static final String OUTCOMES = "updates.handler.outcomes";

    /** Gauge: loads currently holding a permit. */
    public static final String LOADS_IN_FLIGHT = "updates.handler.loads.in.flight";

    private static final Logger log = LoggerFactory.getLogger(EngagementMessageHandler.class);

    private final Region region;
    private final Semaphore loadPermits;
    private final EngagementHistoryRepository history;
    private final ProductVersionRepository products;
    private final PendingUpdateRepository pendingUpdates;
    private final EngagementClient engagements;
    private final SummaryClient summaries;
    private final DeadLetterPublisher deadLetters;
    private final Clock clock;
    private final Counter loaded;
    private final Counter skipped;
    private final Counter transientFailures;
    private final Counter permanentFailures;
    private final Counter staleWritesSkipped;

    public EngagementMessageHandler(
            Region region,
            int permitsPerPod,
            EngagementHistoryRepository history,
            ProductVersionRepository products,
            PendingUpdateRepository pendingUpdates,
            EngagementClient engagements,
            SummaryClient summaries,
            DeadLetterPublisher deadLetters,
            Clock clock,
            MeterRegistry meters) {
        if (permitsPerPod <= 0) {
            throw new IllegalArgumentException("permitsPerPod must be > 0, was " + permitsPerPod);
        }
        this.region = Objects.requireNonNull(region, "region");
        this.loadPermits = new Semaphore(permitsPerPod, true);
        this.history = history;
        this.products = products;
        this.pendingUpdates = pendingUpdates;
        this.engagements = engagements;
        this.summaries = summaries;
        this.deadLetters = deadLetters;
        this.clock = clock;
        this.loaded = outcomeCounter(meters, "loaded");
        this.skipped = outcomeCounter(meters, "skipped");
        this.transientFailures = outcomeCounter(meters, "transient");
        this.permanentFailures = outcomeCounter(meters, "permanent");
        this.staleWritesSkipped = outcomeCounter(meters, "stale_write_skipped");
        Gauge.builder(LOADS_IN_FLIGHT, loadPermits, permits -> permitsPerPod - permits.availablePermits())
                .description("Engagement loads holding a permit")
                .register(meters);
    }

    /**
     * Handles one message. Returns normally once the message is done with (handled, skipped or dead-lettered), so
     * the listener acknowledges it. Throws when SQS should deliver it again.
     */
    public void handle(EngagementMessage message) throws InterruptedException {
        if (message.region() != region) {
            deadLetter(message, "region_mismatch");
            return;
        }
        switch (message) {
            case EngagementStateUpdateEvent event -> onEvent(event);
            case SyncRequest request -> onSyncRequest(request);
        }
    }

    private void onEvent(EngagementStateUpdateEvent event) {
        String outcome = history.append(HistoryEntry.of(event)) ? "appended" : "duplicate";
        log.info("outcome={} engagementId={} region={}", outcome, event.engagementId(), region);
        evaluatePending(event.engagementId());
    }

    private void onSyncRequest(SyncRequest request) throws InterruptedException {
        String engagementId = request.engagementId();
        // The cron only queues engagements without history: a row means an event or an earlier sync got here first.
        if (history.hasAnyEntry(engagementId)) {
            skipped.increment();
            log.info("outcome=skipped engagementId={} region={}", engagementId, region);
        } else {
            TimedLoad load = loadWithPermit(request);
            switch (load.result()) {
                case LoadResult.Loaded(EngagementSnapshot snapshot) -> {
                    history.append(HistoryEntry.synced(engagementId, snapshot, load.startedAt()));
                    loaded.increment();
                    log.info("outcome=loaded engagementId={} region={}", engagementId, region);
                }
                case LoadResult.Transient(String code) -> {
                    transientFailures.increment();
                    log.warn("outcome=transient code={} engagementId={} region={}", code, engagementId, region);
                    throw new TransientLoadException(engagementId, code);
                }
                case LoadResult.Permanent(String code) -> {
                    deadLetter(request, code);
                    return;
                }
            }
        }
        // Even when the load was skipped: a redelivery after a failed summarize must still create the offer.
        evaluatePending(engagementId);
    }

    /** The permit covers the load only, never summarize, so a slow summary cannot idle the load capacity. */
    private TimedLoad loadWithPermit(SyncRequest request) throws InterruptedException {
        loadPermits.acquire();
        try {
            Instant startedAt = clock.instant();
            return new TimedLoad(startedAt, engagements.load(request.engagementId(), request.region()));
        } finally {
            loadPermits.release();
        }
    }

    /**
     * Recomputes the engagement's offer from its current state and its product's latest version, then writes it
     * with a compare-and-set. If either moved meanwhile the write is skipped: the change that moved it runs its own
     * evaluation.
     */
    private void evaluatePending(String engagementId) {
        Optional<EngagementState> state = history.currentState(engagementId);
        OptionalLong latestVersion = state.isPresent()
                ? products.latestVersion(state.get().latest().productId())
                : OptionalLong.empty();
        PendingDecision decision = OfferRules.candidate(state, latestVersion)
                .map(candidate -> OfferRules.decide(candidate, summarize(candidate), clock.instant()))
                .orElseGet(PendingDecision.NoOffer::new);
        if (!pendingUpdates.applyIfUnchanged(engagementId, decision, new DecisionBasis(state, latestVersion))) {
            staleWritesSkipped.increment();
            log.info("outcome=stale_write_skipped engagementId={} region={}", engagementId, region);
        }
    }

    private SummaryResult summarize(OfferRules.Candidate candidate) {
        return summaries.summarize(candidate.productId(), candidate.fromVersion(), candidate.toVersion());
    }

    private void deadLetter(EngagementMessage message, String code) {
        deadLetters.publish(message, code);
        permanentFailures.increment();
        log.warn("outcome=permanent code={} engagementId={} region={}", code, message.engagementId(), message.region());
    }

    private static Counter outcomeCounter(MeterRegistry meters, String outcome) {
        return Counter.builder(OUTCOMES)
                .description("Handler outcomes")
                .tag("outcome", outcome)
                .register(meters);
    }

    private record TimedLoad(Instant startedAt, LoadResult result) {}
}
