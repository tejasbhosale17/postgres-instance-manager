package com.tejas.postgresinstancemanager.operations;

import java.util.UUID;

public record OperationJob(
        UUID id,
        UUID instanceId,
        String operationType,
        String credentialCiphertext,
        String previousInstanceState,
        UUID leaseToken,
        int attemptCount,
        int maxAttempts,
        String correlationId) {
}
