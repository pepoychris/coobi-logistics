package com.coobi.logistics.logisticsapi.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The ticker of the browser streams (MVP-6).
 *
 * <p>The scheduler is a bean of its own rather than the one of the framework for two reasons:
 * the two streams of the API must never compete with anything else for a thread, and a
 * scheduler that is a bean can be replaced in a test, where the ticks of a stream are run by
 * the test instead of by a clock.
 *
 * <p>Its threads are daemons and its tasks are short reads of the database, so a shutdown does
 * not wait for a tick to finish. The connections themselves are closed as the context closes
 * (see {@code SseEndpoint}), before the web server starts waiting for them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamProperties.class)
public class StreamConfiguration {

    @Bean
    TaskScheduler streamScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // One ticker per stream, and no queue of ticks: a stream that is slower than its
        // interval must be late, never accumulate a backlog of its own.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("stream-tick-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
