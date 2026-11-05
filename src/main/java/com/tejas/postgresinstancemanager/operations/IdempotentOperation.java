package com.tejas.postgresinstancemanager.operations;

import java.util.UUID;

public record IdempotentOperation(UUID id, String state, String requestHash) {
}
