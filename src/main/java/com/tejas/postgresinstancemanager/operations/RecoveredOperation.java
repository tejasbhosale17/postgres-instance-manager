package com.tejas.postgresinstancemanager.operations;

import java.util.UUID;

public record RecoveredOperation(
        UUID id,
        UUID instanceId,
        String operationType,
        String previousInstanceState,
        String state,
        String correlationId) {
}
