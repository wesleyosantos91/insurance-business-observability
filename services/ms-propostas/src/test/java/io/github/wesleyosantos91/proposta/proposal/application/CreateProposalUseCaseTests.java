package io.github.wesleyosantos91.proposta.proposal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import io.github.wesleyosantos91.proposta.config.ProposalMessagingProperties;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalStore;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateProposalUseCaseTests {
    private final ProposalStore store = mock(ProposalStore.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final ScheduledFuture<?> polling = mock(ScheduledFuture.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void replaysThePersistedResponseWithoutGeneratingNewIds() throws Exception {
        var proposal = proposal(true);
        when(store.register("key", "AUTO", clock.instant())).thenReturn(proposal);
        assertEquals(proposal.response(),
                useCase(Duration.ofSeconds(2)).create("key", "AUTO").get(1, TimeUnit.SECONDS));
        verifyNoInteractions(scheduler);
    }

    @Test
    void waitsForDurablePublicationAndCancelsPollingAfterConfirmation() throws Exception {
        var proposal = proposal(false);
        when(store.register(anyString(), anyString(), any())).thenReturn(proposal);
        doReturn(polling).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        var response = useCase(Duration.ofSeconds(2)).create("key", "AUTO");
        assertFalse(response.isDone());
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), eq(Duration.ofMillis(100)));
        when(store.isPublished("proposal")).thenReturn(true);
        task.getValue().run();
        assertEquals(proposal.response(), response.get(1, TimeUnit.SECONDS));
        verify(polling).cancel(false);
        verify(store).register("key", "AUTO", clock.instant());
    }

    @Test
    void timeoutCancelsOnlyPollingLeavingTheOutboxIntact() {
        when(store.register(anyString(), anyString(), any())).thenReturn(proposal(false));
        doReturn(polling).when(scheduler).scheduleWithFixedDelay(any(Runnable.class), any(Duration.class));
        var result = useCase(Duration.ofMillis(30)).create("key", "AUTO");
        assertInstanceOf(TimeoutException.class,
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
        verify(polling, timeout(1000)).cancel(false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "with space", "ação"})
    void invalidKeysFailBeforePersistence(String key) {
        assertThrows(InvalidIdempotencyKeyException.class,
                () -> useCase(Duration.ofSeconds(1)).create(key, "AUTO"));
        verifyNoInteractions(store, scheduler);
    }

    @Test
    void rejectsOversizedKeysAndPropagatesContentConflict() {
        var useCase = useCase(Duration.ofSeconds(1));
        assertThrows(InvalidIdempotencyKeyException.class, () -> useCase.create("a".repeat(129), "AUTO"));
        when(store.register("same", "HOME", clock.instant())).thenThrow(new IdempotencyConflictException());
        assertThrows(IdempotencyConflictException.class, () -> useCase.create("same", "HOME"));
    }

    private StoredProposal proposal(boolean published) {
        return new StoredProposal("proposal", "AUTO", "journey", "correlation", "event", clock.instant(), published, null, null);
    }

    private CreateProposalUseCase useCase(Duration timeout) {
        return new CreateProposalUseCase(store, clock, scheduler,
                new ProposalMessagingProperties("insurance.proposals.events.v1", timeout));
    }
}
