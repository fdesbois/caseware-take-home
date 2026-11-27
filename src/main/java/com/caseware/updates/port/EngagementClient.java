package com.caseware.updates.port;

import com.caseware.updates.model.LoadResult;
import com.caseware.updates.model.Region;

/**
 * engagement-service in this region, owned by another team: a load takes ~1 minute and the team allows 80
 * concurrent loads per region.
 */
public interface EngagementClient {

    /**
     * Loads the engagement to read its template state. A loaded snapshot carries engagement-service's own read time
     * ({@code readAt}), so it orders against the events without trusting this pod's clock. Implementations give up
     * with {@code Transient("timeout")} after the configured load timeout, which is shorter than the SQS visibility
     * timeout, so a load always ends before its message can be delivered again.
     */
    LoadResult load(String engagementId, Region region);
}
