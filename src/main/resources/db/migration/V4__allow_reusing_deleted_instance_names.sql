ALTER TABLE managed_instance
    DROP CONSTRAINT managed_instance_owner_id_display_name_key;

CREATE UNIQUE INDEX managed_instance_owner_active_name_idx
    ON managed_instance (owner_id, display_name)
    WHERE state <> 'DELETED';
