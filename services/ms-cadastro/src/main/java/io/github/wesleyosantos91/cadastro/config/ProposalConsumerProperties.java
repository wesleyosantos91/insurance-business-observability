package io.github.wesleyosantos91.cadastro.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("insurance.kafka")
public record ProposalConsumerProperties(@NotBlank String proposalsTopic) {
}
