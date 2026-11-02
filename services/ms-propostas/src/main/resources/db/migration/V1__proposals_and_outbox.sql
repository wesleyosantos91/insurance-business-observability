CREATE TABLE proposals (
    proposal_id VARCHAR(36) PRIMARY KEY,
    idempotency_key_hash CHAR(64) NOT NULL UNIQUE,
    product_key VARCHAR(64) NOT NULL,
    journey_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL
);
CREATE TABLE proposal_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    proposal_id VARCHAR(36) NOT NULL UNIQUE REFERENCES proposals(proposal_id),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_failure VARCHAR(128)
);
CREATE INDEX proposal_outbox_pending ON proposal_outbox(next_attempt_at) WHERE published_at IS NULL;
