package com.tejas.postgresinstancemanager.worker;

import java.util.UUID;

public interface ManagedPostgresDocker {

    ProvisionedPostgres provision(UUID instanceId, String databasePassword);

    int start(UUID instanceId, String databasePassword);

    void stop(UUID instanceId);

    int restart(UUID instanceId, String databasePassword);

    void delete(UUID instanceId);
}
