package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.operations.IdempotentOperation;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
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
    public OperationAccepted requestProvisioning(
            UUID ownerId,
            CreateInstanceRequest request,
            String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = credentialEncryptionService.fingerprint(
                descriptor("CREATE_INSTANCE", request.name(), request.databasePassword()));
        Optional<IdempotentOperation> existing = findExisting(ownerId, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return new OperationAccepted(existing.get().id(), existing.get().state());
        }
        UUID instanceId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        String credentialCiphertext = credentialEncryptionService.encrypt(instanceId, request.databasePassword());
        try {
            instanceRepository.create(instanceId, ownerId, request.name(), POSTGRES_VERSION);
            operationRepository.enqueueProvisioning(
                    operationId,
                    ownerId,
                    instanceId,
                    credentialCiphertext,
                    idempotencyKey,
                    idempotencyKey == null ? null : requestHash);
        } catch (DuplicateKeyException exception) {
            throw new InstanceNameAlreadyExistsException();
        }
        return new OperationAccepted(operationId, "QUEUED");
    }

    @Transactional
    public OperationAccepted requestStart(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey) {
        return queueOperation(ownerId, administrator, instanceId, "START_INSTANCE",
                List.of("STOPPED"), "STARTING", idempotencyKey);
    }

    @Transactional
    public OperationAccepted requestStop(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey) {
        return queueOperation(ownerId, administrator, instanceId, "STOP_INSTANCE",
                List.of("READY"), "STOPPING", idempotencyKey);
    }

    @Transactional
    public OperationAccepted requestRestart(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey) {
        return queueOperation(ownerId, administrator, instanceId, "RESTART_INSTANCE",
                List.of("READY", "STOPPED"), "RESTARTING", idempotencyKey);
    }

    @Transactional
    public OperationAccepted requestDelete(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey) {
        return queueOperation(ownerId, administrator, instanceId, "DELETE_INSTANCE",
                List.of("READY", "STOPPED", "FAILED"), "DELETING", idempotencyKey);
    }

    private OperationAccepted queueOperation(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String operationType,
            List<String> allowedStates,
            String nextState,
            String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = credentialEncryptionService.fingerprint(descriptor(operationType, instanceId.toString()));
        Optional<IdempotentOperation> existing = findExisting(ownerId, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return new OperationAccepted(existing.get().id(), existing.get().state());
        }
        UUID operationId = UUID.randomUUID();
        String previousInstanceState = instanceRepository.beginOperation(
                instanceId, ownerId, administrator, allowedStates, nextState);
        String credentialCiphertext = switch (operationType) {
            case "START_INSTANCE", "RESTART_INSTANCE" -> operationRepository
                    .findProvisioningCredential(instanceId)
                    .orElseThrow(InstanceStateConflictException::new);
            default -> null;
        };
        operationRepository.enqueueInstanceOperation(
                operationId,
                ownerId,
                instanceId,
                operationType,
                credentialCiphertext,
                previousInstanceState,
                idempotencyKey,
                idempotencyKey == null ? null : requestHash);
        return new OperationAccepted(operationId, "QUEUED");
    }

    private Optional<IdempotentOperation> findExisting(
            UUID ownerId,
            String idempotencyKey,
            String requestHash) {
        operationRepository.lockIdempotencyKey(ownerId, idempotencyKey);
        Optional<IdempotentOperation> existing = operationRepository.findIdempotent(ownerId, idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        boolean matches = MessageDigest.isEqual(
                existing.get().requestHash().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII));
        if (!matches) {
            throw new IdempotencyKeyConflictException();
        }
        return existing;
    }

    private void validateIdempotencyKey(String key) {
        if (key != null && (key.isBlank() || key.length() > 255)) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private String descriptor(String... values) {
        StringBuilder descriptor = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                descriptor.append("-1:");
            } else {
                descriptor.append(value.length()).append(':').append(value);
            }
        }
        return descriptor.toString();
    }

}
