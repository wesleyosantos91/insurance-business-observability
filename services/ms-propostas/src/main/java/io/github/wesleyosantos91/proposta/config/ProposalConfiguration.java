package io.github.wesleyosantos91.proposta.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProposalMessagingProperties.class)
class ProposalConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
