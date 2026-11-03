package io.github.wesleyosantos91.cadastro.proposal.infrastructure.observability;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import io.github.wesleyosantos91.cadastro.proposal.application.ReceivedProposal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalTelemetryTests {
    @Test
    void duplicateReceiptsDoNotCountAsNewBusinessOrRepeatDeliveryLatency() {
        var meters = new SimpleMeterRegistry();
        var occurredAt = Instant.parse("2026-11-01T12:00:00Z");
        var telemetry = new ProposalTelemetry(ObservationRegistry.NOOP, meters,
                Clock.fixed(occurredAt.plusSeconds(2), ZoneOffset.UTC));
        var event = proposal(occurredAt);
        var observation = telemetry.consumption();
        telemetry.committed(observation, event, true);
        assertEquals(1, meters.get("insurance.proposal.delivery.latency").timer().count());
        assertEquals(1, meters.get("insurance.business.transitions").counter().count());
        assertEquals(2, meters.get("insurance.proposal.delivery.latency").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS));
        telemetry.committed(observation, event, false);
        observation.stop();
        assertEquals(1, meters.counter("insurance.proposal.receipts", "outcome", "new").count());
        assertEquals(1, meters.counter("insurance.proposal.receipts", "outcome", "duplicate").count());
        assertEquals(1, meters.get("insurance.inbox.duplicates").counter().count());
        assertEquals(1, meters.get("insurance.proposal.delivery.latency").timer().count());
        assertEquals(1, meters.get("insurance.business.transitions").counter().count());
        assertEquals(2, meters.get("insurance.proposal.delivery.latency").timer()
                .totalTime(java.util.concurrent.TimeUnit.SECONDS));
        meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertFalse(tag.getKey().toLowerCase().endsWith("id"));
            assertNotEquals("product-unique", tag.getValue());
        }));
    }

    @Test
    void aDuplicateAsTheFirstReceiptDoesNotCreateADeliveryLatencyMeasurement() {
        var meters = new SimpleMeterRegistry();
        var now = Instant.parse("2026-11-01T12:00:00Z");
        var telemetry = new ProposalTelemetry(ObservationRegistry.NOOP, meters, Clock.fixed(now, ZoneOffset.UTC));
        telemetry.committed(telemetry.consumption(), proposal(now.minusSeconds(2)), false);
        assertNull(meters.find("insurance.proposal.delivery.latency").timer());
        assertNull(meters.find("insurance.business.transitions").counter());
        assertEquals(1, meters.counter("insurance.proposal.receipts", "outcome", "duplicate").count());
        assertEquals(1, meters.get("insurance.inbox.duplicates").counter().count());
    }
    @Test
    void clockSkewDoesNotCreateNegativeDeliveryDurations() {
        var meters = new SimpleMeterRegistry();
        var now = Instant.parse("2026-11-01T12:00:00Z");
        var telemetry = new ProposalTelemetry(ObservationRegistry.NOOP, meters, Clock.fixed(now, ZoneOffset.UTC));
        telemetry.committed(telemetry.consumption(), proposal(now.plusSeconds(1)), true);
        assertNull(meters.find("insurance.proposal.delivery.latency").timer());
        assertEquals(1, meters.counter("insurance.proposal.receipts", "outcome", "new").count());
    }

    @Test
    void validationProcessingAndAckFailuresHaveBoundedCodesAndNoSuccessTransition() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(meters));
        var telemetry = new ProposalTelemetry(registry, meters, Clock.systemUTC());
        for (var entry : java.util.Map.of("validation", "invalid_event", "processing", "processing_failure",
                "acknowledgment", "acknowledgment_failure").entrySet()) {
            var observation = telemetry.consumption();
            telemetry.failure(observation, entry.getKey());
            assertEquals("technical_failure", observation.getContext().getLowCardinalityKeyValue("outcome").getValue());
            assertEquals(entry.getValue(), observation.getContext().getLowCardinalityKeyValue("error_code").getValue());
            observation.stop();
            assertEquals(1, meters.get("insurance.proposal.consume.failures").tag("stage", entry.getKey()).counter().count());
        }
        assertNull(meters.find("insurance.business.transitions").counter());
    }

    private ReceivedProposal proposal(Instant occurredAt) {
        return new ReceivedProposal("e", "ProposalCreated", "1.0.0", "PROPOSAL", "p", "product-unique",
                "j", "j", "c", null, null, occurredAt);
    }
}
