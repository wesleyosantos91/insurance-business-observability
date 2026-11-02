package io.github.wesleyosantos91.proposta.config;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("insurance.kafka")
public record ProposalMessagingProperties(
        @NotBlank String proposalsTopic, @NotNull Duration publicationTimeout) {

    @AssertTrue(message = "publication-timeout must be positive")
    public boolean isPublicationTimeoutPositive() {
        return publicationTimeout != null && publicationTimeout.isPositive();
    }
}
