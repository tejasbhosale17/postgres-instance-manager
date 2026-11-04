package com.tejas.postgresinstancemanager.worker;

import java.util.UUID;

public interface ManagedPostgresDocker {

    ProvisionedPostgres provision(UUID instanceId, String databasePassword);
}
