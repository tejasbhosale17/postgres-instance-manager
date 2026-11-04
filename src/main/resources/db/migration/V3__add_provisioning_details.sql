ALTER TABLE managed_instance
    ADD COLUMN database_name VARCHAR(63) NOT NULL DEFAULT 'postgres',
    ADD COLUMN database_username VARCHAR(63) NOT NULL DEFAULT 'postgres',
    ADD COLUMN container_id VARCHAR(128),
    ADD COLUMN volume_name VARCHAR(255),
    ADD COLUMN host_port INTEGER,
    ADD CONSTRAINT managed_instance_host_port_range
        CHECK (host_port IS NULL OR host_port BETWEEN 1 AND 65535),
    ADD CONSTRAINT managed_instance_host_port_unique
        UNIQUE (host_port);
