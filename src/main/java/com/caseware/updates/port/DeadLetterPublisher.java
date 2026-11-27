package com.caseware.updates.port;

import com.caseware.updates.model.EngagementMessage;

/** Sends a message that retrying cannot fix to the dead-letter queue of the queue it came from. */
public interface DeadLetterPublisher {

    /**
     * @param code an opaque reason such as {@code "404"} or {@code "region_mismatch"}, never message content
     */
    void publish(EngagementMessage message, String code);
}
