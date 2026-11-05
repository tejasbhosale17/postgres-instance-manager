package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    @Transactional
    public OperationAccepted requestStart(UUID ownerId, boolean administrator, UUID instanceId) {
        return queueOperation(ownerId, administrator, instanceId, "START_INSTANCE",
                List.of("STOPPED"), "STARTING");
    }

    @Transactional
    public OperationAccepted requestStop(UUID ownerId, boolean administrator, UUID instanceId) {
        return queueOperation(ownerId, administrator, instanceId, "STOP_INSTANCE",
                List.of("READY"), "STOPPING");
    }

    @Transactional
    public OperationAccepted requestRestart(UUID ownerId, boolean administrator, UUID instanceId) {
        return queueOperation(ownerId, administrator, instanceId, "RESTART_INSTANCE",
                List.of("READY", "STOPPED"), "RESTARTING");
    }

    @Transactional
    public OperationAccepted requestDelete(UUID ownerId, boolean administrator, UUID instanceId) {
        return queueOperation(ownerId, administrator, instanceId, "DELETE_INSTANCE",
                List.of("READY", "STOPPED", "FAILED"), "DELETING");
    }

    private OperationAccepted queueOperation(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String operationType,
            List<String> allowedStates,
            String nextState) {
        UUID operationId = UUID.randomUUID();
        instanceRepository.beginOperation(
                instanceId, ownerId, administrator, allowedStates, nextState);
        String credentialCiphertext = switch (operationType) {
            case "START_INSTANCE", "RESTART_INSTANCE" -> operationRepository
                    .findProvisioningCredential(instanceId)
                    .orElseThrow(InstanceStateConflictException::new);
            default -> null;
        };
        operationRepository.enqueueInstanceOperation(
                operationId, ownerId, instanceId, operationType, credentialCiphertext);
        return new OperationAccepted(operationId, "QUEUED");
    }
}
