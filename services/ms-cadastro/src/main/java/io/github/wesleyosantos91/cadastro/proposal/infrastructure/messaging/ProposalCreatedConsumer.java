package io.github.wesleyosantos91.cadastro.proposal.infrastructure.messaging;

import java.time.Instant;
import java.util.Objects;
import io.github.wesleyosantos91.cadastro.proposal.application.ProcessProposalCreatedUseCase;
import io.github.wesleyosantos91.cadastro.proposal.application.ReceivedProposal;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.cadastro.proposal.infrastructure.observability.ProposalTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class ProposalCreatedConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalCreatedConsumer.class);
    private final ProcessProposalCreatedUseCase processProposal;
    private final ProposalTelemetry telemetry;

    public ProposalCreatedConsumer(ProcessProposalCreatedUseCase processProposal, ProposalTelemetry telemetry) {
        this.processProposal = processProposal;
        this.telemetry = telemetry;
    }

    @KafkaListener(id = "proposal-created", idIsGroup = false, topics = "${insurance.kafka.proposals-topic}")
    public void receive(ConsumerRecord<String, ProposalCreated> record, Acknowledgment acknowledgment) {
        var observation = telemetry.consumption();
        var stage = "validation";
        try (var scope = observation.openScope()) {
            var proposal = validate(record);
            telemetry.identifiers(observation, proposal);
            stage = "processing";
            var created = processProposal.process(proposal); // Transactional proxy has committed on return.
            telemetry.committed(observation, proposal, created);
            stage = "acknowledgment";
            acknowledgment.acknowledge();
            LOG.atInfo()
                    .addKeyValue("businessId", proposal.proposalId())
                    .addKeyValue("product_key", proposal.productKey()).addKeyValue("journey_type", "PROPOSAL").addKeyValue("step", "proposal_receive")
                    .addKeyValue("outcome", created ? "success" : "duplicate").addKeyValue("error_code", "none")
                    .addKeyValue("eventType", proposal.eventType())
                    .addKeyValue("eventId", proposal.eventId())
                    .addKeyValue("proposalId", proposal.proposalId())
                    .addKeyValue("journeyId", proposal.journeyId())
                    .addKeyValue("rootJourneyId", proposal.rootJourneyId())
                    .addKeyValue("correlationId", proposal.correlationId())
                    .addKeyValue("topic", record.topic())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset()).log("ProposalCreated processed and acknowledged");
        } catch (RuntimeException failure) {
            observation.error(failure);
            telemetry.failure(observation, stage);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    private ReceivedProposal validate(ConsumerRecord<String, ProposalCreated> record) {
        var event = Objects.requireNonNull(record.value(), "event is required");
        var metadata = Objects.requireNonNull(event.getMetadata(), "metadata is required");
        var payload = Objects.requireNonNull(event.getPayload(), "payload is required");
        var eventType = required(metadata.getEventType(), "eventType");
        if (!"ProposalCreated".equals(eventType)) {
            throw new IllegalArgumentException("Unsupported eventType");
        }
        if (!"PROPOSAL".equals(required(metadata.getJourneyType(), "journeyType"))) {
            throw new IllegalArgumentException("Unexpected journeyType");
        }
        required(metadata.getSchemaVersion(), "schemaVersion");
        var proposalId = required(payload.getProposalId(), "proposalId");
        if (!proposalId.equals(record.key())) {
            throw new IllegalArgumentException("Kafka key must equal proposalId");
        }
        return new ReceivedProposal(
                required(metadata.getEventId(), "eventId"), eventType,
                required(metadata.getSchemaVersion(), "schemaVersion"),
                required(metadata.getJourneyType(), "journeyType"), proposalId,
                required(metadata.getProductKey(), "productKey"),
                required(metadata.getJourneyId(), "journeyId"), required(metadata.getRootJourneyId(), "rootJourneyId"),
                required(metadata.getCorrelationId(), "correlationId"),
                optional(metadata.getParentJourneyId(), "parentJourneyId"),
                optional(metadata.getCausationId(), "causationId"),
                Instant.parse(required(metadata.getOccurredAt(), "occurredAt")));
    }

    private String required(CharSequence value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private String optional(CharSequence value, String field) {
        return value == null ? null : required(value, field);
    }
}
