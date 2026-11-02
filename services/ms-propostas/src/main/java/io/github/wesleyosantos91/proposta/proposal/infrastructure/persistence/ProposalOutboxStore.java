package io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ProposalOutboxStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProposalOutboxStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    public Optional<Claim> claim(Duration lease) {
        var token = UUID.randomUUID();
        // The short transaction commits the lease and releases its connection before returning.
        return transaction.execute(status -> jdbc.query("""
                WITH candidate AS (
                    SELECT event_id FROM proposal_outbox
                    WHERE published_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP
                      AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_attempt_at, event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE proposal_outbox o
                SET claim_token=?, claimed_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    attempts=o.attempts+1
                FROM candidate c, proposals p
                WHERE o.event_id=c.event_id AND p.proposal_id=o.proposal_id
                RETURNING p.*, o.event_id, o.occurred_at, o.published_at, o.attempts, o.traceparent, o.tracestate
                """, (row, index) -> new Claim(StoredProposal.read(row, index), token, row.getInt("attempts")),
                token, lease.toMillis()).stream().findFirst());
    }

    public boolean published(Claim claim) {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.update("""
                UPDATE proposal_outbox
                SET published_at=CURRENT_TIMESTAMP, last_failure=NULL, claim_token=NULL, claimed_until=NULL
                WHERE event_id=? AND claim_token=? AND published_at IS NULL
                  AND claimed_until > CURRENT_TIMESTAMP
                """, claim.proposal().eventId(), claim.token()) == 1));
    }

    public boolean retry(Claim claim, Duration delay, String failureType) {
        return Boolean.TRUE.equals(transaction.execute(status -> jdbc.update("""
                UPDATE proposal_outbox
                SET next_attempt_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_failure=?, claim_token=NULL, claimed_until=NULL
                WHERE event_id=? AND claim_token=? AND published_at IS NULL
                  AND claimed_until > CURRENT_TIMESTAMP
                """, delay.toMillis(), failureType, claim.proposal().eventId(), claim.token()) == 1));
    }

    public record Claim(StoredProposal proposal, UUID token, int attempts) {
    }
}
