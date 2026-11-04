package com.tejas.postgresinstancemanager.instances;

import java.util.UUID;

public record OperationAccepted(UUID operationId, String state) {
}
