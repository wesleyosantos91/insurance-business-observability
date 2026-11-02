CREATE TABLE proposal_inbox (
    event_id VARCHAR(36) PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE received_proposals (
    event_id VARCHAR(36) PRIMARY KEY REFERENCES proposal_inbox(event_id),
    proposal_id VARCHAR(36) NOT NULL UNIQUE,
    product_key VARCHAR(64) NOT NULL,
    journey_id VARCHAR(36) NOT NULL,
    root_journey_id VARCHAR(36) NOT NULL,
    correlation_id VARCHAR(36) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
