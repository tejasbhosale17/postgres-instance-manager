ALTER TABLE managed_instance
    DROP CONSTRAINT managed_instance_state_check;

ALTER TABLE managed_instance
    ADD CONSTRAINT managed_instance_state_check CHECK (
        state IN ('PROVISIONING', 'READY', 'STARTING', 'STOPPING', 'STOPPED',
                  'RESTARTING', 'DELETING', 'BACKING_UP', 'RESTORING',
                  'FAILED', 'DELETED')
    );

DROP INDEX operation_claimable_idx;

CREATE INDEX operation_claimable_idx
    ON operation (state, next_attempt_at, created_at, id)
    WHERE operation_type IN ('CREATE_INSTANCE', 'START_INSTANCE', 'STOP_INSTANCE',
                             'RESTART_INSTANCE', 'DELETE_INSTANCE', 'BACKUP', 'RESTORE');

ALTER TABLE operation
    ADD COLUMN requester_id UUID REFERENCES app_user (id);

UPDATE operation
SET requester_id = owner_id;

ALTER TABLE operation
    ALTER COLUMN requester_id SET NOT NULL,
    DROP CONSTRAINT operation_owner_id_idempotency_key_key,
    ADD CONSTRAINT operation_requester_id_idempotency_key_key UNIQUE (requester_id, idempotency_key);

CREATE TABLE backup_artifact (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user (id),
    source_instance_id UUID NOT NULL,
    operation_id UUID NOT NULL UNIQUE REFERENCES operation (id),
    state VARCHAR(20) NOT NULL CHECK (state IN ('PENDING', 'READY', 'FAILED')),
    size_bytes BIGINT CHECK (size_bytes IS NULL OR size_bytes > 0),
    checksum_sha256 CHAR(64),
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (source_instance_id, owner_id) REFERENCES managed_instance (id, owner_id),
    CHECK ((state = 'READY') = (size_bytes IS NOT NULL AND checksum_sha256 IS NOT NULL))
);

CREATE INDEX backup_artifact_owner_created_idx
    ON backup_artifact (owner_id, created_at DESC, id DESC);
