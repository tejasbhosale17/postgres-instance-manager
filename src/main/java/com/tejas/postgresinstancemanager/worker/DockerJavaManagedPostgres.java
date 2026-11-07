package com.tejas.postgresinstancemanager.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

@Component
public class DockerJavaManagedPostgres implements ManagedPostgresDocker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerJavaManagedPostgres.class);
    public static final String PLATFORM_LABEL = "pim.platform";
    public static final String INSTANCE_LABEL = "pim.instance-id";
    public static final String RESOURCE_LABEL = "pim.resource-kind";
    public static final String PLATFORM_VALUE = "postgres-instance-manager";
    public static final String POSTGRES_IMAGE = "postgres:17.6";
    private static final String POSTGRES_DATA_PATH = "/var/lib/postgresql/data";
    private static final ExposedPort POSTGRES_PORT = ExposedPort.tcp(5432);
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);

    private final DockerClient dockerClient;

    public DockerJavaManagedPostgres(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public ProvisionedPostgres provision(UUID instanceId, String databasePassword) {
        String volumeName = "pim-pg-data-" + instanceId;
        try {
            ensureVolume(instanceId, volumeName);
            String containerId = findOwnedContainer(instanceId);
            if (containerId == null) {
                ensureImage();
                containerId = createContainer(instanceId, volumeName, databasePassword);
                dockerClient.startContainerCmd(containerId).exec();
            } else {
                ensureExistingContainerIsManaged(containerId, instanceId, volumeName);
                InspectContainerResponse existing = dockerClient.inspectContainerCmd(containerId).exec();
                if (!Boolean.TRUE.equals(existing.getState().getRunning())) {
                    dockerClient.startContainerCmd(containerId).exec();
                }
            }
            int hostPort = getHostPort(containerId);
            waitUntilReady(hostPort, databasePassword);
            return new ProvisionedPostgres(containerId, volumeName, hostPort);
        } catch (PostgresProvisioningException exception) {
            throw exception;
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        } catch (SQLException exception) {
            throw new PostgresProvisioningException("POSTGRES_READINESS_FAILED", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PostgresProvisioningException("PROVISIONING_INTERRUPTED", exception);
        }
    }

    @Override
    public int start(UUID instanceId, String databasePassword) {
        try {
            String containerId = requireManagedContainer(instanceId);
            InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
            if (!Boolean.TRUE.equals(container.getState().getRunning())) {
                dockerClient.startContainerCmd(containerId).exec();
            }
            int hostPort = getHostPort(containerId);
            waitUntilReady(hostPort, databasePassword);
            return hostPort;
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        } catch (SQLException exception) {
            throw new PostgresProvisioningException("POSTGRES_READINESS_FAILED", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PostgresProvisioningException("PROVISIONING_INTERRUPTED", exception);
        }
    }

    @Override
    public void stop(UUID instanceId) {
        try {
            String containerId = requireManagedContainer(instanceId);
            InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(container.getState().getRunning())) {
                dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            }
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        }
    }

    @Override
    public int restart(UUID instanceId, String databasePassword) {
        try {
            String containerId = requireManagedContainer(instanceId);
            InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
            if (Boolean.TRUE.equals(container.getState().getRunning())) {
                dockerClient.restartContainerCmd(containerId).withTimeout(10).exec();
            } else {
                dockerClient.startContainerCmd(containerId).exec();
            }
            int hostPort = getHostPort(containerId);
            waitUntilReady(hostPort, databasePassword);
            return hostPort;
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        } catch (SQLException exception) {
            throw new PostgresProvisioningException("POSTGRES_READINESS_FAILED", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PostgresProvisioningException("PROVISIONING_INTERRUPTED", exception);
        }
    }

    @Override
    public void delete(UUID instanceId) {
        try {
            String containerId = findOwnedContainer(instanceId);
            if (containerId != null) {
                ensureExistingContainerIsManaged(containerId, instanceId, "pim-pg-data-" + instanceId);
                InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
                if (Boolean.TRUE.equals(container.getState().getRunning())) {
                    dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
                }
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            }
            removeOwnedVolume(instanceId);
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        }
    }

    @Override
    public Optional<ManagedPostgresStatus> inspect(UUID instanceId, int currentHostPort) {
        try {
            String containerId = findOwnedContainer(instanceId);
            if (containerId == null) {
                return Optional.empty();
            }
            ensureExistingContainerIsManaged(containerId, instanceId, "pim-pg-data-" + instanceId);
            InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
            boolean running = Boolean.TRUE.equals(container.getState().getRunning());
            return Optional.of(new ManagedPostgresStatus(
                    running,
                    running ? getHostPort(containerId) : currentHostPort));
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        }
    }

    @Override
    public void createLogicalBackup(UUID instanceId, String databasePassword, Path destination) {
        try {
            String containerId = requireManagedContainer(instanceId);
            requireRunningContainer(containerId);
            try (OutputStream output = Files.newOutputStream(destination)) {
                runContainerCommand(
                        containerId,
                        List.of("pg_dump", "--format=custom", "--no-owner", "--no-acl",
                                "--username=postgres", "--host=127.0.0.1", "--dbname=postgres"),
                        databasePassword,
                        null,
                        output,
                        "BACKUP_COMMAND_FAILED");
            }
            try (InputStream archive = Files.newInputStream(destination)) {
                runContainerCommand(
                        containerId,
                        List.of("pg_restore", "--list"),
                        null,
                        archive,
                        null,
                        "BACKUP_ARCHIVE_INVALID");
            }
        } catch (PostgresProvisioningException exception) {
            throw exception;
        } catch (DockerException exception) {
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        } catch (IOException exception) {
            throw new PostgresProvisioningException("BACKUP_STORAGE_ERROR", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PostgresProvisioningException("BACKUP_INTERRUPTED", exception);
        }
    }

    @Override
    public void restoreLogicalBackup(
            UUID instanceId,
            String databasePassword,
            int hostPort,
            Path archivePath) {
        String containerId = requireManagedContainer(instanceId);
        requireRunningContainer(containerId);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String restoreDatabase = "pim_restore_" + suffix;
        String previousDatabase = "pim_previous_" + suffix;
        boolean stagingCreated = false;
        boolean sourceConnectionsDisabled = false;
        boolean sourceRenamed = false;
        try {
            try (InputStream archive = Files.newInputStream(archivePath)) {
                runContainerCommand(
                        containerId,
                        List.of("pg_restore", "--list"),
                        null,
                        archive,
                        null,
                        "BACKUP_ARCHIVE_INVALID");
            }
            try (Connection connection = adminConnection(hostPort, databasePassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + restoreDatabase + "\" TEMPLATE template0");
                stagingCreated = true;
            }
            try (InputStream archive = Files.newInputStream(archivePath)) {
                runContainerCommand(
                        containerId,
                        List.of("pg_restore", "--exit-on-error", "--single-transaction",
                                "--no-owner", "--no-acl", "--username=postgres", "--host=127.0.0.1",
                                "--dbname=" + restoreDatabase),
                        databasePassword,
                        archive,
                        null,
                        "RESTORE_COMMAND_FAILED");
            }
            verifyRestoredDatabase(hostPort, databasePassword, restoreDatabase);
            try (Connection connection = adminConnection(hostPort, databasePassword);
                 Statement statement = connection.createStatement()) {
                statement.execute("ALTER DATABASE postgres WITH ALLOW_CONNECTIONS false");
                sourceConnectionsDisabled = true;
                statement.execute("""
                        SELECT pg_terminate_backend(pid)
                        FROM pg_stat_activity
                        WHERE datname = 'postgres' AND pid <> pg_backend_pid()
                        """);
                statement.execute("ALTER DATABASE postgres RENAME TO \"" + previousDatabase + "\"");
                sourceRenamed = true;
                sourceConnectionsDisabled = false;
                statement.execute("ALTER DATABASE \"" + restoreDatabase + "\" RENAME TO postgres");
                stagingCreated = false;
                sourceRenamed = false;
                try {
                    statement.execute("DROP DATABASE \"" + previousDatabase + "\" WITH (FORCE)");
                } catch (SQLException cleanupFailure) {
                    org.slf4j.LoggerFactory.getLogger(DockerJavaManagedPostgres.class)
                            .warn("Restore completed but the previous database could not be removed (SQL state {}).",
                                    cleanupFailure.getSQLState());
                }
            }
        } catch (PostgresProvisioningException exception) {
            cleanupRestoreDatabases(
                    hostPort, databasePassword, restoreDatabase, previousDatabase,
                    stagingCreated, sourceConnectionsDisabled, sourceRenamed, exception);
            throw exception;
        } catch (DockerException exception) {
            cleanupRestoreDatabases(
                    hostPort, databasePassword, restoreDatabase, previousDatabase,
                    stagingCreated, sourceConnectionsDisabled, sourceRenamed, exception);
            throw new PostgresProvisioningException("DOCKER_API_ERROR", exception);
        } catch (SQLException exception) {
            cleanupRestoreDatabases(
                    hostPort, databasePassword, restoreDatabase, previousDatabase,
                    stagingCreated, sourceConnectionsDisabled, sourceRenamed, exception);
            throw new PostgresProvisioningException("RESTORE_VERIFICATION_FAILED", exception);
        } catch (IOException exception) {
            cleanupRestoreDatabases(
                    hostPort, databasePassword, restoreDatabase, previousDatabase,
                    stagingCreated, sourceConnectionsDisabled, sourceRenamed, exception);
            throw new PostgresProvisioningException("BACKUP_STORAGE_ERROR", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupRestoreDatabases(
                    hostPort, databasePassword, restoreDatabase, previousDatabase,
                    stagingCreated, sourceConnectionsDisabled, sourceRenamed, exception);
            throw new PostgresProvisioningException("RESTORE_INTERRUPTED", exception);
        }
    }

    private String requireManagedContainer(UUID instanceId) {
        String containerId = findOwnedContainer(instanceId);
        if (containerId == null) {
            throw new PostgresProvisioningException("MANAGED_CONTAINER_NOT_FOUND");
        }
        ensureExistingContainerIsManaged(containerId, instanceId, "pim-pg-data-" + instanceId);
        return containerId;
    }

    private void runContainerCommand(
            String containerId,
            List<String> command,
            String databasePassword,
            InputStream standardInput,
            OutputStream standardOutput,
            String failureCode) throws IOException, InterruptedException {
        var createCommand = dockerClient.execCreateCmd(containerId)
                .withCmd(command.toArray(String[]::new))
                .withAttachStdin(standardInput != null)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false);
        if (databasePassword != null) {
            createCommand.withEnv(List.of("PGPASSWORD=" + databasePassword));
        }
        ExecCreateCmdResponse created = createCommand.exec();
        AtomicReference<IOException> outputFailure = new AtomicReference<>();
        AtomicReference<Throwable> streamFailure = new AtomicReference<>();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (standardOutput != null && frame.getStreamType() == StreamType.STDOUT) {
                    try {
                        standardOutput.write(frame.getPayload());
                    } catch (IOException exception) {
                        outputFailure.compareAndSet(null, exception);
                    }
                }
            }

            @Override
            public void onError(Throwable failure) {
                streamFailure.compareAndSet(null, failure);
                try {
                    close();
                } catch (IOException closeFailure) {
                    streamFailure.compareAndSet(null, closeFailure);
                }
            }
        };
        var startCommand = dockerClient.execStartCmd(created.getId()).withTty(false);
        if (standardInput != null) {
            startCommand.withStdIn(standardInput);
        }
        startCommand.exec(callback);
        callback.awaitCompletion();
        IOException failedOutput = outputFailure.get();
        if (failedOutput != null) {
            throw failedOutput;
        }
        Long exitCode = dockerClient.inspectExecCmd(created.getId()).exec().getExitCodeLong();
        Throwable callbackFailure = streamFailure.get();
        if (callbackFailure != null
                && !(Long.valueOf(0).equals(exitCode) && isCompletedPipeShutdown(callbackFailure))) {
            throw new PostgresProvisioningException(failureCode, callbackFailure);
        }
        if (callbackFailure != null) {
            LOGGER.debug("Docker exec response pipe closed after successful command completion.");
        }
        if (!Long.valueOf(0).equals(exitCode)) {
            throw new PostgresProvisioningException(failureCode);
        }
    }

    private boolean isCompletedPipeShutdown(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException
                    && current.getMessage() != null
                    && current.getMessage().contains("pipe has been ended")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void requireRunningContainer(String containerId) {
        InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
        if (!Boolean.TRUE.equals(container.getState().getRunning())) {
            throw new PostgresProvisioningException("MANAGED_CONTAINER_STOPPED");
        }
    }

    private Connection adminConnection(int hostPort, String databasePassword) throws SQLException {
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + hostPort
                + "/template1?connectTimeout=5&socketTimeout=30";
        return DriverManager.getConnection(jdbcUrl, "postgres", databasePassword);
    }

    private void verifyRestoredDatabase(int hostPort, String databasePassword, String databaseName)
            throws SQLException {
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + hostPort
                + "/" + databaseName + "?connectTimeout=5&socketTimeout=30";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", databasePassword);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
            if (!resultSet.next() || !databaseName.equals(resultSet.getString(1))) {
                throw new SQLException("The restored database could not be verified.");
            }
        }
    }

    private void cleanupRestoreDatabases(
            int hostPort,
            String databasePassword,
            String restoreDatabase,
            String previousDatabase,
            boolean stagingCreated,
            boolean sourceConnectionsDisabled,
            boolean sourceRenamed,
            Exception original) {
        try (Connection connection = adminConnection(hostPort, databasePassword);
             Statement statement = connection.createStatement()) {
            if (sourceRenamed) {
                statement.execute("ALTER DATABASE \"" + previousDatabase + "\" RENAME TO postgres");
                statement.execute("ALTER DATABASE postgres WITH ALLOW_CONNECTIONS true");
            } else if (sourceConnectionsDisabled) {
                statement.execute("ALTER DATABASE postgres WITH ALLOW_CONNECTIONS true");
            }
            if (stagingCreated) {
                statement.execute("DROP DATABASE IF EXISTS \"" + restoreDatabase + "\" WITH (FORCE)");
            }
        } catch (SQLException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
        }
    }

    private void removeOwnedVolume(UUID instanceId) {
        String volumeName = "pim-pg-data-" + instanceId;
        try {
            InspectVolumeResponse volume;
            try {
                volume = dockerClient.inspectVolumeCmd(volumeName).exec();
            } catch (NotFoundException exception) {
                throw new PostgresProvisioningException("MANAGED_VOLUME_NOT_FOUND", exception);
            }
            if (!hasExpectedLabels(volume.getLabels(), instanceId, "volume")) {
                throw new PostgresProvisioningException("VOLUME_LABEL_MISMATCH");
            }
            dockerClient.removeVolumeCmd(volumeName).exec();
        } catch (NotFoundException ignored) {
            // A prior delete may have removed the owned volume before the operation was retried.
        }
    }

    private void ensureVolume(UUID instanceId, String volumeName) {
        Map<String, String> labels = labels(instanceId, "volume");
        InspectVolumeResponse volume;
        try {
            volume = dockerClient.inspectVolumeCmd(volumeName).exec();
        } catch (NotFoundException exception) {
            dockerClient.createVolumeCmd()
                    .withName(volumeName)
                    .withLabels(labels)
                    .exec();
            volume = dockerClient.inspectVolumeCmd(volumeName).exec();
        }
        if (!hasExpectedLabels(volume.getLabels(), instanceId, "volume")) {
            throw new PostgresProvisioningException("VOLUME_LABEL_MISMATCH");
        }
    }

    private String findOwnedContainer(UUID instanceId) {
        List<Container> containers = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(labels(instanceId, "container"))
                .exec();
        if (containers.size() > 1) {
            throw new PostgresProvisioningException("DUPLICATE_MANAGED_CONTAINERS");
        }
        return containers.isEmpty() ? null : containers.get(0).getId();
    }

    private void ensureExistingContainerIsManaged(String containerId, UUID instanceId, String volumeName) {
        InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
        if (!POSTGRES_IMAGE.equals(container.getConfig().getImage())) {
            throw new PostgresProvisioningException("CONTAINER_IMAGE_MISMATCH");
        }
        boolean hasPersistentVolume = container.getMounts().stream()
                .anyMatch(mount -> Objects.equals(volumeName, mount.getName())
                        && POSTGRES_DATA_PATH.equals(mount.getDestination().toString()));
        if (!hasPersistentVolume) {
            throw new PostgresProvisioningException("CONTAINER_VOLUME_MISMATCH");
        }
        InspectVolumeResponse volume = dockerClient.inspectVolumeCmd(volumeName).exec();
        if (!hasExpectedLabels(volume.getLabels(), instanceId, "volume")) {
            throw new PostgresProvisioningException("VOLUME_LABEL_MISMATCH");
        }
    }

    private String createContainer(UUID instanceId, String volumeName, String databasePassword)
            throws InterruptedException {
        Ports portBindings = new Ports();
        portBindings.bind(POSTGRES_PORT, Ports.Binding.bindIpAndPort("127.0.0.1", 0));
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(new Bind(volumeName, new Volume(POSTGRES_DATA_PATH)))
                .withPortBindings(portBindings)
                .withRestartPolicy(RestartPolicy.noRestart());
        CreateContainerResponse container = dockerClient.createContainerCmd(POSTGRES_IMAGE)
                .withName("pim-pg-" + instanceId)
                .withEnv(
                        "POSTGRES_USER=postgres",
                        "POSTGRES_PASSWORD=" + databasePassword,
                        "POSTGRES_DB=postgres")
                .withLabels(labels(instanceId, "container"))
                .withExposedPorts(POSTGRES_PORT)
                .withHostConfig(hostConfig)
                .exec();
        return container.getId();
    }

    private void ensureImage() throws InterruptedException {
        try {
            dockerClient.inspectImageCmd(POSTGRES_IMAGE).exec();
        } catch (NotFoundException exception) {
            boolean pulled = dockerClient.pullImageCmd(POSTGRES_IMAGE)
                    .start()
                    .awaitCompletion(5, TimeUnit.MINUTES);
            if (!pulled) {
                throw new PostgresProvisioningException("POSTGRES_IMAGE_PULL_TIMEOUT");
            }
        }
    }

    private int getHostPort(String containerId) {
        InspectContainerResponse container = dockerClient.inspectContainerCmd(containerId).exec();
        Ports.Binding[] bindings = container.getNetworkSettings().getPorts().getBindings().get(POSTGRES_PORT);
        if (bindings == null || bindings.length != 1
                || !"127.0.0.1".equals(bindings[0].getHostIp())) {
            throw new PostgresProvisioningException("POSTGRES_PORT_BINDING_INVALID");
        }
        return Integer.parseInt(bindings[0].getHostPortSpec());
    }

    private void waitUntilReady(int hostPort, String databasePassword)
            throws SQLException, InterruptedException {
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + hostPort
                + "/postgres?connectTimeout=2&socketTimeout=2";
        long deadline = System.nanoTime() + READINESS_TIMEOUT.toNanos();
        SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(jdbcUrl, "postgres", databasePassword)) {
                return;
            } catch (SQLException exception) {
                lastFailure = exception;
                Thread.sleep(500);
            }
        }
        throw new SQLException("PostgreSQL did not become ready before the timeout.", lastFailure);
    }

    private Map<String, String> labels(UUID instanceId, String resourceKind) {
        return Map.of(
                PLATFORM_LABEL, PLATFORM_VALUE,
                INSTANCE_LABEL, instanceId.toString(),
                RESOURCE_LABEL, resourceKind);
    }

    private boolean hasExpectedLabels(Map<String, String> actual, UUID instanceId, String resourceKind) {
        return actual != null
                && PLATFORM_VALUE.equals(actual.get(PLATFORM_LABEL))
                && instanceId.toString().equals(actual.get(INSTANCE_LABEL))
                && resourceKind.equals(actual.get(RESOURCE_LABEL));
    }
}
