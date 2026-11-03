package io.github.wesleyosantos91.cadastro.config;

import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class BusinessTelemetryConfigurationTests {
    @ParameterizedTest
    @CsvSource({
        "insurance.proposal.consume,insurance.business.step.duration,proposal_receive"
    })
    void existingObservationsExportCanonicalTimersWithoutExceptionClassTags(String original, String exported, String step) {
        var meters = new SimpleMeterRegistry();
        try {
            meters.config().meterFilter(new BusinessTelemetryConfiguration().businessDurations());
            var timer = Timer.builder(original).tags(Tags.of("journey_type", "PROPOSAL", "outcome", "retry",
                    "error_code", "publication_unconfirmed", "error", "PrivateException", "eventType", "ProposalCreated",
                    "service", "ms-cadastro")).register(meters);
            timer.record(25, TimeUnit.MILLISECONDS);
            var actual = meters.get(exported).tag("step", step).timer();
            assertSame(timer, actual);
            assertEquals(1, actual.count());
            assertEquals(25, actual.totalTime(TimeUnit.MILLISECONDS));
            assertEquals("publication_unconfirmed", actual.getId().getTag("error_code"));
            assertEquals("PROPOSAL", actual.getId().getTag("journey_type"));
            assertEquals("ms-cadastro", actual.getId().getTag("service"));
            assertNull(actual.getId().getTag("error"));
            assertNull(actual.getId().getTag("eventType"));
            assertNull(meters.find(original).timer());
        } finally {
            meters.close();
        }
    }

    @Test
    void nativeMetricsRemainUnchanged() {
        var meters = new SimpleMeterRegistry();
        try {
            meters.config().meterFilter(new BusinessTelemetryConfiguration().businessDurations());
            var counter = meters.counter("http.server.requests.test", "status", "202");
            counter.increment();
            assertSame(counter, meters.get("http.server.requests.test").counter());
            assertEquals(1, counter.count());
        } finally {
            meters.close();
        }
    }
}
