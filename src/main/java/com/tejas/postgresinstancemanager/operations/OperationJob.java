package com.tejas.postgresinstancemanager.operations;

import java.util.UUID;

public record OperationJob(UUID id, UUID instanceId, String credentialCiphertext) {
}
