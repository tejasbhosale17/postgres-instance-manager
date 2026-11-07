package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.tejas.postgresinstancemanager.backups.BackupFileStore;
import com.tejas.postgresinstancemanager.backups.BackupRecord;
import com.tejas.postgresinstancemanager.backups.BackupRepository;
import com.tejas.postgresinstancemanager.instances.InstanceRecord;
import com.tejas.postgresinstancemanager.instances.InstanceRepository;
import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionException;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ProvisioningWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvisioningWorker.class);

    private final String workerId = UUID.randomUUID().toString();
    private final Duration leaseDuration;
    private final ConcurrentMap<UUID, OperationJob> activeOperations = new ConcurrentHashMap<>();
    private final OperationRepository operationRepository;
    private final OperationCompletionService completionService;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ManagedPostgresDocker managedPostgresDocker;
    private final MeterRegistry meterRegistry;
    private final BackupFileStore backupFileStore;
    private final BackupRepository backupRepository;
    private final InstanceRepository instanceRepository;

    public ProvisioningWorker(
            OperationRepository operationRepository,
            OperationCompletionService completionService,
            CredentialEncryptionService credentialEncryptionService,
            ManagedPostgresDocker managedPostgresDocker,
            MeterRegistry meterRegistry,
            BackupFileStore backupFileStore,
            BackupRepository backupRepository,
            InstanceRepository instanceRepository,
            @Value("${app.worker.lease-duration-seconds:120}") long leaseDurationSeconds) {
        if (leaseDurationSeconds < 30) {
            throw new IllegalStateException("Worker lease duration must be at least 30 seconds.");
        }
        this.operationRepository = operationRepository;
        this.completionService = completionService;
        this.credentialEncryptionService = credentialEncryptionService;
        this.managedPostgresDocker = managedPostgresDocker;
        this.meterRegistry = meterRegistry;
        this.backupFileStore = backupFileStore;
        this.backupRepository = backupRepository;
        this.instanceRepository = instanceRepository;
        leaseDuration = Duration.ofSeconds(leaseDurationSeconds);
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:500}")
    public void processNextOperation() {
        operationRepository.claimNext(workerId, leaseDuration).ifPresent(operation -> {
            activeOperations.put(operation.id(), operation);
            try {
                process(operation);
            } finally {
                activeOperations.remove(operation.id());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.worker.lease-renewal-ms:30000}")
    public void renewActiveLeases() {
        activeOperations.values().forEach(operation -> {
            if (!operationRepository.renewLease(operation, workerId, leaseDuration)) {
                activeOperations.remove(operation.id(), operation);
                LOGGER.warn("Worker {} lost the lease for operation {}", workerId, operation.id());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.worker.recovery-delay-ms:30000}")
    public void recoverExpiredLeases() {
        completionService.recoverExpiredLeases();
    }

    private void process(OperationJob operation) {
        if (operation.correlationId() != null) {
            MDC.put("correlationId", operation.correlationId());
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            OperationResult result = switch (operation.operationType()) {
                case "CREATE_INSTANCE" -> create(operation);
                case "START_INSTANCE" -> OperationResult.withHostPort(
                        managedPostgresDocker.start(
                                operation.instanceId(), decryptCredential(operation)));
                case "STOP_INSTANCE" -> {
                    managedPostgresDocker.stop(operation.instanceId());
                    yield OperationResult.empty();
                }
                case "RESTART_INSTANCE" -> OperationResult.withHostPort(
                        managedPostgresDocker.restart(
                                operation.instanceId(), decryptCredential(operation)));
                case "DELETE_INSTANCE" -> {
                    managedPostgresDocker.delete(operation.instanceId());
                    yield OperationResult.empty();
                }
                case "BACKUP" -> createBackup(operation);
                case "RESTORE" -> {
                    restoreBackup(operation);
                    yield OperationResult.empty();
                }
                default -> throw new IllegalStateException(
                        "Unsupported operation type: " + operation.operationType());
            };
            if (!completionService.complete(operation, workerId, result)) {
                LOGGER.warn("Ignoring completion of operation {} after its lease expired.", operation.id());
            }
        } catch (CredentialEncryptionException exception) {
            fail(operation, "CREDENTIAL_DECRYPTION_FAILED");
        } catch (PostgresProvisioningException exception) {
            fail(operation, exception.errorCode());
        } finally {
            sample.stop(Timer.builder("pim.worker.operation.duration")
                    .tag("operation_type", operation.operationType())
                    .register(meterRegistry));
            MDC.remove("correlationId");
        }
    }

    private OperationResult create(OperationJob operation) {
        String password = decryptCredential(operation);
        ProvisionedPostgres postgres = managedPostgresDocker.provision(
                operation.instanceId(),
                password);
        return OperationResult.provisioned(postgres);
    }

    private OperationResult createBackup(OperationJob operation) {
        if (operation.backupId() == null) {
            throw new PostgresProvisioningException("BACKUP_ID_MISSING");
        }
        String password = decryptCredential(operation);
        var result = backupFileStore.create(
                operation.ownerId(), operation.backupId(), operation.instanceId(), password);
        return OperationResult.backup(operation.backupId(), result);
    }

    private void restoreBackup(OperationJob operation) {
        if (operation.backupId() == null) {
            throw new PostgresProvisioningException("BACKUP_ID_MISSING");
        }
        BackupRecord backup = backupRepository.findById(operation.backupId())
                .filter(record -> "READY".equals(record.state()))
                .orElseThrow(() -> new PostgresProvisioningException("BACKUP_NOT_READY"));
        InstanceRecord instance = instanceRepository.findById(operation.instanceId())
                .orElseThrow(() -> new PostgresProvisioningException("RESTORE_TARGET_NOT_FOUND"));
        if (instance.hostPort() == null) {
            throw new PostgresProvisioningException("MANAGED_INSTANCE_PORT_UNAVAILABLE");
        }
        backupFileStore.restore(
                backup,
                operation.instanceId(),
                decryptCredential(operation),
                instance.hostPort());
    }

    private String decryptCredential(OperationJob operation) {
        return credentialEncryptionService.decrypt(
                operation.instanceId(),
                operation.credentialCiphertext());
    }

    private void fail(OperationJob operation, String errorCode) {
        LOGGER.error("Operation {} failed on attempt {} of {} with code {}",
                operation.id(), operation.attemptCount(), operation.maxAttempts(), errorCode);
        completionService.failOrScheduleRetry(operation, workerId, errorCode);
    }
}
