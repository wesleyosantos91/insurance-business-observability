package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalOutboxStore;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalOutboxRecoveryTests {
    private final ProposalOutboxStore store = mock(ProposalOutboxStore.class);
    private final ProposalEventPublisher publisher = mock(ProposalEventPublisher.class);
    private final ProposalTelemetry telemetry = mock(ProposalTelemetry.class);
    private final ProposalOutboxStore.Claim claim = new ProposalOutboxStore.Claim(
            new StoredProposal("proposal", "AUTO", "journey", "correlation", "event",
                    Instant.parse("2026-11-01T12:00:00Z"), false, null, null), UUID.randomUUID(), 3);
    private final ProposalOutboxDispatcher relay = new ProposalOutboxDispatcher(store, publisher,
            new MockEnvironment(), new OutboxProperties(Duration.ofSeconds(30), Duration.ofMillis(20)), telemetry);

    @BeforeEach
    void pendingClaim() {
        when(store.claim(Duration.ofSeconds(30))).thenReturn(Optional.of(claim));
        when(telemetry.relay(claim.proposal())).thenReturn(Observation.NOOP);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlyMarksPublishedAfterPositiveConfirmationAndHonorsFencing(boolean owned) {
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.published(claim)).thenReturn(owned);
        relay.dispatch();
        var order = inOrder(publisher, store);
        order.verify(store).claim(Duration.ofSeconds(30));
        order.verify(publisher).publish(argThat(event ->
                event.getMetadata().getEventId().toString().equals("event")
                && event.getPayload().getProposalId().toString().equals("proposal")));
        order.verify(store).published(claim);
        verify(telemetry).relayOutcome(Observation.NOOP, owned ? "published" : "claim_lost");
        verify(store, never()).retry(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedConfirmationSchedulesSameClaimWithBoundedJitter(boolean owned) {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(new IllegalStateException("unavailable"))));
        when(store.retry(eq(claim), any(), eq("IllegalStateException"))).thenReturn(owned);
        relay.dispatch();
        var delay = ArgumentCaptor.forClass(Duration.class);
        verify(store).retry(eq(claim), delay.capture(), eq("IllegalStateException"));
        assertTrue(delay.getValue().toMillis() >= 2000 && delay.getValue().toMillis() <= 4000);
        verify(store, never()).published(any());
        verify(telemetry).relayOutcome(Observation.NOOP, owned ? "retry" : "claim_lost");
    }

    @Test
    void unconfirmedPublicationTimesOutWithoutMarkingPublished() {
        var confirmation = new CompletableFuture<Void>();
        when(publisher.publish(any())).thenReturn(confirmation);
        relay.dispatch();
        assertFalse(confirmation.isDone(), "HTTP/relay timeout must not claim Kafka cancellation");
        verify(store).retry(eq(claim), any(), eq("TimeoutException"));
        verify(store, never()).published(any());
    }

    @Test
    void interruptedWaitRestoresInterruptAndRetainsEvent() {
        when(publisher.publish(any())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        relay.dispatch();
        assertTrue(Thread.currentThread().isInterrupted());
        verify(store).retry(eq(claim), any(), eq("InterruptedException"));
        verify(store, never()).published(any());
    }

    @Test
    void databaseFailureAfterKafkaAckReliesOnLeaseRecovery() {
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.published(claim)).thenThrow(new IllegalStateException("database down"));
        assertDoesNotThrow(relay::dispatch);
        verify(telemetry).relayOutcome(Observation.NOOP, "database_failure");
        verify(store, never()).retry(any(), any(), any());
    }

    @Test
    void databaseFailureWhileSchedulingRetryReliesOnLeaseRecovery() {
        when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException()));
        when(store.retry(any(), any(), any())).thenThrow(new IllegalStateException("database down"));
        assertDoesNotThrow(relay::dispatch);
        verify(telemetry).relayOutcome(Observation.NOOP, "database_failure");
        verify(store, never()).published(any());
    }

    @Test
    void relayAlwaysClosesItsObservationAfterDispatch() {
        var observation = mock(Observation.class);
        when(telemetry.relay(claim.proposal())).thenReturn(observation);
        when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.published(claim)).thenReturn(true);
        relay.dispatch();
        verify(observation).stop();
        verify(telemetry).relayOutcome(observation, "published");
    }

    @Test
    void noClaimDoesNotSendAndClaimFailureLeavesSchedulerAlive() {
        when(store.claim(any())).thenReturn(Optional.empty());
        relay.dispatch();
        verifyNoInteractions(publisher, telemetry);
        when(store.claim(any())).thenThrow(new IllegalStateException("database down"));
        assertDoesNotThrow(relay::dispatch);
        verifyNoInteractions(publisher, telemetry);
    }
}
