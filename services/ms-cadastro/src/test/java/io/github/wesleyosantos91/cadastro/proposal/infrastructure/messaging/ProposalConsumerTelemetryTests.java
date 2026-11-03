package io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import io.github.wesleyosantos91.cadastro.proposal.application.ProcessProposalCreatedUseCase;
import io.github.wesleyosantos91.cadastro.proposal.infrastructure.observability.ProposalTelemetry;
import io.github.wesleyosantos91.insurance.events.proposals.v1.EventMetadata;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreatedPayload;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalConsumerTelemetryTests {
    private final Instant now = Instant.parse("2026-11-01T12:00:00Z");
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry registry = ObservationRegistry.create();
    private final ArrayList<Observation.Context> stopped = new ArrayList<>();
    private final ProcessProposalCreatedUseCase useCase = mock(ProcessProposalCreatedUseCase.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ProposalCreatedConsumer consumer;

    ProposalConsumerTelemetryTests() {
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { stopped.add(context); }
        });
        consumer = new ProposalCreatedConsumer(useCase,
                new ProposalTelemetry(registry, meters, Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void commitAndDuplicateHaveCorrelatedCompletedObservationsAndDistinctMetrics() {
        when(useCase.process(any())).thenReturn(true, false);
        consumer.receive(record(), ack);
        consumer.receive(record(), ack);
        assertEquals(1, meters.get("insurance.proposal.receipts").tag("outcome", "new").counter().count());
        assertEquals(1, meters.get("insurance.proposal.receipts").tag("outcome", "duplicate").counter().count());
        assertEquals(1, meters.get("insurance.proposal.delivery.latency").timer().count());
        assertEquals(2, stopped.size());
        for (var context : stopped) {
            assertEquals("event", context.getHighCardinalityKeyValue("eventId").getValue());
            assertEquals("journey", context.getHighCardinalityKeyValue("journeyId").getValue());
            assertEquals("correlation", context.getHighCardinalityKeyValue("correlationId").getValue());
        }
        assertNull(registry.getCurrentObservation());
        verify(ack, times(2)).acknowledge();
    }

    @Test
    void processingAndAckFailuresHaveBoundedFailureStageAndCloseObservation() {
        var failure = new IllegalStateException("database unavailable");
        when(useCase.process(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> consumer.receive(record(), ack)));
        assertEquals(1, meters.get("insurance.proposal.consume.failures").tag("stage", "processing").counter().count());
        verifyNoInteractions(ack);
        doReturn(true).when(useCase).process(any());
        doThrow(failure).when(ack).acknowledge();
        assertThrows(IllegalStateException.class, () -> consumer.receive(record(), ack));
        assertEquals(1, meters.get("insurance.proposal.consume.failures").tag("stage", "acknowledgment").counter().count());
        assertEquals(2, stopped.size());
        assertSame(failure, stopped.getFirst().getError());
        assertNull(registry.getCurrentObservation());
    }

    private ConsumerRecord<String, ProposalCreated> record() {
        return new ConsumerRecord<>("insurance.proposals.events.v1", 0, 1L, "proposal",
                new ProposalCreated(new EventMetadata("event", "ProposalCreated", "1.0.0", now.toString(),
                        "AUTO", "PROPOSAL", "journey", "root", "correlation", null, null),
                        new ProposalCreatedPayload("proposal")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void committedAndDuplicateLogsCarryBusinessContextAndPreciseOutcome(boolean created) {
        when(useCase.process(any())).thenReturn(created);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProposalCreatedConsumer.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            consumer.receive(record(), ack);
            var fields = appender.list.getLast().getKeyValuePairs().stream().collect(
                    java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertEquals(created ? "success" : "duplicate", fields.get("outcome"));
            assertEquals("proposal", fields.get("businessId"));
            assertEquals("none", fields.get("error_code"));
            assertEquals("AUTO", fields.get("product_key"));
            assertEquals("PROPOSAL", fields.get("journey_type"));
            assertNotNull(fields.get("journeyId"));
            assertNotNull(fields.get("correlationId"));
            assertNotNull(fields.get("eventId"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

}
