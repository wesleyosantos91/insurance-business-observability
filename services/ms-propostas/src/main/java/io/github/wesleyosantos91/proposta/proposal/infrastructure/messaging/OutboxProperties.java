package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("insurance.outbox")
public record OutboxProperties(@DefaultValue("30s") Duration leaseDuration,
                               @DefaultValue("6s") Duration publicationTimeout) {
    public OutboxProperties {
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(publicationTimeout, "publicationTimeout");
        if (publicationTimeout.isNegative() || publicationTimeout.isZero()
                || publicationTimeout.toMillis() == 0 || leaseDuration.compareTo(publicationTimeout) <= 0) {
            throw new IllegalArgumentException("Outbox lease must exceed a positive publication timeout");
        }
    }
}
