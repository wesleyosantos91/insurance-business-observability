ALTER TABLE proposal_outbox
    ADD COLUMN claim_token UUID,
    ADD COLUMN claimed_until TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT proposal_outbox_claim_pair CHECK (
        (claim_token IS NULL AND claimed_until IS NULL)
        OR (claim_token IS NOT NULL AND claimed_until IS NOT NULL)
    ),
    ADD CONSTRAINT proposal_outbox_published_unclaimed CHECK (
        published_at IS NULL OR claim_token IS NULL
    );
