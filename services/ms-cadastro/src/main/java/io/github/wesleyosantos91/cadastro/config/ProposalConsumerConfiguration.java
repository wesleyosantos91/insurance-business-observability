package io.github.wesleyosantos91.cadastro.config;

import io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging.ProposalConsumerErrorHandler;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProposalConsumerProperties.class)
class ProposalConsumerConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CommonErrorHandler proposalConsumerErrorHandler() {
        return new ProposalConsumerErrorHandler();
    }
}
