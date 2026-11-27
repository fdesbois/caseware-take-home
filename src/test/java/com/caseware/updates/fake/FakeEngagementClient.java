package com.caseware.updates.fake;

import com.caseware.updates.model.EngagementSnapshot;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;
import com.caseware.updates.port.EngagementClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * engagement-service stand-in. Counts calls and the peak number running at once. Tests can script a result per
 * engagement and hold loads inside the call on a latch or barrier. A loaded snapshot is stamped with this fake's
 * own clock, read when the load starts: engagement-service's clock, separate from the pod's.
 */
public final class FakeEngagementClient implements EngagementClient {

    /** By default every engagement is on v4 of {@link #PRODUCT}. */
    public static final String FIRM = "firm-1";

    public static final String PRODUCT = "product-1";

    private static final long DEFAULT_VERSION = 4;

    /** Runs inside {@link #load}, while the caller holds its permit. */
    @FunctionalInterface
    public interface DuringLoad {
        void run(String engagementId) throws Exception;
    }

    private final Clock engagementServiceClock;
    private final Map<String, Function<Instant, LoadResult>> results = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private volatile DuringLoad duringLoad = engagementId -> {};

    public FakeEngagementClient(Clock engagementServiceClock) {
        this.engagementServiceClock = engagementServiceClock;
    }

    /** Scripts a failure (or any fixed result) for one engagement. */
    public void respond(String engagementId, LoadResult result) {
        results.put(engagementId, readAt -> result);
    }

    /** Scripts a successful load of {@link #PRODUCT} on the given version. */
    public void respondOnVersion(String engagementId, long appliedVersion) {
        results.put(engagementId, readAt -> loaded(appliedVersion, readAt));
    }

    public void duringLoad(DuringLoad hook) {
        this.duringLoad = hook;
    }

    @Override
    public LoadResult load(String engagementId, Region region) {
        Instant readAt = engagementServiceClock.instant();
        calls.incrementAndGet();
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            duringLoad.run(engagementId);
            return results.getOrDefault(engagementId, at -> loaded(DEFAULT_VERSION, at)).apply(readAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted inside load", e);
        } catch (Exception e) {
            throw new IllegalStateException("load hook failed", e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public int calls() {
        return calls.get();
    }

    public int peakInFlight() {
        return peakInFlight.get();
    }

    private static LoadResult loaded(long appliedVersion, Instant readAt) {
        return new LoadResult.Loaded(new EngagementSnapshot(FIRM, PRODUCT, appliedVersion, null, readAt));
    }
}
