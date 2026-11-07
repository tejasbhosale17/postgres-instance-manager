package com.tejas.postgresinstancemanager;

import com.tejas.postgresinstancemanager.worker.ManagedPostgresDocker;
import com.tejas.postgresinstancemanager.worker.ManagedPostgresStatus;
import com.tejas.postgresinstancemanager.worker.ProvisionedPostgres;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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

    @Override
    public int start(UUID instanceId, String databasePassword) {
        return requireInstance(instanceId).hostPort();
    }

    @Override
    public void stop(UUID instanceId) {
        requireInstance(instanceId);
    }

    @Override
    public int restart(UUID instanceId, String databasePassword) {
        return requireInstance(instanceId).hostPort();
    }

    @Override
    public void delete(UUID instanceId) {
        instances.remove(instanceId);
    }

    @Override
    public Optional<ManagedPostgresStatus> inspect(UUID instanceId, int currentHostPort) {
        ProvisionedPostgres instance = instances.get(instanceId);
        return instance == null
                ? Optional.empty()
                : Optional.of(new ManagedPostgresStatus(true, instance.hostPort()));
    }

    @Override
    public void createLogicalBackup(UUID instanceId, String databasePassword, Path destination) {
        requireInstance(instanceId);
        try {
            Files.write(destination, "test logical backup".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write fake backup.", exception);
        }
    }

    @Override
    public void restoreLogicalBackup(UUID instanceId, String databasePassword, int hostPort, Path archive) {
        requireInstance(instanceId);
    }

    private ProvisionedPostgres requireInstance(UUID instanceId) {
        ProvisionedPostgres instance = instances.get(instanceId);
        if (instance == null) {
            throw new IllegalStateException("The fake managed instance does not exist.");
        }
        return instance;
    }

    int createdCount() {
        return createdCount.get();
    }
}
