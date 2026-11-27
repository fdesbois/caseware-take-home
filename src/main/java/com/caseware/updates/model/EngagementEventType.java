package com.caseware.updates.model;

/**
 * Event type of an {@code engagement_history} row.
 *
 * <p>Declaration order breaks ties between rows with the same {@code sourceUpdatedAt}: a SYNCED snapshot loses
 * to any engagement-service event, and ARCHIVED wins.
 */
public enum EngagementEventType {
    SYNCED,
    CREATED,
    ACCEPTED,
    DECLINED,
    ARCHIVED
}
