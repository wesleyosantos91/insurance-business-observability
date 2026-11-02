package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import io.github.wesleyosantos91.insurance.events.proposals.v1.EventMetadata;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreatedPayload;
import io.github.wesleyosantos91.proposta.config.ProposalMessagingProperties;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.Test;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalEventPublisherTests {
    private static final String TOPIC = "insurance.proposals.events.v1";
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ProposalCreated> kafka = mock(KafkaTemplate.class);
    private final ProposalEventPublisher publisher = new ProposalEventPublisher(kafka,
            new ProposalMessagingProperties(TOPIC, Duration.ofSeconds(5)), ObservationRegistry.NOOP);

    @Test
    void usesProposalIdAsKeyAndWaitsForTheBroker() throws Exception {
        var event = event();
        var confirmation = new CompletableFuture<SendResult<String, ProposalCreated>>();
        when(kafka.send(TOPIC, "proposal", event)).thenReturn(confirmation);
        var result = publisher.publish(event);
        assertFalse(result.isDone());
        verify(kafka).send(TOPIC, "proposal", event);
        confirmation.complete(new SendResult<>(new ProducerRecord<>(TOPIC, "proposal", event),
                new RecordMetadata(new TopicPartition(TOPIC, 1), 7, 0, 0, -1, -1)));
        assertNull(result.get(1, TimeUnit.SECONDS));
    }

    @Test
    void synchronousSerializationOrMissingSchemaFailureBecomesAFailedFuture() {
        var event = event();
        var failure = new SerializationException("Schema not found");
        when(kafka.send(TOPIC, "proposal", event)).thenThrow(failure);
        var result = assertDoesNotThrow(() -> publisher.publish(event));
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> result.get(1, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void asynchronousConfirmationFailureIsPreserved() {
        var event = event();
        var failure = new org.apache.kafka.common.errors.TimeoutException("delivery expired");
        when(kafka.send(TOPIC, "proposal", event)).thenReturn(CompletableFuture.failedFuture(failure));
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> publisher.publish(event).get(1, TimeUnit.SECONDS)).getCause());
    }

    @Test
    void confirmationLogReopensCapturedRequestObservationAndPreservesCorrelation() throws Exception {
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        var request = io.micrometer.observation.Observation.start("request", registry);
        var logs = new java.util.ArrayList<ch.qos.logback.classic.spi.ILoggingEvent>();
        var scopes = new java.util.ArrayList<io.micrometer.observation.Observation>();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent entry) {
                if (entry.getFormattedMessage().equals("ProposalCreated publication confirmed")) {
                    logs.add(entry);
                    scopes.add(registry.getCurrentObservation());
                }
            }
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ProposalEventPublisher.class);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            var event = event();
            var confirmation = new CompletableFuture<SendResult<String, ProposalCreated>>();
            when(kafka.send(TOPIC, "proposal", event)).thenReturn(confirmation);
            var scopedPublisher = new ProposalEventPublisher(kafka,
                    new ProposalMessagingProperties(TOPIC, Duration.ofSeconds(5)), registry);
            CompletableFuture<Void> result;
            try (var scope = request.openScope()) {
                result = scopedPublisher.publish(event);
            }
            org.junit.jupiter.api.Assertions.assertNull(registry.getCurrentObservation());
            confirmation.complete(new SendResult<>(new ProducerRecord<>(TOPIC, "proposal", event),
                    new RecordMetadata(new TopicPartition(TOPIC, 1), 7, 0, 0, -1, -1)));
            result.get(1, TimeUnit.SECONDS);
            assertEquals(1, logs.size());
            assertSame(request, scopes.getFirst());
            var fields = logs.getFirst().getKeyValuePairs().stream().collect(
                    java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value.toString()));
            assertEquals("event", fields.get("eventId"));
            assertEquals("proposal", fields.get("proposalId"));
            assertEquals("journey", fields.get("journeyId"));
            assertEquals("correlation", fields.get("correlationId"));
            assertEquals(TOPIC, fields.get("topic"));
            org.junit.jupiter.api.Assertions.assertNull(registry.getCurrentObservation());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            request.stop();
        }
    }

    private ProposalCreated event() {
        var metadata = new EventMetadata("event", "ProposalCreated", "1.0.0",
                "2026-11-01T12:00:00Z", "AUTO", "PROPOSAL", "journey", "journey",
                "correlation", null, null);
        return new ProposalCreated(metadata, new ProposalCreatedPayload("proposal"));
    }
}
