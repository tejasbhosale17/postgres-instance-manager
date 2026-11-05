ALTER TABLE operation
    ADD COLUMN previous_instance_state VARCHAR(20),
    ADD COLUMN lease_token UUID;

CREATE INDEX operation_claimable_idx
    ON operation (state, next_attempt_at, created_at, id)
    WHERE operation_type IN ('CREATE_INSTANCE', 'START_INSTANCE', 'STOP_INSTANCE',
                             'RESTART_INSTANCE', 'DELETE_INSTANCE');

CREATE INDEX operation_expired_lease_idx
    ON operation (lease_until)
    WHERE state = 'RUNNING';
