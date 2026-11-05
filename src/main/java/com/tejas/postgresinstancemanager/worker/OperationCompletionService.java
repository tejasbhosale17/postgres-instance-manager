package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.instances.InstanceRepository;
import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.operations.RecoveredOperation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationCompletionService {

    private final OperationRepository operationRepository;
    private final InstanceRepository instanceRepository;

    public OperationCompletionService(
            OperationRepository operationRepository,
            InstanceRepository instanceRepository) {
        this.operationRepository = operationRepository;
        this.instanceRepository = instanceRepository;
    }

    @Transactional
    public boolean complete(OperationJob operation, String workerId, OperationResult result) {
        if (!operationRepository.markSucceeded(operation, workerId)) {
            return false;
        }
        switch (operation.operationType()) {
            case "CREATE_INSTANCE" -> instanceRepository.markReady(
                    operation.instanceId(),
                    result.containerId(),
                    result.volumeName(),
                    result.hostPort());
            case "START_INSTANCE", "RESTART_INSTANCE" ->
                    instanceRepository.markReadyAfterLifecycle(operation.instanceId(), result.hostPort());
            case "STOP_INSTANCE" -> instanceRepository.markStopped(operation.instanceId());
            case "DELETE_INSTANCE" -> instanceRepository.markDeleted(operation.instanceId());
            default -> throw new IllegalStateException(
                    "Unsupported operation type: " + operation.operationType());
        }
        return true;
    }

    @Transactional
    public void failOrScheduleRetry(OperationJob operation, String workerId, String errorCode) {
        operationRepository.markRetryOrFailed(operation, workerId, errorCode)
                .filter(Boolean::booleanValue)
                .ifPresent(ignored -> instanceRepository.restoreState(
                        operation.instanceId(), operation.previousInstanceState()));
    }

    @Transactional
    public void recoverExpiredLeases() {
        for (RecoveredOperation operation : operationRepository.recoverExpiredLeases()) {
            if ("FAILED".equals(operation.state())) {
                instanceRepository.restoreState(
                        operation.instanceId(), operation.previousInstanceState());
            }
        }
    }
}
