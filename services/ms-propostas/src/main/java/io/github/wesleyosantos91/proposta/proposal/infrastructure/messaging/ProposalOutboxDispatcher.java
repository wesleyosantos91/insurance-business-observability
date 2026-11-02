package io.github.wesleyosantos91.proposta.proposal.infrastructure.messaging;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import io.github.wesleyosantos91.insurance.events.proposals.v1.EventMetadata;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreated;
import io.github.wesleyosantos91.insurance.events.proposals.v1.ProposalCreatedPayload;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalOutboxStore;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.ProposalOutboxStore.Claim;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence.StoredProposal;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import io.micrometer.observation.Observation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProposalOutboxDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalOutboxDispatcher.class);
    private final ProposalOutboxStore store;
    private final ProposalEventPublisher publisher;
    private final Environment environment;
    private final OutboxProperties properties;
    private final ProposalTelemetry telemetry;

    public ProposalOutboxDispatcher(ProposalOutboxStore store, ProposalEventPublisher publisher,
                                   Environment environment, OutboxProperties properties, ProposalTelemetry telemetry) {
        this.store = store;
        this.publisher = publisher;
        this.environment = environment;
        this.properties = properties;
        this.telemetry = telemetry;
    }

    @Scheduled(fixedDelayString = "${insurance.outbox.delay:200}", initialDelay = 1000)
    public void dispatch() {
        if (!environment.getProperty("insurance.persistence.initialize", Boolean.class, true)) {
            return;
        }
        try {
            store.claim(properties.leaseDuration()).ifPresent(this::publish);
        } catch (RuntimeException failure) {
            LOG.atError()
                    .addKeyValue("failureType", failure.getClass().getSimpleName())
                    .log("Outbox claim failed; pending events remain eligible for retry");
        }
    }

    private void publish(Claim claim) {
        var observation = telemetry.relay(claim.proposal());
        try (var scope = observation.openScope()) {
            publish(claim, observation);
        } finally {
            observation.stop();
        }
    }

    private void publish(Claim claim, Observation observation) {
        try {
            // Only the scheduler waits; no database transaction or connection is held here.
            publisher.publish(event(claim.proposal()))
                    .get(properties.publicationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception failure) {
            observation.error(failure);
            try {
                var delay = retryDelayMillis(claim.attempts());
                var updated = store.retry(claim, Duration.ofMillis(delay), failureType(failure));
                telemetry.relayOutcome(observation, updated ? "retry" : "claim_lost");
                LOG.atWarn()
                        .addKeyValue("eventId", claim.proposal().eventId())
                        .addKeyValue("attempt", claim.attempts()).addKeyValue("retryDelayMillis", delay)
                        .addKeyValue("claimUpdated", updated).addKeyValue("failureType", failureType(failure))
                        .log("Outbox publication unconfirmed; same event retained for retry");
            } catch (RuntimeException databaseFailure) {
                observation.error(databaseFailure);
                telemetry.relayOutcome(observation, "database_failure");
                logRecovery(claim, databaseFailure);
            } finally {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            return;
        }
        try {
            if (!store.published(claim)) {
                telemetry.relayOutcome(observation, "claim_lost");
                LOG.atWarn()
                        .addKeyValue("eventId", claim.proposal().eventId())
                        .log("Outbox claim expired or superseded after Kafka confirmation; redelivery possible");
            } else {
                telemetry.relayOutcome(observation, "published");
            }
        } catch (RuntimeException databaseFailure) {
            observation.error(databaseFailure);
            telemetry.relayOutcome(observation, "database_failure");
            // Kafka may already contain the event. The durable lease expires; retry keeps its eventId.
            logRecovery(claim, databaseFailure);
        }
    }

    private static void logRecovery(Claim claim, RuntimeException failure) {
        LOG.atError()
                .addKeyValue("eventId", claim.proposal().eventId()).addKeyValue("attempt", claim.attempts())
                .addKeyValue("failureType", failure.getClass().getSimpleName())
                .log("Outbox state update failed; lease expiry will recover the same event");
    }

    static long retryDelayMillis(int attempts) {
        var ceiling = retryCeilingMillis(attempts);
        // Equal jitter: retain half of the exponential delay and randomize the other half.
        return ThreadLocalRandom.current().nextLong(ceiling / 2, ceiling + 1);
    }

    static long retryCeilingMillis(int attempts) {
        return Math.min(60_000, 1_000L << Math.min(6, Math.max(0, attempts - 1)));
    }

    private static String failureType(Exception failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    static ProposalCreated event(StoredProposal proposal) {
        var metadata = new EventMetadata(proposal.eventId(), "ProposalCreated", "1.0.0",
                proposal.occurredAt().toString(), proposal.productKey(), "PROPOSAL",
                proposal.journeyId(), proposal.journeyId(), proposal.correlationId(), null, null);
        return new ProposalCreated(metadata, new ProposalCreatedPayload(proposal.proposalId()));
    }
}
