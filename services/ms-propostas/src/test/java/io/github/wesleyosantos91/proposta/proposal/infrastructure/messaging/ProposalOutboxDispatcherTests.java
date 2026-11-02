package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.time.Instant;
import java.time.Duration;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalOutboxStore;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import org.junit.jupiter.api.Test;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalOutboxDispatcherTests {
    @Test
    void rebuildingAnEventPreservesIdentityAndMetadata() {
        var proposal = new StoredProposal("proposal", "AUTO", "journey",
                "correlation", "event", Instant.parse("2026-11-01T12:00:00Z"), false, null, null);
        var first = ProposalOutboxDispatcher.event(proposal);
        assertEquals(first, ProposalOutboxDispatcher.event(proposal));
        assertEquals("event", first.getMetadata().getEventId().toString());
        assertEquals("journey", first.getMetadata().getRootJourneyId().toString());
        assertEquals("1.0.0", first.getMetadata().getSchemaVersion().toString());
        assertEquals("ProposalCreated", first.getMetadata().getEventType().toString());
        assertEquals("PROPOSAL", first.getMetadata().getJourneyType().toString());
        assertEquals(proposal.occurredAt().toString(), first.getMetadata().getOccurredAt().toString());
        assertNull(first.getMetadata().getParentJourneyId());
        assertNull(first.getMetadata().getCausationId());
    }

    @Test
    void aotTrainingDoesNotAccessPersistenceOrKafka() {
        var store = mock(ProposalOutboxStore.class);
        var publisher = mock(ProposalEventPublisher.class);

        new ProposalOutboxDispatcher(store, publisher,
                new MockEnvironment().withProperty("insurance.persistence.initialize", "false"),
                new OutboxProperties(Duration.ofSeconds(30), Duration.ofSeconds(6)), mock(ProposalTelemetry.class)).dispatch();
        verifyNoInteractions(store, publisher);
    }

    @Test
    void retriesBackOffWithoutWrappingOrExceedingOneMinute() {
        assertEquals(1_000, ProposalOutboxDispatcher.retryCeilingMillis(1));
        assertEquals(2_000, ProposalOutboxDispatcher.retryCeilingMillis(2));
        assertEquals(4_000, ProposalOutboxDispatcher.retryCeilingMillis(3));
        assertEquals(32_000, ProposalOutboxDispatcher.retryCeilingMillis(6));
        assertEquals(60_000, ProposalOutboxDispatcher.retryCeilingMillis(7));
        assertEquals(60_000, ProposalOutboxDispatcher.retryCeilingMillis(Integer.MAX_VALUE));
    }

    @Test
    void leaseMustOutliveThePublicationWait() {
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(Duration.ofSeconds(6), Duration.ofSeconds(6)));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboxProperties(Duration.ofSeconds(30), Duration.ZERO));
    }

    @Test
    void equalJitterRemainsWithinHalfAndAllOfTheCappedExponentialDelay() {
        for (int attempt : new int[] { 1, 2, 3, 6, 7, 20, Integer.MAX_VALUE }) {
            var ceiling = ProposalOutboxDispatcher.retryCeilingMillis(attempt);
            for (int sample = 0; sample < 50; sample++) {
                var delay = ProposalOutboxDispatcher.retryDelayMillis(attempt);
                assertTrue(delay >= ceiling / 2 && delay <= ceiling);
            }
        }
    }
}
