package io.github.wesleyosantos91.proposta.proposal.infrastructure.observability;

import java.time.Instant;
import java.util.Map;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalTraceContextTests {
    @Test
    void capturesActiveW3cContextAndRestoresOutboxCarrierAndBusinessAttributes() {
        var tracer = mock(Tracer.class);
        var propagator = mock(Propagator.class);
        var span = mock(Span.class);
        var trace = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(trace);
        doAnswer(invocation -> {
            Map<String, String> carrier = invocation.getArgument(1);
            Propagator.Setter<Map<String, String>> setter = invocation.getArgument(2);
            setter.set(carrier, "traceparent", "parent-value");
            setter.set(carrier, "tracestate", "vendor=value");
            return null;
        }).when(propagator).inject(eq(trace), any(), any());
        var registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(
                new io.micrometer.core.instrument.observation.DefaultMeterObservationHandler(new SimpleMeterRegistry()));
        var telemetry = new ProposalTelemetry(registry, new SimpleMeterRegistry(), tracer, propagator);
        assertEquals(Map.of("traceparent", "parent-value", "tracestate", "vendor=value"), telemetry.traceHeaders());
        var observation = telemetry.relay(new StoredProposal("p", "AUTO", "j", "c", "e",
                Instant.now(), false, "parent-value", "vendor=value"));
        try {
            var context = (ReceiverContext<Map<String, String>>) observation.getContext();
            assertEquals("parent-value", context.getCarrier().get("traceparent"));
            assertEquals("vendor=value", context.getCarrier().get("tracestate"));
            assertEquals("p", context.getHighCardinalityKeyValue("proposalId").getValue());
            assertEquals("j", context.getHighCardinalityKeyValue("journeyId").getValue());
        } finally {
            observation.stop();
        }
    }
}
