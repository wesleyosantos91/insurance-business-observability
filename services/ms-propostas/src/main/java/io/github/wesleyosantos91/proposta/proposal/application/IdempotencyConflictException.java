package io.github.wesleyosantos91.proposta.proposal.application;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key already used with different request content");
    }
}
