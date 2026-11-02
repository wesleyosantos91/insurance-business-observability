package io.github.wesleyosantos91.proposta.config;

import io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class PersistenceConfiguration {
    @Bean
    FlywayMigrationStrategy migrationStrategy(Environment environment) {
        // Runtime guard: preserve the AOT bean graph while training without a database.
        return flyway -> {
            if (environment.getProperty("insurance.persistence.initialize", Boolean.class, true)) {
                flyway.migrate();
            }
        };
    }
}
