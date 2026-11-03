package io.github.wesleyosantos91.proposta.proposal.infrastructure.observability;

import java.util.HashMap;
import java.util.Map;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.Kind;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

@Component
public class ProposalTelemetry {
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final Propagator propagator;

    public ProposalTelemetry(ObservationRegistry observations, MeterRegistry meters,
                             Tracer tracer, Propagator propagator) {
        this.observations = observations;
        this.meters = meters;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public Observation registration() {
        return Observation.createNotStarted("insurance.proposal.register", observations)
                .lowCardinalityKeyValue("journey_type", "PROPOSAL")
                .lowCardinalityKeyValue("outcome", "pending").lowCardinalityKeyValue("error_code", "none")
                .highCardinalityKeyValue("stepKey", "proposal_register").start();
    }

    public Map<String, String> traceHeaders() {
        var headers = new HashMap<String, String>();
        var span = tracer.currentSpan();
        if (span != null) {
            propagator.inject(span.context(), headers, Map::put);
        }
        return headers;
    }

    public void registered(Observation observation, StoredProposal proposal, boolean created) {
        identifiers(observation, proposal);
        var outcome = created ? "created" : "replayed";
        observation.lowCardinalityKeyValue("outcome", outcome).highCardinalityKeyValue("status", outcome);
        meters.counter("insurance.proposal.registrations", "outcome", outcome).increment();
        if (created) {
            meters.counter("insurance.business.transitions", "journey_type", "PROPOSAL",
                    "step", "proposal_register", "outcome", "success", "error_code", "none").increment();
        }
    }

    public void registrationFailed(Observation observation, RuntimeException failure) {
        boolean conflict = failure instanceof io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException;
        var outcome = conflict ? "rejected" : "technical_failure";
        var code = conflict ? "idempotency_conflict" : "registration_failure";
        observation.error(failure).lowCardinalityKeyValue("outcome", outcome)
                .lowCardinalityKeyValue("error_code", code).highCardinalityKeyValue("status", outcome);
        meters.counter("insurance.proposal.registrations", "outcome", outcome).increment();
    }

    public Observation relay(StoredProposal proposal) {
        var headers = new HashMap<String, String>();
        if (proposal.traceparent() != null) {
            headers.put("traceparent", proposal.traceparent());
        }
        if (proposal.tracestate() != null) {
            headers.put("tracestate", proposal.tracestate());
        }
        var context = new ReceiverContext<Map<String, String>>(Map::get, Kind.CONSUMER);
        context.setCarrier(headers);
        var observation = Observation.createNotStarted("insurance.outbox.publish", () -> context, observations)
                .parentObservation(null)
                .lowCardinalityKeyValue("journey_type", "PROPOSAL")
                .lowCardinalityKeyValue("eventType", "ProposalCreated");
        observation.lowCardinalityKeyValue("outcome", "pending").lowCardinalityKeyValue("error_code", "none")
                .highCardinalityKeyValue("stepKey", "outbox_publish");
        identifiers(observation, proposal);
        meters.counter("insurance.outbox.publish.attempts", "journey_type", "PROPOSAL",
                "step", "outbox_publish").increment();
        return observation.start();
    }

    public void relayOutcome(Observation observation, String outcome) {
        var code = switch (outcome) {
            case "retry" -> "publication_unconfirmed";
            case "claim_lost" -> "lease_lost";
            case "database_failure" -> "persistence_failure";
            default -> "none";
        };
        observation.lowCardinalityKeyValue("outcome", outcome).lowCardinalityKeyValue("error_code", code)
                .highCardinalityKeyValue("status", outcome);
        meters.counter("insurance.outbox.dispatches", "outcome", outcome).increment();
    }

    private void identifiers(Observation observation, StoredProposal proposal) {
        observation.highCardinalityKeyValue("businessId", proposal.proposalId())
                .highCardinalityKeyValue("eventId", proposal.eventId())
                .highCardinalityKeyValue("proposalId", proposal.proposalId())
                .highCardinalityKeyValue("journeyId", proposal.journeyId())
                .highCardinalityKeyValue("rootJourneyId", proposal.journeyId())
                .highCardinalityKeyValue("correlationId", proposal.correlationId())
                .highCardinalityKeyValue("productKey", proposal.productKey());
    }
}
