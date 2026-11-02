package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.util.concurrent.CompletableFuture;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.proposta.config.ProposalMessagingProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProposalEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalEventPublisher.class);
    private final KafkaTemplate<String, ProposalCreated> kafka;
    private final ProposalMessagingProperties properties;
    private final ObservationRegistry observations;

    public ProposalEventPublisher(KafkaTemplate<String, ProposalCreated> kafka,
                                  ProposalMessagingProperties properties, ObservationRegistry observations) {
        this.kafka = kafka;
        this.properties = properties;
        this.observations = observations;
    }

    public CompletableFuture<Void> publish(ProposalCreated event) {
        var parent = observations.getCurrentObservation();
        try {
            return kafka.send(properties.proposalsTopic(), event.getPayload().getProposalId().toString(), event)
                    .thenAccept(result -> Observation.tryScoped(parent, () -> {
                        var metadata = event.getMetadata();
                        var record = result.getRecordMetadata();
                        LOG.atInfo()
                                .addKeyValue("eventType", metadata.getEventType())
                                .addKeyValue("eventId", metadata.getEventId())
                                .addKeyValue("proposalId", event.getPayload().getProposalId())
                                .addKeyValue("journeyId", metadata.getJourneyId())
                                .addKeyValue("rootJourneyId", metadata.getRootJourneyId())
                                .addKeyValue("correlationId", metadata.getCorrelationId())
                                .addKeyValue("topic", record.topic())
                                .addKeyValue("partition", record.partition()).addKeyValue("offset", record.offset())
                                .log("ProposalCreated publication confirmed");
                    }));
        } catch (RuntimeException failure) {
            // Metadata lookup and Avro serialization may fail before returning a future.
            return CompletableFuture.failedFuture(failure);
        }
    }
}
