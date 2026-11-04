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
            String password = credentialEncryptionService.decrypt(
                    operation.instanceId(),
                    operation.credentialCiphertext());
            ProvisionedPostgres postgres = managedPostgresDocker.provision(
                    operation.instanceId(),
                    password);
            instanceRepository.markReady(
                    operation.instanceId(),
                    postgres.containerId(),
                    postgres.volumeName(),
                    postgres.hostPort());
            operationRepository.markSucceeded(operation.id());
        } catch (CredentialEncryptionException exception) {
            fail(operation, "CREDENTIAL_DECRYPTION_FAILED");
        } catch (PostgresProvisioningException exception) {
            fail(operation, exception.errorCode());
        }
    }

    private void fail(OperationJob operation, String errorCode) {
        LOGGER.error("Provision operation {} failed with code {}", operation.id(), errorCode);
        instanceRepository.markFailed(operation.instanceId());
        operationRepository.markFailed(operation.id(), errorCode);
    }
}
