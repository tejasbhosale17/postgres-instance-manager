package com.tejas.postgresinstancemanager.instances;

import com.tejas.postgresinstancemanager.api.ResourceNotFoundException;
import com.tejas.postgresinstancemanager.audit.AuditEventService;
import com.tejas.postgresinstancemanager.backups.BackupRecord;
import com.tejas.postgresinstancemanager.backups.BackupRepository;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.operations.IdempotentOperation;
import com.tejas.postgresinstancemanager.security.CredentialEncryptionService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class InstanceService {

    private static final String POSTGRES_VERSION = "17.6";

    private final InstanceRepository instanceRepository;
    private final OperationRepository operationRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final AuditEventService auditEventService;
    private final MeterRegistry meterRegistry;
    private final BackupRepository backupRepository;

    public InstanceService(
            InstanceRepository instanceRepository,
            OperationRepository operationRepository,
            CredentialEncryptionService credentialEncryptionService,
            AuditEventService auditEventService,
            MeterRegistry meterRegistry,
            BackupRepository backupRepository) {
        this.instanceRepository = instanceRepository;
        this.operationRepository = operationRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.auditEventService = auditEventService;
        this.meterRegistry = meterRegistry;
        this.backupRepository = backupRepository;
    }

    @Transactional
    public OperationAccepted requestProvisioning(
            UUID ownerId,
            CreateInstanceRequest request,
            String idempotencyKey,
            String correlationId) {
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
                    idempotencyKey == null ? null : requestHash,
                    correlationId);
            recordQueuedOperation(ownerId, operationId, "CREATE_INSTANCE", instanceId, correlationId);
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
            String idempotencyKey,
            String correlationId) {
        return queueOperation(ownerId, administrator, instanceId, "START_INSTANCE",
                List.of("STOPPED"), "STARTING", idempotencyKey, null, null, correlationId);
    }

    @Transactional
    public OperationAccepted requestStop(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey,
            String correlationId) {
        return queueOperation(ownerId, administrator, instanceId, "STOP_INSTANCE",
                List.of("READY"), "STOPPING", idempotencyKey, null, null, correlationId);
    }

    @Transactional
    public OperationAccepted requestRestart(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey,
            String correlationId) {
        return queueOperation(ownerId, administrator, instanceId, "RESTART_INSTANCE",
                List.of("READY", "STOPPED"), "RESTARTING", idempotencyKey, null, null, correlationId);
    }

    @Transactional
    public OperationAccepted requestDelete(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey,
            String correlationId) {
        return queueOperation(ownerId, administrator, instanceId, "DELETE_INSTANCE",
                List.of("READY", "STOPPED", "FAILED"), "DELETING",
                idempotencyKey, null, null, correlationId);
    }

    @Transactional
    public OperationAccepted requestBackup(
            UUID requesterId,
            boolean administrator,
            UUID instanceId,
            String idempotencyKey,
            String correlationId) {
        return queueOperation(requesterId, administrator, instanceId, "BACKUP",
                List.of("READY"), "BACKING_UP", idempotencyKey, UUID.randomUUID(), null, correlationId);
    }

    @Transactional
    public OperationAccepted requestRestore(
            UUID requesterId,
            boolean administrator,
            UUID instanceId,
            UUID backupId,
            boolean confirmDataReplacement,
            String idempotencyKey,
            String correlationId) {
        if (!confirmDataReplacement) {
            throw new InvalidDataReplacementConfirmationException();
        }
        BackupRecord backup = (administrator
                ? backupRepository.findById(backupId)
                : backupRepository.findByIdAndOwnerId(backupId, requesterId))
                .orElseThrow(ResourceNotFoundException::new);
        if (!"READY".equals(backup.state())) {
            throw new InstanceStateConflictException();
        }
        return queueOperation(requesterId, administrator, instanceId, "RESTORE",
                List.of("READY"), "RESTORING", idempotencyKey, backupId, backupId.toString(), correlationId);
    }

    private OperationAccepted queueOperation(
            UUID ownerId,
            boolean administrator,
            UUID instanceId,
            String operationType,
            List<String> allowedStates,
            String nextState,
            String idempotencyKey,
            UUID backupId,
            String requestIdentity,
            String correlationId) {
        validateIdempotencyKey(idempotencyKey);
        String requestHash = credentialEncryptionService.fingerprint(
                descriptor(operationType, instanceId.toString(), requestIdentity));
        Optional<IdempotentOperation> existing = findExisting(ownerId, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            return new OperationAccepted(existing.get().id(), existing.get().state());
        }
        InstanceRecord instance = instanceRepository.findById(instanceId)
                .filter(record -> administrator || ownerId.equals(record.ownerId()))
                .orElseThrow(ResourceNotFoundException::new);
        UUID operationId = UUID.randomUUID();
        String previousInstanceState = instanceRepository.beginOperation(
                instanceId, ownerId, administrator, allowedStates, nextState);
        String credentialCiphertext = switch (operationType) {
            case "START_INSTANCE", "RESTART_INSTANCE", "BACKUP", "RESTORE" -> operationRepository
                    .findProvisioningCredential(instanceId)
                    .orElseThrow(InstanceStateConflictException::new);
            default -> null;
        };
        operationRepository.enqueueInstanceOperation(
                operationId,
                instance.ownerId(),
                ownerId,
                instanceId,
                operationType,
                credentialCiphertext,
                backupId,
                previousInstanceState,
                idempotencyKey,
                idempotencyKey == null ? null : requestHash,
                correlationId);
        if ("BACKUP".equals(operationType)) {
            backupRepository.createPending(backupId, instance.ownerId(), instanceId, operationId);
        }
        recordQueuedOperation(ownerId, operationId, operationType, instanceId, correlationId);
        return new OperationAccepted(operationId, "QUEUED");
    }

    private void recordQueuedOperation(
            UUID ownerId,
            UUID operationId,
            String operationType,
            UUID instanceId,
            String correlationId) {
        auditEventService.record(
                ownerId,
                "OPERATION_QUEUED",
                "OPERATION",
                operationId,
                Map.of("operationType", operationType, "instanceId", instanceId.toString()),
                correlationId);
        meterRegistry.counter("pim.operations.accepted", "operation_type", operationType).increment();
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
