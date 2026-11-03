package io.github.wesleyosantos91.proposta.proposal.infrastructure.observability;

import java.time.Instant;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProposalTelemetryTests {
    @Test
    void businessIdsAreSpanAttributesAndNeverMetricDimensions() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var telemetry = new ProposalTelemetry(registry, meters, Tracer.NOOP, Propagator.NOOP);
        var proposal = new StoredProposal("proposal-unique", "UNBOUNDED_PRODUCT", "journey-unique",
                "correlation-unique", "event-unique", Instant.parse("2026-11-01T12:00:00Z"), false, null, null);
        var observation = telemetry.registration();
        try (var scope = observation.openScope()) {
            assertSame(observation, registry.getCurrentObservation());
            assertTrue(telemetry.traceHeaders().isEmpty());
            telemetry.registered(observation, proposal, true);
            assertEquals("proposal-unique",
                    observation.getContext().getHighCardinalityKeyValue("proposalId").getValue());
        } finally {
            observation.stop();
        }
        assertNull(registry.getCurrentObservation());
        assertEquals(1, meters.counter("insurance.proposal.registrations", "outcome", "created").count());
        assertEquals(1, meters.get("insurance.proposal.register").timer().count());
        meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            assertFalse(tag.getValue().endsWith("-unique"));
            assertNotEquals("UNBOUNDED_PRODUCT", tag.getValue());
        }));
    }

    @Test
    void noActiveSpanProducesNoPropagationHeaders() {
        var tracer = org.mockito.Mockito.mock(Tracer.class);
        var propagator = org.mockito.Mockito.mock(Propagator.class);
        var telemetry = new ProposalTelemetry(ObservationRegistry.NOOP, new SimpleMeterRegistry(), tracer, propagator);
        assertTrue(telemetry.traceHeaders().isEmpty());
        org.mockito.Mockito.verifyNoInteractions(propagator);
    }

    @Test
    void replayCountsSeparatelyFromNewRegistrations() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new ProposalTelemetry(ObservationRegistry.NOOP, meters, Tracer.NOOP, Propagator.NOOP);
        var proposal = new StoredProposal("p", "AUTO", "j", "c", "e",
                Instant.parse("2026-11-01T12:00:00Z"), false, null, null);
        telemetry.registered(telemetry.registration(), proposal, false);
        assertEquals(1, meters.get("insurance.proposal.registrations").tag("outcome", "replayed").counter().count());
        assertNull(meters.find("insurance.proposal.registrations").tag("outcome", "created").counter());
        assertNull(meters.find("insurance.business.transitions").counter());
    }
    @Test
    void aLegacyOutboxWithoutTraceContextStillHasObservableDelivery() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var telemetry = new ProposalTelemetry(registry, meters, Tracer.NOOP, Propagator.NOOP);
        var observation = telemetry.relay(new StoredProposal("p", "AUTO", "j", "c", "e",
                Instant.now(), false, null, null));
        try (var scope = observation.openScope()) {
            telemetry.relayOutcome(observation, "published");
        } finally {
            observation.stop();
        }
        assertEquals(1, meters.counter("insurance.outbox.dispatches", "outcome", "published").count());
        assertEquals(1, meters.get("insurance.outbox.publish").timer().count());
        assertNull(registry.getCurrentObservation());
    }
    @Test
    void newCommitReplayAndRetriesHaveIndependentBusinessAndTechnicalCounts() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var telemetry = new ProposalTelemetry(registry, meters, Tracer.NOOP, Propagator.NOOP);
        var proposal = new StoredProposal("p", "AUTO", "j", "c", "e", Instant.now(), false, null, null);
        var registration = telemetry.registration();
        telemetry.registered(registration, proposal, true);
        registration.stop();
        assertEquals(1, meters.get("insurance.business.transitions").counter().count());
        var replay = telemetry.registration();
        telemetry.registered(replay, proposal, false);
        replay.stop();
        assertEquals(1, meters.get("insurance.business.transitions").counter().count());
        for (var outcome : java.util.List.of("retry", "claim_lost", "database_failure", "published")) {
            var attempt = telemetry.relay(proposal);
            telemetry.relayOutcome(attempt, outcome);
            var code = switch (outcome) {
                case "retry" -> "publication_unconfirmed";
                case "claim_lost" -> "lease_lost";
                case "database_failure" -> "persistence_failure";
                default -> "none";
            };
            assertEquals(code, attempt.getContext().getLowCardinalityKeyValue("error_code").getValue());
            assertEquals(outcome, attempt.getContext().getHighCardinalityKeyValue("status").getValue());
            assertEquals("p", attempt.getContext().getHighCardinalityKeyValue("businessId").getValue());
            attempt.stop();
        }
        assertEquals(4, meters.get("insurance.outbox.publish.attempts").counter().count());
        assertEquals(1, meters.get("insurance.business.transitions").counter().count());
    }

    @Test
    void conflictIsARequestRejectionAndTechnicalFailureIsNotABusinessTransition() {
        var meters = new SimpleMeterRegistry();
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        var telemetry = new ProposalTelemetry(registry, meters, Tracer.NOOP, Propagator.NOOP);
        var conflict = telemetry.registration();
        telemetry.registrationFailed(conflict,
                new io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException());
        assertEquals("rejected", conflict.getContext().getLowCardinalityKeyValue("outcome").getValue());
        assertEquals("idempotency_conflict", conflict.getContext().getLowCardinalityKeyValue("error_code").getValue());
        conflict.stop();
        var failed = telemetry.registration();
        var failure = new IllegalStateException("private arbitrary message");
        telemetry.registrationFailed(failed, failure);
        assertSame(failure, failed.getContext().getError());
        assertEquals("technical_failure", failed.getContext().getLowCardinalityKeyValue("outcome").getValue());
        assertEquals("registration_failure", failed.getContext().getLowCardinalityKeyValue("error_code").getValue());
        failed.stop();
        assertEquals(1, meters.get("insurance.proposal.registrations").tag("outcome", "rejected").counter().count());
        assertEquals(1, meters.get("insurance.proposal.registrations").tag("outcome", "technical_failure").counter().count());
        assertNull(meters.find("insurance.business.transitions").counter());
        meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertNotEquals("private arbitrary message", tag.getValue())));
    }

}
