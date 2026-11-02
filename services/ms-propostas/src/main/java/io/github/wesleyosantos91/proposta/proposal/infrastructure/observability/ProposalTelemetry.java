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
                .lowCardinalityKeyValue("journeyType", "PROPOSAL").start();
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
        observation.lowCardinalityKeyValue("outcome", outcome);
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
                .lowCardinalityKeyValue("journeyType", "PROPOSAL")
                .lowCardinalityKeyValue("eventType", "ProposalCreated");
        identifiers(observation, proposal);
        return observation.start();
    }

    public void relayOutcome(Observation observation, String outcome) {
        observation.lowCardinalityKeyValue("outcome", outcome);
        meters.counter("insurance.outbox.dispatches", "outcome", outcome).increment();
    }

    private void identifiers(Observation observation, StoredProposal proposal) {
        observation.highCardinalityKeyValue("eventId", proposal.eventId())
                .highCardinalityKeyValue("proposalId", proposal.proposalId())
                .highCardinalityKeyValue("journeyId", proposal.journeyId())
                .highCardinalityKeyValue("rootJourneyId", proposal.journeyId())
                .highCardinalityKeyValue("correlationId", proposal.correlationId())
                .highCardinalityKeyValue("productKey", proposal.productKey());
    }
}
