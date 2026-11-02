package io.github.wesleyosantos91.proposta.proposal.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProposalRequest(@NotBlank @Size(max = 64) String productKey) {
}
