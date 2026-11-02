package io.github.wesleyosantos91.proposta.proposal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import io.github.wesleyosantos91.proposta.config.ProposalMessagingProperties;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalStore;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

@Service
public class CreateProposalUseCase {
    private final ProposalStore store;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final ProposalMessagingProperties properties;

    public CreateProposalUseCase(ProposalStore store, Clock clock, TaskScheduler scheduler,
                                 ProposalMessagingProperties properties) {
        this.store = store;
        this.clock = clock;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    public CompletableFuture<CreatedProposal> create(String key, String productKey) {
        if (key == null || key.isBlank() || key.length() > 128
                || key.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw new InvalidIdempotencyKeyException();
        }
        var proposal = store.register(key, productKey, Instant.now(clock));
        if (proposal.published()) {
            return CompletableFuture.completedFuture(proposal.response());
        }
        var response = new CompletableFuture<CreatedProposal>();
        var polling = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (store.isPublished(proposal.proposalId())) {
                    response.complete(proposal.response());
                }
            } catch (RuntimeException failure) {
                response.completeExceptionally(failure);
            }
        }, Duration.ofMillis(100));
        response.orTimeout(properties.publicationTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((result, failure) -> polling.cancel(false));
        return response;
    }
}
