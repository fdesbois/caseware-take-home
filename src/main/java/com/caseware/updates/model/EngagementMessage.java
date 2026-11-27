package com.caseware.updates.model;

/** A message from either queue. Both kinds name the engagement and the region its data belongs to. */
public sealed interface EngagementMessage permits EngagementStateUpdateEvent, SyncRequest {

    String engagementId();

    Region region();
}
