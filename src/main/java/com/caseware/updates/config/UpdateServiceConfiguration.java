package com.caseware.updates.config;

import com.caseware.updates.handler.EngagementMessageHandler;
import com.caseware.updates.port.DeadLetterPublisher;
import com.caseware.updates.port.EngagementClient;
import com.caseware.updates.port.EngagementHistoryRepository;
import com.caseware.updates.port.PendingUpdateRepository;
import com.caseware.updates.port.ProductVersionRepository;
import com.caseware.updates.port.SummaryClient;
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Wires the handler to its ports. The SQL Server and HTTP adapters behind those ports are not part of this
 * component, so each deployment provides them as beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UpdateServiceProperties.class)
class UpdateServiceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    EngagementMessageHandler engagementMessageHandler(
            UpdateServiceProperties properties,
            EngagementHistoryRepository history,
            ProductVersionRepository products,
            PendingUpdateRepository pendingUpdates,
            EngagementClient engagements,
            SummaryClient summaries,
            DeadLetterPublisher deadLetters,
            Clock clock,
            MeterRegistry meters) {
        return new EngagementMessageHandler(
                properties.region(),
                properties.permitsPerPod(),
                history,
                products,
                pendingUpdates,
                engagements,
                summaries,
                deadLetters,
                clock,
                meters);
    }

    /**
     * Spring Cloud AWS runs listeners on a pool of platform threads and ignores
     * {@code spring.threads.virtual.enabled}. This moves them to virtual threads, so a listener blocked for a
     * minute in a load or a summary parks instead of holding an OS thread. Each container's back-pressure still
     * caps the messages it has in flight.
     */
    @Bean
    static BeanPostProcessor sqsListenersOnVirtualThreads() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof SqsMessageListenerContainerFactory<?> factory) {
                    factory.configure(options -> options.componentsTaskExecutor(virtualThreads()));
                }
                return bean;
            }
        };
    }

    private static SimpleAsyncTaskExecutor virtualThreads() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sqs-listener-");
        executor.setVirtualThreads(true);
        return executor;
    }
}
