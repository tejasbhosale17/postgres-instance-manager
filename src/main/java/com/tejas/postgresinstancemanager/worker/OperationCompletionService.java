package com.tejas.postgresinstancemanager.worker;

import com.tejas.postgresinstancemanager.audit.AuditEventService;
import com.tejas.postgresinstancemanager.instances.InstanceRepository;
import com.tejas.postgresinstancemanager.operations.OperationJob;
import com.tejas.postgresinstancemanager.operations.OperationRepository;
import com.tejas.postgresinstancemanager.operations.RecoveredOperation;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationCompletionService {

    private final OperationRepository operationRepository;
    private final InstanceRepository instanceRepository;
    private final AuditEventService auditEventService;
    private final MeterRegistry meterRegistry;

    public OperationCompletionService(
            OperationRepository operationRepository,
            InstanceRepository instanceRepository,
            AuditEventService auditEventService,
            MeterRegistry meterRegistry) {
        this.operationRepository = operationRepository;
        this.instanceRepository = instanceRepository;
        this.auditEventService = auditEventService;
        this.meterRegistry = meterRegistry;
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
        recordOutcome(operation.id(), operation.instanceId(), operation.operationType(),
                "OPERATION_SUCCEEDED", "SUCCEEDED", null, operation.correlationId());
        meterRegistry.counter(
                "pim.operations.outcomes",
                "operation_type", operation.operationType(),
                "state", "succeeded").increment();
        return true;
    }

    @Transactional
    public void failOrScheduleRetry(OperationJob operation, String workerId, String errorCode) {
        operationRepository.markRetryOrFailed(operation, workerId, errorCode)
                .ifPresent(failed -> {
                    if (failed) {
                        instanceRepository.restoreState(
                                operation.instanceId(), operation.previousInstanceState());
                    }
                    String state = failed ? "FAILED" : "RETRY_WAIT";
                    recordOutcome(operation.id(), operation.instanceId(), operation.operationType(),
                            "OPERATION_" + state, state, errorCode, operation.correlationId());
                    meterRegistry.counter(
                            "pim.operations.outcomes",
                            "operation_type", operation.operationType(),
                            "state", state.toLowerCase()).increment();
                });
    }

    @Transactional
    public void recoverExpiredLeases() {
        for (RecoveredOperation operation : operationRepository.recoverExpiredLeases()) {
            recordOutcome(operation.id(), operation.instanceId(), operation.operationType(),
                    "OPERATION_LEASE_RECOVERED", operation.state(),
                    "WORKER_LEASE_EXPIRED", operation.correlationId());
            if ("FAILED".equals(operation.state())) {
                instanceRepository.restoreState(
                        operation.instanceId(), operation.previousInstanceState());
            }
        }
    }

    private void recordOutcome(
            UUID operationId,
            UUID instanceId,
            String operationType,
            String action,
            String state,
            String errorCode,
            String correlationId) {
        Map<String, String> details = new HashMap<>();
        details.put("operationType", operationType);
        details.put("instanceId", instanceId.toString());
        details.put("state", state);
        if (errorCode != null) {
            details.put("errorCode", errorCode);
        }
        auditEventService.record(null, action, "OPERATION", operationId, details, correlationId);
    }
}
