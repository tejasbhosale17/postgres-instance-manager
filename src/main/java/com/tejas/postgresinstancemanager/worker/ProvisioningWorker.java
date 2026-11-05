package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionException;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ProvisioningWorker(
            OperationRepository operationRepository,
            OperationCompletionService completionService,
            CredentialEncryptionService credentialEncryptionService,
            ManagedPostgresDocker managedPostgresDocker,
            @Value("${app.worker.lease-duration-seconds:120}") long leaseDurationSeconds) {
        if (leaseDurationSeconds < 30) {
            throw new IllegalStateException("Worker lease duration must be at least 30 seconds.");
        }
        this.operationRepository = operationRepository;
        this.completionService = completionService;
        this.credentialEncryptionService = credentialEncryptionService;
        this.managedPostgresDocker = managedPostgresDocker;
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
        }
    }

    private OperationResult create(OperationJob operation) {
        String password = decryptCredential(operation);
        ProvisionedPostgres postgres = managedPostgresDocker.provision(
                operation.instanceId(),
                password);
        return OperationResult.provisioned(postgres);
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
