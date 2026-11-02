package io.github.wesleyosantos91.proposta.proposal.api;

public record CreateProposalResponse(
        String proposalId, String journeyId, String correlationId, String status) {
}
