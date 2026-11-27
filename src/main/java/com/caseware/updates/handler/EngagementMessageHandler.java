package com.caseware.updates.handler;

import com.caseware.updates.model.EngagementMessage;
import com.caseware.updates.model.EngagementSnapshot;
import com.caseware.updates.model.EngagementStateUpdateEvent;
import com.caseware.updates.model.HistoryEntry;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;
import com.caseware.updates.model.SyncRequest;
import com.caseware.updates.port.DeadLetterPublisher;
import com.caseware.updates.port.EngagementClient;
import com.caseware.updates.port.EngagementHistoryRepository;
import com.caseware.updates.port.PendingUpdateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles both queues' messages for one region and keeps {@code engagement_history} complete. An event appends its
 * row, and a decision or an archive removes the offer it made obsolete. A sync request loads an engagement that has
 * no history yet. Offers themselves are created only by the publish fan-out (Flow B).
 *
 * <p>Thread-safe: listeners call {@link #handle} concurrently, including for the same engagement.
 */
public final class EngagementMessageHandler {

    /** Counter, tagged {@code outcome}: loaded, skipped, transient, permanent, error. */
    public static final String OUTCOMES = "updates.handler.outcomes";

    /** Gauge: loads currently holding a permit. */
    public static final String LOADS_IN_FLIGHT = "updates.handler.loads.in.flight";

    private static final Logger log = LoggerFactory.getLogger(EngagementMessageHandler.class);

    private final Region region;
    private final Semaphore loadPermits;
    private final EngagementHistoryRepository history;
    private final PendingUpdateRepository pendingUpdates;
    private final EngagementClient engagements;
    private final DeadLetterPublisher deadLetters;
    private final Counter loaded;
    private final Counter skipped;
    private final Counter transientFailures;
    private final Counter permanentFailures;
    private final Counter errors;

    public EngagementMessageHandler(
            Region region,
            int permitsPerPod,
            EngagementHistoryRepository history,
            PendingUpdateRepository pendingUpdates,
            EngagementClient engagements,
            DeadLetterPublisher deadLetters,
            MeterRegistry meters) {
        if (permitsPerPod <= 0) {
            throw new IllegalArgumentException("permitsPerPod must be > 0, was " + permitsPerPod);
        }
        this.region = Objects.requireNonNull(region, "region");
        this.loadPermits = new Semaphore(permitsPerPod, true);
        this.history = history;
        this.pendingUpdates = pendingUpdates;
        this.engagements = engagements;
        this.deadLetters = deadLetters;
        this.loaded = outcomeCounter(meters, "loaded");
        this.skipped = outcomeCounter(meters, "skipped");
        this.transientFailures = outcomeCounter(meters, "transient");
        this.permanentFailures = outcomeCounter(meters, "permanent");
        this.errors = outcomeCounter(meters, "error");
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
        } else {
            dispatch(message);
        }
    }

    /** Counts any unexpected failure (an adapter bug, the database down) before SQS redelivers the message. */
    private void dispatch(EngagementMessage message) throws InterruptedException {
        try {
            switch (message) {
                case EngagementStateUpdateEvent event -> onEvent(event);
                case SyncRequest request -> onSyncRequest(request);
            }
        } catch (TransientLoadException e) {
            throw e; // already counted as transient
        } catch (RuntimeException e) {
            errors.increment();
            log.warn("outcome=error exception={} engagementId={} region={}",
                    e.getClass().getSimpleName(), message.engagementId(), region);
            throw e;
        }
    }

    private void onEvent(EngagementStateUpdateEvent event) {
        String engagementId = event.engagementId();
        String outcome = history.append(HistoryEntry.of(event)) ? "appended" : "duplicate";
        // Each removal is conditional, so a redelivered decision never erases an offer built after it.
        switch (event.type()) {
            // The accepted version is the new starting point: only an offer built from an older one is obsolete.
            case ACCEPTED -> pendingUpdates.removeOfferStartingBefore(engagementId, event.appliedVersion());
            // A decline only covers the version the user saw: a newer offer published meanwhile stays.
            case DECLINED ->
                pendingUpdates.removeOfferUpTo(engagementId, Objects.requireNonNull(event.declinedVersion()));
            // An archived engagement gets no offer.
            case ARCHIVED -> pendingUpdates.removeOffer(engagementId);
            case CREATED, SYNCED -> {}
        }
        log.info("outcome={} engagementId={} region={}", outcome, engagementId, region);
    }

    private void onSyncRequest(SyncRequest request) throws InterruptedException {
        String engagementId = request.engagementId();
        // The cron only queues engagements without history: a row means an event or an earlier sync got here first.
        if (history.hasAnyEntry(engagementId)) {
            skipped.increment();
            log.info("outcome=skipped engagementId={} region={}", engagementId, region);
        } else {
            switch (loadWithPermit(request)) {
                case LoadResult.Loaded(EngagementSnapshot snapshot) -> {
                    history.append(HistoryEntry.synced(engagementId, snapshot));
                    loaded.increment();
                    log.info("outcome=loaded engagementId={} region={}", engagementId, region);
                }
                case LoadResult.Transient(String code) -> {
                    transientFailures.increment();
                    log.warn("outcome=transient code={} engagementId={} region={}", code, engagementId, region);
                    throw new TransientLoadException(engagementId, code);
                }
                case LoadResult.Permanent(String code) -> deadLetter(request, code);
            }
        }
    }

    /** The permit covers the 1-minute load and nothing else. */
    private LoadResult loadWithPermit(SyncRequest request) throws InterruptedException {
        loadPermits.acquire();
        try {
            return engagements.load(request.engagementId(), request.region());
        } finally {
            loadPermits.release();
        }
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
}
