package com.caseware.updates.adapter;

import com.caseware.updates.handler.EngagementMessageHandler;
import com.caseware.updates.model.EngagementStateUpdateEvent;
import com.caseware.updates.model.SyncRequest;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/**
 * The two SQS listeners, one per queue, both handing their message to the same handler. Returning acknowledges
 * (deletes) the message; throwing leaves it to be redelivered, and the queue's DLQ takes over after
 * {@code maxReceiveCount}. Both hide a received message for longer than a load or a summary can take.
 */
@Component
class EngagementQueueListeners {

    private final EngagementMessageHandler handler;

    EngagementQueueListeners(EngagementMessageHandler handler) {
        this.handler = handler;
    }

    /** Decisions and lifecycle events, on their own queue so they never wait behind 1-minute loads. */
    @SqsListener(
            id = "engagement-events",
            value = "${update-service.events-queue}",
            maxConcurrentMessages = "${update-service.events-max-in-flight}",
            messageVisibilitySeconds = "${update-service.visibility-timeout-seconds}")
    void onEngagementEvent(EngagementStateUpdateEvent event) throws InterruptedException {
        handler.handle(event);
    }

    /**
     * Nightly sync requests: at most one message in flight per load permit. The poll batch follows the same value
     * because Spring Cloud AWS rejects a batch larger than the concurrency.
     */
    @SqsListener(
            id = "engagement-sync",
            value = "${update-service.sync-queue}",
            maxConcurrentMessages = "${update-service.permits-per-pod}",
            maxMessagesPerPoll = "${update-service.permits-per-pod}",
            messageVisibilitySeconds = "${update-service.visibility-timeout-seconds}")
    void onSyncRequest(SyncRequest request) throws InterruptedException {
        handler.handle(request);
    }
}
