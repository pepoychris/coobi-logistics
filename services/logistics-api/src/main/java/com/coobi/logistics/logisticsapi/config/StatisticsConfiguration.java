package com.coobi.logistics.logisticsapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client the statistics endpoint reads the processor counters with.
 *
 * <p>The client is built here rather than inside the reader for two reasons: its timeout is
 * part of the configuration of the service, and a client that is a bean can be replaced in a
 * test without starting a second process.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatisticsProperties.class)
public class StatisticsConfiguration {

    @Bean
    RestClient streamProcessorMetricsRestClient(StatisticsProperties properties, RestClient.Builder builder) {
        Duration timeout = properties.streamProcessor().timeout();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return builder.requestFactory(requestFactory).build();
    }
}
