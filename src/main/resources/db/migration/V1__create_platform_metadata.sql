CREATE TABLE platform_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value VARCHAR(255) NOT NULL
);

INSERT INTO platform_metadata (metadata_key, metadata_value)
VALUES ('schema-purpose', 'postgres-instance-management-control-plane');
