package com.caseware.updates.fake;

import com.caseware.updates.model.EngagementSnapshot;
import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;
import com.caseware.updates.port.EngagementClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * engagement-service stand-in. Counts calls and the peak number running at once. Tests can script a result per
 * engagement and hold loads inside the call on a latch or barrier.
 */
public final class FakeEngagementClient implements EngagementClient {

    /** By default every engagement is on v4 of {@link #PRODUCT}. */
    public static final String FIRM = "firm-1";

    public static final String PRODUCT = "product-1";

    public static final LoadResult ON_V4 = new LoadResult.Loaded(new EngagementSnapshot(FIRM, PRODUCT, 4, null));

    /** Runs inside {@link #load}, while the caller holds its permit. */
    @FunctionalInterface
    public interface DuringLoad {
        void run(String engagementId) throws Exception;
    }

    private final Map<String, LoadResult> results = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private volatile DuringLoad duringLoad = engagementId -> {};

    public void respond(String engagementId, LoadResult result) {
        results.put(engagementId, result);
    }

    public void duringLoad(DuringLoad hook) {
        this.duringLoad = hook;
    }

    @Override
    public LoadResult load(String engagementId, Region region) {
        calls.incrementAndGet();
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            duringLoad.run(engagementId);
            return results.getOrDefault(engagementId, ON_V4);
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
}
