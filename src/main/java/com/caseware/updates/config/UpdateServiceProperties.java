package com.caseware.updates.config;

import com.caseware.updates.model.Region;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code update-service.*} settings, checked when they are bound: a bad value stops the service at startup
 * rather than in the middle of a night's sync.
 *
 * @param region the region this deployment serves; messages for any other region go to the DLQ
 * @param permitsPerPod concurrent loads per pod; pods × permits is the per-region cap agreed with the engagement team
 * @param loadTimeout how long the engagement-service adapter waits for one load before giving up
 * @param visibilityTimeoutSeconds how long SQS hides a received message; longer than a load, so a message is not
 *     delivered again while its load is still running
 */
@ConfigurationProperties("update-service")
public record UpdateServiceProperties(
        Region region,
        int permitsPerPod,
        Duration loadTimeout,
        int visibilityTimeoutSeconds,
        String eventsQueue,
        String syncQueue) {

    public UpdateServiceProperties {
        Objects.requireNonNull(region, "update-service.region is required (EU, CA or US)");
        Objects.requireNonNull(loadTimeout, "update-service.load-timeout is required");
        requireText(eventsQueue, "update-service.events-queue");
        requireText(syncQueue, "update-service.sync-queue");
        if (permitsPerPod <= 0) {
            throw new IllegalArgumentException("update-service.permits-per-pod must be > 0, was " + permitsPerPod);
        }
        if (!loadTimeout.isPositive()) {
            throw new IllegalArgumentException("update-service.load-timeout must be > 0, was " + loadTimeout);
        }
        if (loadTimeout.compareTo(Duration.ofSeconds(visibilityTimeoutSeconds)) >= 0) {
            throw new IllegalArgumentException("update-service.load-timeout (" + loadTimeout
                    + ") must be shorter than update-service.visibility-timeout-seconds (" + visibilityTimeoutSeconds
                    + "s), or SQS delivers a message again while its load is still running");
        }
    }

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " is required");
        }
    }
}
