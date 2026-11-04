package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InstanceService {

    private static final String POSTGRES_VERSION = "17.6";

    private final InstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final CredentialEncryptionService credentialEncryptionService;

    public InstanceService(
            InstanceRepository instanceRepository,
            OperationRepository operationRepository,
            CredentialEncryptionService credentialEncryptionService) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.credentialEncryptionService = credentialEncryptionService;
    }

    @Transactional
    public OperationAccepted requestProvisioning(UUID ownerId, CreateInstanceRequest request) {
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String credentialCiphertext = credentialEncryptionService.encrypt(instanceId, request.databasePassword());
        try {
            instanceRepository.create(instanceId, ownerId, request.name(), POSTGRES_VERSION);
            operationRepository.enqueueProvisioning(operationId, ownerId, instanceId, credentialCiphertext);
        } catch (DuplicateKeyException exception) {
            throw new InstanceNameAlreadyExistsException();
        }
        return new OperationAccepted(operationId, "QUEUED");
    }
}
