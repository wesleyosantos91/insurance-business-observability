package io.github.wesleyosantos91.cadastro.proposal.application;

import java.time.Instant;

public record ReceivedProposal(
        String eventId, String eventType, String schemaVersion, String journeyType,
        String proposalId, String productKey,
        String journeyId, String rootJourneyId, String correlationId,
        String parentJourneyId, String causationId, Instant occurredAt) {
}
