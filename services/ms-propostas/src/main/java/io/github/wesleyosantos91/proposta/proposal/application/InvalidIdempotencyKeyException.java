package io.github.wesleyosantos91.proposta.proposal.application;

public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must contain 1 to 128 printable ASCII characters");
    }
}
