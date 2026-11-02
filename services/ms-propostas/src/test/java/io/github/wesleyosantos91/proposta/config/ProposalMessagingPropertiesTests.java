package io.github.wesleyosantos91.proposta.config;

import java.time.Duration;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalMessagingPropertiesTests {
    @Test
    void publicationTimeoutMustBePresentAndPositive() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var duration : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(-1)}) {
                assertFalse(validator.validate(new ProposalMessagingProperties("proposals", duration)).isEmpty());
            }
            assertTrue(validator.validate(
                    new ProposalMessagingProperties("proposals", Duration.ofSeconds(1))).isEmpty());
        }
    }
}
