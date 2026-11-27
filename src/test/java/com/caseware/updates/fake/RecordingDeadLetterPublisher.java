package com.caseware.updates.fake;

import com.caseware.updates.model.EngagementMessage;
import com.caseware.updates.port.DeadLetterPublisher;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Records what would have gone to a dead-letter queue. */
public final class RecordingDeadLetterPublisher implements DeadLetterPublisher {

    public record DeadLetter(EngagementMessage message, String code) {}

    private final Queue<DeadLetter> published = new ConcurrentLinkedQueue<>();

    @Override
    public void publish(EngagementMessage message, String code) {
        published.add(new DeadLetter(message, code));
    }

    public List<DeadLetter> published() {
        return List.copyOf(published);
    }
}
