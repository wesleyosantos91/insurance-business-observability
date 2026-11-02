package io.github.wesleyosantos91.cadastro.proposal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessProposalCreatedUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessProposalCreatedUseCase.class);
    private final JdbcTemplate jdbc;

    public ProcessProposalCreatedUseCase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean process(ReceivedProposal proposal) {
        var fingerprint = fingerprint(proposal);
        int claimed = jdbc.update("""
                INSERT INTO proposal_inbox(event_id,fingerprint) VALUES (?,?)
                ON CONFLICT (event_id) DO NOTHING
                """, proposal.eventId(), fingerprint);
        if (claimed == 0) {
            var existing = jdbc.queryForObject("SELECT fingerprint FROM proposal_inbox WHERE event_id=?",
                    String.class, proposal.eventId());
            if (!fingerprint.equals(existing)) {
                throw new IllegalArgumentException("eventId reused with different event content");
            }
            LOG.atInfo()
                    .addKeyValue("eventId", proposal.eventId()).addKeyValue("proposalId", proposal.proposalId())
                    .log("Duplicate ProposalCreated already processed");
            return false;
        }
        // Durable receipt only: no customer-registration business workflow.
        jdbc.update("""
                INSERT INTO received_proposals(event_id,proposal_id,product_key,journey_id,
                    root_journey_id,correlation_id,occurred_at) VALUES (?,?,?,?,?,?,?)
                """, proposal.eventId(), proposal.proposalId(), proposal.productKey(), proposal.journeyId(),
                proposal.rootJourneyId(), proposal.correlationId(), Timestamp.from(proposal.occurredAt()));
        return true;
    }

    private String fingerprint(ReceivedProposal proposal) {
        var values = new String[]{proposal.eventId(), proposal.eventType(), proposal.schemaVersion(),
                proposal.journeyType(), proposal.proposalId(),
                proposal.productKey(), proposal.journeyId(), proposal.rootJourneyId(), proposal.correlationId(),
                proposal.parentJourneyId(), proposal.causationId(), proposal.occurredAt().toString()};
        var canonical = new StringBuilder();
        for (var value : values) {
            canonical.append(value == null ? "-1:" : value.length() + ":" + value);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
