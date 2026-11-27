package com.caseware.updates.adapter;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.updates.fake.FakeEngagementClient;
import com.caseware.updates.fake.FakeSummaryClient;
import com.caseware.updates.fake.InMemoryUpdateDatabase;
import com.caseware.updates.fake.RecordingDeadLetterPublisher;
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry;
import io.awspring.cloud.sqs.listener.SqsContainerOptions;
import io.awspring.cloud.sqs.listener.SqsMessageListenerContainer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Boots the real Spring context with the ports backed by fakes. The listener containers are built and configured
 * but never started, so nothing reaches AWS.
 */
@SpringBootTest(properties = {
    "update-service.region=EU",
    "spring.cloud.aws.region.static=eu-west-1",
    "spring.cloud.aws.credentials.access-key=test",
    "spring.cloud.aws.credentials.secret-key=test",
    "spring.cloud.aws.sqs.listener.auto-startup=false"
})
class SqsListenerWiringTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakePorts {

        @Bean
        InMemoryUpdateDatabase database() {
            return new InMemoryUpdateDatabase();
        }

        @Bean
        FakeEngagementClient engagementClient() {
            return new FakeEngagementClient();
        }

        @Bean
        FakeSummaryClient summaryClient() {
            return new FakeSummaryClient();
        }

        @Bean
        RecordingDeadLetterPublisher deadLetterPublisher() {
            return new RecordingDeadLetterPublisher();
        }
    }

    @Autowired
    private MessageListenerContainerRegistry containers;

    @Test
    void syncListenerTakesOneMessagePerLoadPermit() {
        SqsMessageListenerContainer<?> sync = container("engagement-sync");

        assertThat(sync.getQueueNames()).containsExactly("engagement-sync");
        assertThat(sync.getContainerOptions().getMaxConcurrentMessages()).isEqualTo(20);
        assertThat(sync.getContainerOptions().getMessageVisibility()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void eventListenerHidesMessagesForLongerThanASummaryTakes() {
        SqsMessageListenerContainer<?> events = container("engagement-events");

        assertThat(events.getQueueNames()).containsExactly("engagement-events");
        assertThat(events.getContainerOptions().getMessageVisibility()).isEqualTo(Duration.ofSeconds(300));
    }

    @ParameterizedTest
    @ValueSource(strings = {"engagement-events", "engagement-sync"})
    void listenersRunOnVirtualThreads(String containerId) throws Exception {
        SqsContainerOptions options = container(containerId).getContainerOptions();
        CompletableFuture<Boolean> virtual = new CompletableFuture<>();

        options.getComponentsTaskExecutor().execute(() -> virtual.complete(Thread.currentThread().isVirtual()));

        assertThat(virtual.get(10, SECONDS)).isTrue();
    }

    private SqsMessageListenerContainer<?> container(String id) {
        return (SqsMessageListenerContainer<?>) containers.getContainerById(id);
    }
}
