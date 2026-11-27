package com.caseware.updates.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.caseware.updates.model.Region;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UpdateServicePropertiesTest {

    private static final Duration LOAD_TIMEOUT = Duration.ofSeconds(120);

    @Test
    void acceptsTheProductionValues() {
        assertThat(properties(Region.EU, 20, LOAD_TIMEOUT, 300).permitsPerPod()).isEqualTo(20);
    }

    @Test
    void requiresARegion() {
        assertThatNullPointerException()
                .isThrownBy(() -> properties(null, 20, LOAD_TIMEOUT, 300))
                .withMessageContaining("update-service.region");
    }

    @Test
    void requiresAtLeastOnePermit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Region.EU, 0, LOAD_TIMEOUT, 300))
                .withMessageContaining("permits-per-pod");
    }

    @Test
    void requiresTheLoadTimeoutToEndBeforeTheVisibilityTimeout() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties(Region.EU, 20, Duration.ofSeconds(300), 300))
                .withMessageContaining("visibility-timeout-seconds");
    }

    private static UpdateServiceProperties properties(
            Region region, int permitsPerPod, Duration loadTimeout, int visibilityTimeoutSeconds) {
        return new UpdateServiceProperties(
                region, permitsPerPod, loadTimeout, visibilityTimeoutSeconds, "engagement-events", "engagement-sync");
    }
}
