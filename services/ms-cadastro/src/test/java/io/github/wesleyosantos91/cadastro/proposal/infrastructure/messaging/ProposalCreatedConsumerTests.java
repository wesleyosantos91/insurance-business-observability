package io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging;

import java.time.Instant;
import java.util.stream.Stream;
import io.github.wesleyosantos91.cadastro.proposal.application.ProcessProposalCreatedUseCase;
import io.github.wesleyosantos91.cadastro.proposal.application.ReceivedProposal;
import io.github.wesleyosantos91.insurance.events.proposals.v1.EventMetadata;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreatedPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import java.time.Clock;
import java.time.ZoneOffset;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.wesleyosantos91.cadastro.proposal.infrastructure.observability.ProposalTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalCreatedConsumerTests {
    private final ProcessProposalCreatedUseCase useCase = mock(ProcessProposalCreatedUseCase.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final ProposalCreatedConsumer consumer = new ProposalCreatedConsumer(useCase,
            new ProposalTelemetry(ObservationRegistry.NOOP, new SimpleMeterRegistry(),
                    Clock.fixed(Instant.parse("2026-11-01T12:00:01Z"), ZoneOffset.UTC)));

    @Test
    void preservesCorrelationAndAcknowledgesOnlyAfterProcessingOnTheSameThread() {
        var processingThread = Thread.currentThread();
        doAnswer(invocation -> {
            assertSame(processingThread, Thread.currentThread());
            verifyNoInteractions(ack);
            return true;
        }).when(useCase).process(any());
        consumer.receive(record(event()), ack);
        var command = ArgumentCaptor.forClass(ReceivedProposal.class);
        var order = inOrder(useCase, ack);
        order.verify(useCase).process(command.capture());
        order.verify(ack).acknowledge();
        var received = command.getValue();
        assertEquals("event", received.eventId());
        assertEquals("ProposalCreated", received.eventType());
        assertEquals("proposal", received.proposalId());
        assertEquals("journey", received.journeyId());
        assertEquals("root", received.rootJourneyId());
        assertEquals("correlation", received.correlationId());
        assertEquals("parent", received.parentJourneyId());
        assertEquals("cause", received.causationId());
        assertEquals("AUTO", received.productKey());
        assertEquals(Instant.parse("2026-11-01T12:00:00Z"), received.occurredAt());
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void invalidRequiredMetadataStopsBeforeProcessingAndAcknowledgment(String field) {
        var event = event();
        var metadata = event.getMetadata();
        metadata.put(field, field.equals("occurredAt") ? "invalid-date" : "");
        assertThrows(RuntimeException.class, () -> consumer.receive(record(event), ack));
        verifyNoInteractions(useCase, ack);
    }

    static Stream<String> invalidFields() {
        return Stream.of("eventId", "eventType", "schemaVersion", "occurredAt", "productKey",
                "journeyType", "journeyId", "rootJourneyId", "correlationId",
                "parentJourneyId", "causationId");
    }

    @Test
    void rejectsUnexpectedEventType() {
        var event = event();
        event.getMetadata().setEventType("ProposalApproved");
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(record(event), ack));
        verifyNoInteractions(useCase, ack);
    }

    @Test
    void rejectsInvalidProposalAndPartitionKey() {
        var event = event();
        event.getPayload().setProposalId("");
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(record(event), ack));
        event.getPayload().setProposalId("different");
        assertThrows(IllegalArgumentException.class, () -> consumer.receive(record(event), ack));
        verifyNoInteractions(useCase, ack);
    }

    @Test
    void rejectsMissingEventMetadataAndPayload() {
        assertThrows(RuntimeException.class, () -> consumer.receive(record(null), ack));
        var missingMetadata = event();
        missingMetadata.setMetadata(null);
        assertThrows(RuntimeException.class, () -> consumer.receive(record(missingMetadata), ack));
        var missingPayload = event();
        missingPayload.setPayload(null);
        assertThrows(RuntimeException.class, () -> consumer.receive(record(missingPayload), ack));
        verifyNoInteractions(useCase, ack);
    }

    @Test
    void processingFailureIsPropagatedWithoutAcknowledgment() {
        var failure = new IllegalStateException("processing unavailable");
        doThrow(failure).when(useCase).process(any());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> consumer.receive(record(event()), ack)));
        verifyNoInteractions(ack);
    }

    private ConsumerRecord<String, ProposalCreated> record(ProposalCreated event) {
        return new ConsumerRecord<>("insurance.proposals.events.v1", 1, 7L, "proposal", event);
    }

    private ProposalCreated event() {
        return new ProposalCreated(new EventMetadata("event", "ProposalCreated", "1.0.0",
                "2026-11-01T12:00:00Z", "AUTO", "PROPOSAL", "journey", "root", "correlation",
                "parent", "cause"), new ProposalCreatedPayload("proposal"));
    }
}
