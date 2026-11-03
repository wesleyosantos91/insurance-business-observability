package io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import io.github.wesleyosantos91.proposta.proposal.application.IdempotencyConflictException;
import io.github.wesleyosantos91.proposta.proposal.infrastructure.observability.ProposalTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ProposalStore {
    private static final Logger LOG = LoggerFactory.getLogger(ProposalStore.class);
    public static final String SELECT = """
            SELECT p.*, o.event_id, o.occurred_at, o.published_at, o.traceparent, o.tracestate
            FROM proposals p JOIN proposal_outbox o ON o.proposal_id=p.proposal_id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ProposalTelemetry telemetry;

    public ProposalStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ProposalTelemetry telemetry) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.telemetry = telemetry;
    }

    public StoredProposal register(String key, String productKey, Instant now) {
        var observation = telemetry.registration();
        try (var scope = observation.openScope()) {
            var trace = telemetry.traceHeaders();
            var result = transaction.execute(status -> {
                var hash = keyHash(key);
                var proposalId = UUID.randomUUID().toString();
                var inserted = jdbc.update("""
                        INSERT INTO proposals(proposal_id,idempotency_key_hash,product_key,journey_id,correlation_id)
                        VALUES (?,?,?,?,?) ON CONFLICT (idempotency_key_hash) DO NOTHING
                        """, proposalId, hash, productKey, UUID.randomUUID().toString(), UUID.randomUUID().toString());
                if (inserted == 1) {
                    jdbc.update("""
                            INSERT INTO proposal_outbox(event_id,proposal_id,occurred_at,traceparent,tracestate)
                            VALUES (?,?,?,?,?)
                            """, UUID.randomUUID().toString(), proposalId, Timestamp.from(now),
                            trace.get("traceparent"), trace.get("tracestate"));
                }
                var existing = jdbc.queryForObject(SELECT + " WHERE p.idempotency_key_hash=?",
                        StoredProposal::read, hash);
                if (existing == null || !existing.productKey().equals(productKey)) {
                    throw new IdempotencyConflictException();
                }
                return new Registration(existing, inserted == 1);
            });
            // Counts committed registrations, never statements later rolled back.
            telemetry.registered(observation, result.proposal(), result.created());
            var proposal = result.proposal();
            LOG.atInfo()
                    .addKeyValue("eventId", proposal.eventId()).addKeyValue("proposalId", proposal.proposalId())
                    .addKeyValue("journeyId", proposal.journeyId()).addKeyValue("rootJourneyId", proposal.journeyId())
                    .addKeyValue("correlationId", proposal.correlationId())
                    .addKeyValue("eventType", "ProposalCreated").addKeyValue("created", result.created())
                    .addKeyValue("businessId", proposal.proposalId())
                    .addKeyValue("product_key", proposal.productKey()).addKeyValue("journey_type", "PROPOSAL").addKeyValue("step", "proposal_register")
                    .addKeyValue("outcome", result.created() ? "success" : "replay").addKeyValue("error_code", "none")
                    .log("Proposal intent durably registered");
            return proposal;
        } catch (RuntimeException failure) {
            telemetry.registrationFailed(observation, failure);
            throw failure;
        } finally {
            observation.stop();
        }
    }

    public boolean isPublished(String proposalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT published_at IS NOT NULL FROM proposal_outbox WHERE proposal_id=?",
                Boolean.class, proposalId));
    }

    private String keyHash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("POST:/proposals:" + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record Registration(StoredProposal proposal, boolean created) {
    }
}
