ALTER TABLE proposal_outbox
    ADD COLUMN traceparent VARCHAR(55),
    ADD COLUMN tracestate VARCHAR(512);
