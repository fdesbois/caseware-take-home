package com.caseware.updates.port;

import com.caseware.updates.model.EngagementEventType;
import com.caseware.updates.model.EngagementState;
import com.caseware.updates.model.HistoryEntry;
import java.util.Optional;

/** The append-only {@code engagement_history} table of this region's database. */
public interface EngagementHistoryRepository {

    /**
     * Adds a row. Idempotent on {@code (engagementId, sourceUpdatedAt, eventType)}: a duplicate key is a no-op.
     *
     * @return {@code true} if the row was added, {@code false} if its key already existed
     */
    boolean append(HistoryEntry entry);

    /** Whether the engagement has any row: an event or an earlier sync already gave us its state. */
    boolean hasAnyEntry(String engagementId);

    /**
     * The latest row by {@code sourceUpdatedAt}, plus the max declined version over all rows; empty when the
     * engagement has no row. Rows with the same {@code sourceUpdatedAt} rank in {@link EngagementEventType} order.
     *
     * <p>Read by the publish fan-out (Flow B) to compute offers; this component only writes the rows it orders.
     */
    Optional<EngagementState> currentState(String engagementId);
}
