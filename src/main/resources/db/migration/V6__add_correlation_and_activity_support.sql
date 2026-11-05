ALTER TABLE operation
    ADD COLUMN correlation_id VARCHAR(64);

ALTER TABLE audit_event
    ADD COLUMN correlation_id VARCHAR(64);

CREATE INDEX audit_event_occurred_idx
    ON audit_event (occurred_at DESC, id DESC);
