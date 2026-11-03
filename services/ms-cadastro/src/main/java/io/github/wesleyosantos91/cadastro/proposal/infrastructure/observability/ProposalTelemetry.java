package io.github.wesleyosantos91.cadastro.proposal.infrastructure.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import io.github.wesleyosantos91.cadastro.proposal.application.ReceivedProposal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

@Component
public class ProposalTelemetry {
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Clock clock;

    public ProposalTelemetry(ObservationRegistry observations, MeterRegistry meters, Clock clock) {
        this.observations = observations;
        this.meters = meters;
        this.clock = clock;
    }

    public Observation consumption() {
        return Observation.createNotStarted("insurance.proposal.consume", observations)
                .lowCardinalityKeyValue("journey_type", "PROPOSAL")
                .lowCardinalityKeyValue("eventType", "ProposalCreated")
                .lowCardinalityKeyValue("outcome", "pending").lowCardinalityKeyValue("error_code", "none")
                .highCardinalityKeyValue("stepKey", "proposal_receive").start();
    }

    public void identifiers(Observation observation, ReceivedProposal proposal) {
        observation.highCardinalityKeyValue("businessId", proposal.proposalId())
                .highCardinalityKeyValue("eventId", proposal.eventId())
                .highCardinalityKeyValue("proposalId", proposal.proposalId())
                .highCardinalityKeyValue("journeyId", proposal.journeyId())
                .highCardinalityKeyValue("rootJourneyId", proposal.rootJourneyId())
                .highCardinalityKeyValue("correlationId", proposal.correlationId())
                .highCardinalityKeyValue("productKey", proposal.productKey());
    }

    public void committed(Observation observation, ReceivedProposal proposal, boolean created) {
        var outcome = created ? "new" : "duplicate";
        observation.lowCardinalityKeyValue("outcome", outcome).highCardinalityKeyValue("status", outcome);
        meters.counter("insurance.proposal.receipts", "outcome", outcome).increment();
        if (created) {
            meters.counter("insurance.business.transitions", "journey_type", "PROPOSAL",
                    "step", "proposal_receive", "outcome", "success", "error_code", "none").increment();
        } else {
            meters.counter("insurance.inbox.duplicates", "journey_type", "PROPOSAL",
                    "step", "proposal_receive").increment();
        }
        var elapsed = Duration.between(proposal.occurredAt(), Instant.now(clock));
        if (created && !elapsed.isNegative()) {
            meters.timer("insurance.proposal.delivery.latency").record(elapsed);
        }
    }

    public void failure(Observation observation, String stage) {
        var code = switch (stage) {
            case "processing" -> "processing_failure";
            case "acknowledgment" -> "acknowledgment_failure";
            default -> "invalid_event";
        };
        observation.lowCardinalityKeyValue("outcome", "technical_failure")
                .lowCardinalityKeyValue("error_code", code).highCardinalityKeyValue("status", "technical_failure");
        meters.counter("insurance.proposal.consume.failures", "stage", stage).increment();
    }
}
