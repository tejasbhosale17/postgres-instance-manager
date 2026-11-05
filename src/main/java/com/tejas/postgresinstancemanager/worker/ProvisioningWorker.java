package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.instances.InstanceRepository;
import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionException;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProvisioningWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProvisioningWorker.class);

    private final OperationRepository operationRepository;
    private final InstanceRepository instanceRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ManagedPostgresDocker managedPostgresDocker;

    public ProvisioningWorker(
            OperationRepository operationRepository,
            InstanceRepository instanceRepository,
            CredentialEncryptionService credentialEncryptionService,
            ManagedPostgresDocker managedPostgresDocker) {
        this.operationRepository = operationRepository;
        this.instanceRepository = instanceRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.managedPostgresDocker = managedPostgresDocker;
    }

    @Scheduled(fixedDelayString = "${app.worker.poll-delay-ms:500}")
    public void processNextOperation() {
        operationRepository.claimNext().ifPresent(this::provision);
    }

    private void provision(OperationJob operation) {
        try {
            switch (operation.operationType()) {
                case "CREATE_INSTANCE" -> create(operation);
                case "START_INSTANCE" -> {
                    int hostPort = managedPostgresDocker.start(
                            operation.instanceId(), decryptCredential(operation));
                    instanceRepository.markReadyAfterLifecycle(operation.instanceId(), hostPort);
                }
                case "STOP_INSTANCE" -> {
                    managedPostgresDocker.stop(operation.instanceId());
                    instanceRepository.markStopped(operation.instanceId());
                }
                case "RESTART_INSTANCE" -> {
                    int hostPort = managedPostgresDocker.restart(
                            operation.instanceId(), decryptCredential(operation));
                    instanceRepository.markReadyAfterLifecycle(operation.instanceId(), hostPort);
                }
                case "DELETE_INSTANCE" -> {
                    managedPostgresDocker.delete(operation.instanceId());
                    instanceRepository.markDeleted(operation.instanceId());
                }
                default -> throw new IllegalStateException(
                        "Unsupported operation type: " + operation.operationType());
            }
            operationRepository.markSucceeded(operation.id());
        } catch (CredentialEncryptionException exception) {
            fail(operation, "CREDENTIAL_DECRYPTION_FAILED");
        } catch (PostgresProvisioningException exception) {
            fail(operation, exception.errorCode());
        }
    }

    private void create(OperationJob operation) {
        String password = decryptCredential(operation);
        ProvisionedPostgres postgres = managedPostgresDocker.provision(
                operation.instanceId(),
                password);
        instanceRepository.markReady(
                operation.instanceId(),
                postgres.containerId(),
                postgres.volumeName(),
                postgres.hostPort());
    }

    private String decryptCredential(OperationJob operation) {
        return credentialEncryptionService.decrypt(
                operation.instanceId(),
                operation.credentialCiphertext());
    }

    private void fail(OperationJob operation, String errorCode) {
        LOGGER.error("Provision operation {} failed with code {}", operation.id(), errorCode);
        instanceRepository.markFailed(operation.instanceId());
        operationRepository.markFailed(operation.id(), errorCode);
    }
}
