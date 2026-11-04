CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMINISTRATOR', 'OPERATOR', 'VIEWER')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE managed_instance (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user (id),
    display_name VARCHAR(80) NOT NULL,
    postgres_version VARCHAR(20) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (
        state IN ('PROVISIONING', 'READY', 'STARTING', 'STOPPING', 'STOPPED',
                  'RESTARTING', 'DELETING', 'FAILED', 'DELETED')
    ),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_id, display_name),
    UNIQUE (id, owner_id)
);

CREATE TABLE operation (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user (id),
    instance_id UUID,
    operation_type VARCHAR(30) NOT NULL CHECK (
        operation_type IN ('CREATE_INSTANCE', 'START_INSTANCE', 'STOP_INSTANCE',
                           'RESTART_INSTANCE', 'DELETE_INSTANCE', 'BACKUP', 'RESTORE')
    ),
    state VARCHAR(20) NOT NULL CHECK (
        state IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED')
    ),
    payload JSONB NOT NULL DEFAULT '{}'::JSONB,
    idempotency_key VARCHAR(255),
    request_hash CHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    worker_id VARCHAR(100),
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (instance_id, owner_id) REFERENCES managed_instance (id, owner_id),
    UNIQUE (owner_id, idempotency_key),
    CHECK ((idempotency_key IS NULL) = (request_hash IS NULL))
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor_id UUID REFERENCES app_user (id) ON DELETE SET NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::JSONB,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX managed_instance_owner_created_idx
    ON managed_instance (owner_id, created_at DESC);
CREATE INDEX operation_owner_created_idx
    ON operation (owner_id, created_at DESC);
