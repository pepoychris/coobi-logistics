package com.coobi.logistics.streamprocessor.config;

import com.coobi.logistics.streamprocessor.persistence.JdbcTelemetryPersistence;
import com.coobi.logistics.streamprocessor.persistence.TelemetryPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Persistence wiring of the stream processor (MVP-4.1).
 *
 * <p>The {@code JdbcTemplate}, the {@code DataSource} behind it and the transaction manager
 * come from Spring Boot's JDBC auto-configuration, which reads the datasource settings from
 * the environment. The schema itself is created by Flyway migration scripts, never by the
 * application: the migrations under {@code db/migration} are the only definition of the
 * schema.
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    @Bean
    public TelemetryPersistence telemetryPersistence(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            Clock processingClock) {
        return new JdbcTelemetryPersistence(jdbcTemplate, transactionManager, objectMapper, processingClock);
    }
}
