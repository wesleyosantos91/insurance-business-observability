package io.github.wesleyosantos91.proposta.proposal.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import io.github.wesleyosantos91.proposta.proposal.application.CreatedProposal;

public record StoredProposal(String proposalId, String productKey, String journeyId,
                             String correlationId, String eventId, Instant occurredAt, boolean published, String traceparent, String tracestate) {
    public CreatedProposal response() {
        return new CreatedProposal(proposalId, journeyId, correlationId);
    }

    public static StoredProposal read(ResultSet row, int index) throws SQLException {
        return new StoredProposal(row.getString("proposal_id"), row.getString("product_key"),
                row.getString("journey_id"), row.getString("correlation_id"),
                row.getString("event_id"), row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("published_at") != null, row.getString("traceparent"), row.getString("tracestate"));
    }
}
