package io.github.wesleyosantos91.proposta.proposal.api;

import java.util.concurrent.CompletableFuture;
import jakarta.validation.Valid;
import io.github.wesleyosantos91.proposta.proposal.application.CreateProposalUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProposalController {
    private final CreateProposalUseCase createProposal;

    ProposalController(CreateProposalUseCase createProposal) {
        this.createProposal = createProposal;
    }

    @PostMapping("/proposals")
    CompletableFuture<ResponseEntity<CreateProposalResponse>> create(
            @RequestHeader(name = "Idempotency-Key", defaultValue = "") String key,
            @Valid @RequestBody CreateProposalRequest request) {
        return createProposal.create(key, request.productKey())
                .thenApply(result -> ResponseEntity.accepted().body(new CreateProposalResponse(
                        result.proposalId(), result.journeyId(), result.correlationId(), "ACCEPTED")));
    }
}
