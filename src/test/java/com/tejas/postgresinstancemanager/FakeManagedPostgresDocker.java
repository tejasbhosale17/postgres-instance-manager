package com.tejas.postgresinstancemanager;

import com.tejas.postgresinstancemanager.worker.ManagedPostgresDocker;
import com.tejas.postgresinstancemanager.worker.ProvisionedPostgres;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeManagedPostgresDocker implements ManagedPostgresDocker {

    private final ConcurrentHashMap<UUID, ProvisionedPostgres> instances = new ConcurrentHashMap<>();
    private final AtomicInteger createdCount = new AtomicInteger();

    @Override
    public ProvisionedPostgres provision(UUID instanceId, String databasePassword) {
        return instances.computeIfAbsent(instanceId, id -> {
            createdCount.incrementAndGet();
            return new ProvisionedPostgres(
                    "test-container-" + id,
                    "test-volume-" + id,
                    15432);
        });
    }

    int createdCount() {
        return createdCount.get();
    }
}
